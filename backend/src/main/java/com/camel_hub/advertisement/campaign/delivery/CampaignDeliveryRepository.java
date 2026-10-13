package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.email.smtp.SmtpModels;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpQuotaPolicy;
import com.camel_hub.advertisement.email.smtp.SmtpQuotaRepository;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRuntimePolicy;
import com.camel_hub.advertisement.campaign.migration.CampaignMigrationDeliveryFence;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;

/**
 * Transactional reservation and settlement boundary for production campaign mail.
 * Network I/O is intentionally absent from this class.
 */
public final class CampaignDeliveryRepository {

	private static final Instant COOLDOWN_SENTINEL = Instant.parse("1900-01-01T00:00:00Z");
	private static final Pattern ENHANCED_STATUS = Pattern.compile("(?<!\\d)([245]\\.\\d\\.\\d{1,3})(?!\\d)");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String PAPER_CONTACT_POLICY = """
			EXISTS (SELECT 1 FROM campaigns policy WHERE policy.id = r.campaign_id
			  AND policy.recipient_policy = 'PAPER_CONTACT' AND r.paper_id = ANY(policy.paper_contact_ids))
			""";
	private static final String CURRENT_APPROVED_EVIDENCE = """
			EXISTS (
			    SELECT 1
			    FROM paper_author_contacts pac
			    LEFT JOIN paper_authors pa ON pa.id = pac.paper_author_id
			    JOIN papers p ON p.id = pac.paper_id AND p.deleted_at IS NULL
			    JOIN extraction_runs er ON er.id = pac.extraction_run_id AND er.paper_id = pac.paper_id
			    WHERE pac.contact_id = r.contact_id
			      AND pac.paper_id = r.paper_id
			      AND pac.verification_status <> 'REJECTED'
			      AND ((%s AND (r.author_name_snapshot IS NULL OR (
			        pa.author_id = r.author_id AND pa.paper_id = r.paper_id
			        AND (pac.confidence = 'HIGH' OR (pac.human_verified AND pac.verification_status = 'CONFIRMED')))))
			        OR (pa.author_id = r.author_id AND pa.paper_id = r.paper_id
			        AND (pac.confidence = 'HIGH' OR (pac.confidence = 'MEDIUM'
			           AND pac.human_verified AND pac.verification_status = 'CONFIRMED'))))
			      AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
			      AND pac.id = (
			          SELECT latest_pac.id
			          FROM paper_author_contacts latest_pac
			          JOIN extraction_runs latest_er ON latest_er.id = latest_pac.extraction_run_id AND latest_er.paper_id = latest_pac.paper_id
			            AND latest_er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
			          WHERE latest_pac.contact_id = r.contact_id
			            AND latest_pac.paper_id = r.paper_id
			          ORDER BY latest_er.completed_at DESC NULLS LAST,
			                   latest_pac.created_at DESC, latest_pac.id
			          LIMIT 1)
			      AND EXISTS (SELECT 1 FROM extraction_evidence ev
			                  WHERE ev.paper_author_contact_id = pac.id)
			)
			""".formatted(PAPER_CONTACT_POLICY);
	private static final String UNDELIVERABLE_PREDICATE = """
			EXISTS (SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac = r.email_hmac)
			OR EXISTS (SELECT 1 FROM suppression_entries s
			           WHERE s.email_hmac = r.email_hmac
			             AND (s.expires_at IS NULL OR s.expires_at > :now))
			OR EXISTS (SELECT 1 FROM campaign_exclusions x
			           WHERE x.campaign_id = r.campaign_id AND x.email_hmac = r.email_hmac)
			OR EXISTS (SELECT 1 FROM recipient_delivery_cooldowns cd
			           WHERE cd.email_hmac = r.email_hmac
			             AND cd.last_smtp_accepted_at > :cooldownCutoff)
			OR NOT EXISTS (
			    SELECT 1 FROM smtp_accounts smtp
			    WHERE smtp.id = c.smtp_account_id AND smtp.enabled
			      AND smtp.last_test_status = 'SUCCEEDED'
			      AND smtp.last_tested_at IS NOT NULL
			      AND smtp.last_tested_at >= smtp.updated_at)
			OR NOT EXISTS (
			    SELECT 1 FROM mailbox_accounts mailbox
			    WHERE mailbox.id = c.mailbox_account_id AND mailbox.protocol = 'IMAP'
			      AND mailbox.enabled AND mailbox.last_test_status = 'SUCCEEDED'
			      AND mailbox.last_tested_at IS NOT NULL
			      AND mailbox.last_tested_at >= mailbox.updated_at)
			OR NOT EXISTS (
			    SELECT 1 FROM contacts contact
			    WHERE contact.id = r.contact_id AND contact.syntax_valid
			      AND NOT contact.example_address AND contact.suppression_status = 'ACTIVE'
			      AND contact.deleted_at IS NULL)
			OR r.personalization_status <> 'GENERATED'
			OR (r.confidence NOT IN ('HIGH', 'MEDIUM') AND NOT (%s))
			OR r.rendered_subject IS NULL OR btrim(r.rendered_subject) = ''
			OR r.rendered_html IS NULL OR btrim(r.rendered_html) = ''
			OR r.rendered_text IS NULL OR btrim(r.rendered_text) = ''
			OR (r.attempt_count = 0 AND (
			    position('{{unsubscribe_url}}' in r.rendered_html) = 0
			    OR position('{{unsubscribe_url}}' in r.rendered_text) = 0))
			OR NOT (%s)
			""".formatted(PAPER_CONTACT_POLICY, CURRENT_APPROVED_EVIDENCE);

	private final DatabaseClient database;
	private final TransactionalOperator transactions;
	private final CampaignDeliveryProperties properties;
	private final CampaignSafetyProperties safety;
	private final CampaignSafetyRuntimePolicy safetyPolicy;
	private final PreparationDiagnosticRepository diagnostics;
	// Scan position only; immutable checkpoint identity prevents stale concurrent pumps from rewinding it.
	private final java.util.concurrent.atomic.AtomicReference<DiscoveryCheckpoint> discoveryContinuation =
			new java.util.concurrent.atomic.AtomicReference<>(new DiscoveryCheckpoint(null));
	private final java.util.concurrent.atomic.AtomicReference<SafetyCheckpoint> safetyContinuation =
			new java.util.concurrent.atomic.AtomicReference<>(new SafetyCheckpoint(null));

	public CampaignDeliveryRepository(
			DatabaseClient database, TransactionalOperator transactions,
			CampaignDeliveryProperties properties, CampaignSafetyProperties safety
	) {
		this(database, transactions, properties, safety, null);
	}

	public CampaignDeliveryRepository(
			DatabaseClient database, TransactionalOperator transactions,
			CampaignDeliveryProperties properties, CampaignSafetyProperties safety,
			CampaignSafetyRuntimePolicy safetyPolicy
	) {
		this.database = database;
		this.transactions = transactions;
		this.properties = properties;
		this.safety = safety;
		this.safetyPolicy = safetyPolicy;
		this.diagnostics = new PreparationDiagnosticRepository(database);
	}

	/** Claims at most one tagged production or safety message and commits its lease before returning. */
	public Mono<DeliveryClaim> claimNext(Instant now) {
		if (!safety.enabled() || safetyPolicy == null) {
			return claimProductionTransaction(now);
		}
		return claimSafetyTransaction(now)
				.flatMap(decision -> {
					if (decision.claim() != null) return Mono.just(decision.claim());
					if (decision.handled()) return Mono.empty();
					return claimProductionTransaction(now);
				});
	}

	private Mono<DeliveryClaim> claimProductionTransaction(Instant now) {
		return Mono.defer(() -> {
			DiscoveryScan scan = new DiscoveryScan(discoveryContinuation.get());
			return claimProductionTransaction(now, scan).doOnSuccess(claim -> {
				if (!scan.suspended) discoveryContinuation.compareAndSet(scan.checkpoint, new DiscoveryCheckpoint(null));
			});
		});
	}

	private Mono<DeliveryClaim> claimProductionTransaction(Instant now, DiscoveryScan scan) {
		if (scan.excluded.size() >= 120) {
			scan.suspended = true;
			discoveryContinuation.compareAndSet(scan.checkpoint, new DiscoveryCheckpoint(scan.after));
			return Mono.empty();
		}
		return Mono.defer(() -> claimProduction(now, scan)).cast(DeliveryClaim.class).as(transactions::transactional)
				.retryWhen(reactor.util.retry.Retry.max(120).filter(CandidateChanged.class::isInstance))
				.onErrorResume(CandidateRejected.class, rejected -> {
					scan.excluded.add(rejected.position.recipient());
					scan.after = rejected.position;
					return claimProductionTransaction(now, scan);
				});
	}

	private static final class CandidateChanged extends RuntimeException { }
	private static final class CandidateRejected extends RuntimeException {
		private final DiscoveryPosition position;
		private CandidateRejected(Candidate candidate) {
			this.position = new DiscoveryPosition(candidate.campaignId(), candidate.nextAttemptAt(), candidate.id());
		}
	}
	private record DiscoveryPosition(UUID campaign, Instant due, UUID recipient) { }
	private record DiscoveryCheckpoint(DiscoveryPosition after) { }
	private static final class DiscoveryScan {
		private final DiscoveryCheckpoint checkpoint;
		private final java.util.Set<UUID> excluded = new java.util.HashSet<>();
		private DiscoveryPosition after;
		private boolean wrapped, suspended;
		private DiscoveryScan(DiscoveryCheckpoint checkpoint) { this.checkpoint = checkpoint; this.after = checkpoint.after(); }
	}

	/** Production-specific assertion used by package tests while {@link #claimNext} stays exhaustively tagged. */
	Mono<ProductionClaim> claimNextProduction(Instant now) {
		return claimNext(now).map(claim -> {
			if (claim instanceof ProductionClaim production) return production;
			throw new IllegalStateException("Expected a production delivery claim");
		});
	}

	int maximumAttempts() {
		return properties.maximumAttempts();
	}

	Duration firstRetryDelay() {
		return properties.firstRetryDelay();
	}

	Duration secondRetryDelay() {
		return properties.secondRetryDelay();
	}

	private Mono<ProductionClaim> claimProduction(Instant now, DiscoveryScan scan) {
		return selectCandidate(now, scan).switchIfEmpty(Mono.defer(() -> {
			if (scan.after == null || scan.wrapped) return Mono.empty();
			scan.wrapped = true;
			scan.after = null;
			return selectCandidate(now, scan);
		}))
				.flatMap(candidate -> new com.camel_hub.advertisement.campaign.migration.CampaignMigrationDeliveryFence(database)
						.lockCandidateAndVerify(candidate.campaignId(), candidate.id(), candidate.emailHmac(), candidate.attemptCount() == 0)
						.flatMap(allowed -> allowed ? Mono.just(true) : Mono.error(new CandidateRejected(candidate)))
						.flatMap(allowed -> database.sql("SELECT id FROM campaign_recipients WHERE id=:id AND status IN ('QUEUED','TEMPORARY_FAILURE') AND attempt_count=:attempt")
								.bind("id",candidate.id()).bind("attempt",candidate.attemptCount()).fetch().all().hasElements()
								.flatMap(current -> current ? lockAccount(candidate.smtpAccountId()) : Mono.error(new CandidateChanged())))
						.flatMap(account -> lockCooldown(candidate, now)
								.flatMap(cooldown -> verifyAfterLocks(candidate, cooldown, now)
										.flatMap(eligible -> new SmtpQuotaRepository(database).reservations(account, now)
												.collectList()
												.flatMap(reservations -> {
													Instant release = SmtpQuotaPolicy.capacityRelease(account, candidate.emailDomain(), reservations, now);
													return release == null
															? reserve(eligible, account, now)
															: defer(eligible.id(), release).then(Mono.empty());
												})))));
	}

	private Mono<SafetyClaimDecision> claimSafetyTransaction(Instant now) {
		return Mono.defer(() -> {
			SafetyScan scan = new SafetyScan(safetyContinuation.get());
			return claimSafetyTransaction(now, scan).doOnSuccess(decision -> {
				if (!scan.suspended) safetyContinuation.compareAndSet(scan.checkpoint, new SafetyCheckpoint(null));
			});
		});
	}

	private Mono<SafetyClaimDecision> claimSafetyTransaction(Instant now, SafetyScan scan) {
		if (scan.excluded.size() >= 120) {
			scan.suspended = true;
			safetyContinuation.compareAndSet(scan.checkpoint, new SafetyCheckpoint(scan.after));
			// No safety action in this bounded scan, not proof of exhaustive absence.
			// Independently fenced production may progress; it cannot reset this safety hint.
			return Mono.just(SafetyClaimDecision.noWork());
		}
		return Mono.defer(() -> claimSafety(now, scan)).as(transactions::transactional)
				.onErrorResume(SafetyCandidateRejected.class, rejected -> {
					// The candidate transaction has rolled back before opening the next root/lock scope.
					scan.excluded.add(rejected.position.message());
					scan.after = rejected.position;
					return claimSafetyTransaction(now, scan);
				});
	}

	// Exact PostgreSQL priority tuple: RUNNING rank, run creation/id, message due/id.
	// This is only a process-local scan hint, never eligibility. Restart begins at the head;
	// one wrap rechecks earlier work. Checkpoint identity CAS prevents a stale concurrent
	// subscription from overwriting publication/reset; success or exhausted scan resets it.
	private record SafetyPosition(int rank, Instant created, UUID run, Instant due, UUID message) { }
	private record SafetyCheckpoint(SafetyPosition after) { }
	private static final class SafetyScan {
		private final SafetyCheckpoint checkpoint;
		private final java.util.Set<UUID> excluded = new java.util.HashSet<>();
		private SafetyPosition after;
		private boolean wrapped, suspended;
		private SafetyScan(SafetyCheckpoint checkpoint) { this.checkpoint = checkpoint; this.after = checkpoint.after(); }
	}
	private static final class SafetyCandidateRejected extends RuntimeException {
		private final SafetyPosition position;
		private SafetyCandidateRejected(SafetyPosition position) { this.position = position; }
	}

	private Mono<SafetyClaimDecision> claimSafety(Instant now, SafetyScan scan) {
		return discoverSafety(now, scan).switchIfEmpty(Mono.defer(() -> {
			if (scan.after == null || scan.wrapped) return Mono.empty();
			scan.wrapped = true;
			scan.after = null;
			return discoverSafety(now, scan);
		})).flatMap(discovered -> {
			var fence=new CampaignMigrationDeliveryFence(database);
			return fence.lockSources(List.of(discovered.source())).flatMap(receipt -> lockSafetyRun(discovered.run(),now)
				.filter(run -> run.campaignId().equals(discovered.source().campaignId()))
				.flatMap(run -> lockSafetyMessage(run.id(),discovered.message(),now)
					.filter(message -> message.campaignRecipientId().equals(discovered.source().recipientId()))
					.flatMap(message -> database.sql("SELECT id FROM campaign_safety_attempts WHERE safety_message_id=:id ORDER BY id FOR UPDATE")
						.bind("id",message.id()).fetch().all().then(fence.verifyLocked(receipt,true,List.of(run.smtpAccountId())))
						.filter(Boolean::booleanValue).flatMap(allowed -> claimSafety(run,message,now)))))
				.switchIfEmpty(Mono.error(new SafetyCandidateRejected(discovered.position())));
		})
				.defaultIfEmpty(SafetyClaimDecision.noWork());
	}

	private record SafetyDiscovery(UUID run, UUID message, CampaignMigrationDeliveryFence.Source source, SafetyPosition position) { }

	private Mono<SafetyDiscovery> discoverSafety(Instant now, SafetyScan scan) {
		SafetyPosition after = scan.after;
		UUID zero = new UUID(0,0);
		return database.sql("""
			SELECT sr.id AS run,m.id AS message,r.id AS recipient,r.campaign_id,encode(r.email_hmac,'hex') AS hmac,
			 CASE WHEN sr.status='RUNNING' THEN 0 ELSE 1 END AS rank,sr.created_at,m.next_attempt_at
			FROM campaign_safety_runs sr JOIN campaign_safety_messages m ON m.run_id=sr.id
			JOIN campaign_recipients r ON r.id=m.campaign_recipient_id AND r.campaign_id=sr.campaign_id
			WHERE sr.status IN ('QUEUED','RUNNING') AND m.status IN ('QUEUED','TEMPORARY_FAILURE')
			 AND m.next_attempt_at<=:now AND m.attempt_count<:maximumAttempts
			 AND NOT (m.id=ANY(:excluded))
			 AND (NOT :hasAfter OR (CASE WHEN sr.status='RUNNING' THEN 0 ELSE 1 END,sr.created_at,sr.id,m.next_attempt_at,m.id)
			   > (:rank,:created,:run,:due,:message))
			ORDER BY CASE WHEN sr.status='RUNNING' THEN 0 ELSE 1 END,sr.created_at,sr.id,m.next_attempt_at,m.id LIMIT 1
			""").bind("now",now).bind("maximumAttempts",properties.maximumAttempts())
			.bind("excluded",scan.excluded.toArray(UUID[]::new)).bind("hasAfter",after!=null)
			.bind("rank",after==null?0:after.rank()).bind("created",after==null?Instant.EPOCH:after.created())
			.bind("run",after==null?zero:after.run()).bind("due",after==null?Instant.EPOCH:after.due())
			.bind("message",after==null?zero:after.message())
			.map((r,m)->new SafetyDiscovery(r.get("run",UUID.class),r.get("message",UUID.class),
				new CampaignMigrationDeliveryFence.Source(r.get("campaign_id",UUID.class),r.get("recipient",UUID.class),r.get("hmac",String.class)),
				new SafetyPosition(r.get("rank",Integer.class),r.get("created_at",Instant.class),r.get("run",UUID.class),
					r.get("next_attempt_at",Instant.class),r.get("message",UUID.class)))).one();
	}

	private Mono<SafetyClaimDecision> claimSafety(SafetyRunCandidate run, SafetyMessageCandidate message, Instant now) {
		CampaignSafetyRuntimePolicy.Destination destination;
		try {
			destination = safetyPolicy.requireMatching(run.destinationHmac());
		}
		catch (RuntimeException rejected) {
			return terminalizeSafetyRun(run.id(), now).thenReturn(SafetyClaimDecision.handledWork());
		}
		if (!destination.masked().equals(run.destinationMasked())
				|| !destination.domain().equals(run.destinationDomain())) {
			return terminalizeSafetyRun(run.id(), now).thenReturn(SafetyClaimDecision.handledWork());
		}
		return lockAccount(run.smtpAccountId())
				.flatMap(account -> claimSafety(run, message, account, destination, now));
	}

	private Mono<SafetyClaimDecision> claimSafety(
			SafetyRunCandidate run, SafetyMessageCandidate message,
			SmtpRepository.SmtpAccountRecord account,
			CampaignSafetyRuntimePolicy.Destination destination, Instant now
	) {
		if (!smtpHealthy(account)) {
			return failSafetyBeforeSmtp(run.id(), message.id(), now)
					.thenReturn(SafetyClaimDecision.handledWork());
		}
		return new SmtpQuotaRepository(database).reservations(account, now).collectList()
				.flatMap(reservations -> {
					Instant release = SmtpQuotaPolicy.capacityRelease(account, destination.domain(), reservations, now);
					if (release != null) {
						return deferSafety(message.id(), release)
								.thenReturn(SafetyClaimDecision.handledWork());
					}
					return reserveSafety(run, message, account, destination, now)
							.map(SafetyClaimDecision::claimed);
				});
	}

	private Mono<SafetyRunCandidate> lockSafetyRun(UUID runId, Instant now) {
		return database.sql("""
				SELECT sr.id, sr.campaign_id, sr.smtp_account_id, sr.destination_hmac,
				       sr.destination_masked,
				       lower(split_part(sr.destination_masked, '@', 2)) AS destination_domain,
				       sr.from_name_snapshot, sr.from_email_snapshot, sr.reply_to_snapshot,
				       sr.tracking_opens_enabled, sr.tracking_clicks_enabled
				FROM campaign_safety_runs sr
				WHERE sr.id=:run AND sr.status IN ('QUEUED','RUNNING')
				  AND EXISTS (
				      SELECT 1 FROM campaign_safety_messages m
				      WHERE m.run_id = sr.id AND m.status IN ('QUEUED','TEMPORARY_FAILURE')
				        AND m.next_attempt_at <= :now AND m.attempt_count < :maximumAttempts)
				ORDER BY CASE WHEN sr.status = 'RUNNING' THEN 0 ELSE 1 END, sr.created_at, sr.id
				FOR UPDATE OF sr SKIP LOCKED
				LIMIT 1
				""").bind("run",runId).bind("now", now).bind("maximumAttempts", properties.maximumAttempts())
				.map((row, metadata) -> new SafetyRunCandidate(
						row.get("id", UUID.class), row.get("campaign_id", UUID.class),
						row.get("smtp_account_id", UUID.class), copy(row.get("destination_hmac", byte[].class)),
						row.get("destination_masked", String.class), row.get("destination_domain", String.class),
						row.get("from_name_snapshot", String.class), row.get("from_email_snapshot", String.class),
						row.get("reply_to_snapshot", String.class),
						Boolean.TRUE.equals(row.get("tracking_opens_enabled", Boolean.class)),
						Boolean.TRUE.equals(row.get("tracking_clicks_enabled", Boolean.class)))).one();
	}

	private Mono<SafetyMessageCandidate> lockSafetyMessage(UUID runId, UUID messageId, Instant now) {
		return database.sql("""
				SELECT id, campaign_recipient_id, attempt_count, rfc_message_id,
				       rendered_subject, rendered_html, rendered_text
				FROM campaign_safety_messages
				WHERE id=:message AND run_id = :run AND status IN ('QUEUED','TEMPORARY_FAILURE')
				  AND smtp_account_id=(SELECT smtp_account_id FROM campaign_safety_runs WHERE id=:run)
				  AND next_attempt_at <= :now AND attempt_count < :maximumAttempts
				ORDER BY next_attempt_at, id
				FOR UPDATE SKIP LOCKED
				LIMIT 1
				""").bind("message",messageId).bind("run", runId).bind("now", now).bind("maximumAttempts", properties.maximumAttempts())
				.map((row, metadata) -> new SafetyMessageCandidate(
						row.get("id", UUID.class), row.get("campaign_recipient_id", UUID.class),
						requiredInt(row, "attempt_count"), row.get("rfc_message_id", String.class),
						row.get("rendered_subject", String.class), row.get("rendered_html", String.class),
						row.get("rendered_text", String.class))).one();
	}

	private boolean smtpHealthy(SmtpRepository.SmtpAccountRecord account) {
		return account.enabled() && "SUCCEEDED".equals(account.lastTestStatus())
				&& account.lastTestedAt() != null && account.updatedAt() != null
				&& !account.lastTestedAt().isBefore(account.updatedAt());
	}

	private Mono<SafetyClaim> reserveSafety(
			SafetyRunCandidate run, SafetyMessageCandidate message,
			SmtpRepository.SmtpAccountRecord account,
			CampaignSafetyRuntimePolicy.Destination destination, Instant now
	) {
		UUID attemptId = UUID.randomUUID();
		int attemptNumber = message.attemptCount() + 1;
		byte[] lease = new byte[32];
		RANDOM.nextBytes(lease);
		byte[] leaseHash = sha256(lease);
		String idempotency = "safety:" + message.id() + ":" + attemptNumber;
		String messageId = message.rfcMessageId() == null
				? "<safety-" + message.id() + "@delivery.camel-arxiv.invalid>" : message.rfcMessageId();
		Instant expires = now.plus(properties.leaseDuration());
		SafetyClaim claim = new SafetyClaim(
				message.id(), run.id(), message.campaignRecipientId(), attemptId, attemptNumber,
				idempotency, messageId, "campaign-safety-" + message.id(), lease,
				run.destinationHmac(), run.destinationMasked(), destination.domain(), account,
				run.fromName(), run.fromEmail(), run.replyTo(), run.trackingOpens(), run.trackingClicks(),
				message.subject(), message.html(), message.text());
		return database.sql("""
				UPDATE campaign_safety_messages
				SET status = 'CONNECTING', attempt_count = :number,
				    delivery_lease_hash = :lease, delivery_lease_expires_at = :expires,
				    rfc_message_id = :messageId
				WHERE id = :message AND run_id = :run
				  AND status IN ('QUEUED','TEMPORARY_FAILURE')
				  AND attempt_count = :oldAttemptCount AND next_attempt_at <= :now
				  AND EXISTS (SELECT 1 FROM campaign_safety_runs sr WHERE sr.id = :run
				              AND sr.status IN ('QUEUED','RUNNING'))
				""").bind("number", attemptNumber).bind("lease", leaseHash).bind("expires", expires)
				.bind("messageId", messageId).bind("message", message.id()).bind("run", run.id())
				.bind("oldAttemptCount", message.attemptCount()).bind("now", now).fetch().rowsUpdated()
				.flatMap(updated -> updated.longValue() != 1L ? Mono.empty()
						: database.sql("""
								UPDATE campaign_safety_runs
								SET status = 'RUNNING', started_at = COALESCE(started_at, :now),
								    lock_version = lock_version + CASE WHEN status = 'QUEUED' THEN 1 ELSE 0 END
								WHERE id = :run AND status IN ('QUEUED','RUNNING')
								""").bind("now", now).bind("run", run.id()).fetch().rowsUpdated()
							.then(database.sql("""
									INSERT INTO campaign_safety_attempts (
									    id, safety_message_id, attempt_number, idempotency_key, status,
									    transport_stage, retryable, rfc_message_id, started_at
									) VALUES (:id, :message, :number, :key, 'CONNECTING',
									          'CONNECT', false, :messageId, :now)
									""").bind("id", attemptId).bind("message", message.id())
									.bind("number", attemptNumber).bind("key", idempotency)
									.bind("messageId", messageId).bind("now", now).fetch().rowsUpdated())
							.thenReturn(claim));
	}

	private Mono<Integer> deferSafety(UUID messageId, Instant release) {
		return database.sql("""
				UPDATE campaign_safety_messages SET next_attempt_at = :release
				WHERE id = :message AND status IN ('QUEUED','TEMPORARY_FAILURE')
				""").bind("release", release).bind("message", messageId)
				.fetch().rowsUpdated().map(Number::intValue);
	}

	private Mono<Integer> failSafetyBeforeSmtp(UUID runId, UUID messageId, Instant now) {
		return database.sql("""
				UPDATE campaign_safety_messages
				SET status = 'PERMANENT_FAILURE', delivery_lease_hash = NULL,
				    delivery_lease_expires_at = NULL, next_attempt_at = :now
				WHERE id = :message AND run_id = :run AND status IN ('QUEUED','TEMPORARY_FAILURE')
				""").bind("now", now).bind("message", messageId).bind("run", runId)
				.fetch().rowsUpdated().map(Number::intValue)
				.flatMap(updated -> aggregateSafetyRun(runId, now).thenReturn(updated));
	}

	private Mono<Integer> terminalizeSafetyRun(UUID runId, Instant now) {
		return database.sql("""
				UPDATE campaign_safety_messages
				SET status = 'PERMANENT_FAILURE', delivery_lease_hash = NULL,
				    delivery_lease_expires_at = NULL, next_attempt_at = :now
				WHERE run_id = :run AND status IN ('QUEUED','TEMPORARY_FAILURE')
				""").bind("now", now).bind("run", runId).fetch().rowsUpdated().map(Number::intValue)
				.flatMap(updated -> aggregateSafetyRun(runId, now).thenReturn(updated));
	}

	private Mono<Integer> aggregateSafetyRun(UUID runId, Instant now) {
		return database.sql("""
				WITH totals AS (
				    SELECT count(*)::int AS total,
				           count(*) FILTER (WHERE status = 'SMTP_ACCEPTED')::int AS accepted,
				           count(*) FILTER (WHERE status IN ('QUEUED','CONNECTING','TEMPORARY_FAILURE'))::int AS active
				    FROM campaign_safety_messages WHERE run_id = :run
				), resolved AS (
				    SELECT CASE
				        WHEN active > 0 THEN NULL
				        WHEN total > 0 AND accepted = total THEN 'COMPLETED'
				        WHEN accepted = 0 THEN 'FAILED'
				        ELSE 'PARTIALLY_FAILED'
				    END AS next_status FROM totals
				)
				UPDATE campaign_safety_runs sr
				SET status = resolved.next_status, completed_at = :now, lock_version = lock_version + 1
				FROM resolved
				WHERE sr.id = :run AND sr.status IN ('QUEUED','RUNNING') AND resolved.next_status IS NOT NULL
				""").bind("run", runId).bind("now", now).fetch().rowsUpdated().map(Number::intValue);
	}

	/**
	 * Terminates queued rows whose approved eligibility no longer holds. Every
	 * individual claim also repeats the same critical checks before reservation.
	 */
	public Mono<Integer> reconcileUndeliverable(Instant now) {
		return reconcileUndeliverableInternal(now).as(transactions::transactional);
	}

	private Mono<Integer> reconcileUndeliverableInternal(Instant now) {
		return database.sql("""
				WITH locked_campaign AS MATERIALIZED (
				    SELECT c.id, c.smtp_account_id, c.mailbox_account_id
				    FROM campaigns c
				    WHERE c.status = 'RUNNING'
				      AND NOT EXISTS (SELECT 1 FROM campaign_safety_runs sr
				                      WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED', 'RUNNING'))
				      AND EXISTS (
				          SELECT 1 FROM campaign_recipients r
				          WHERE r.campaign_id = c.id
				            AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				            AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				            AND (%1$s))
				    ORDER BY c.id
				    FOR UPDATE OF c SKIP LOCKED
				    LIMIT 1
				), locked_recipients AS MATERIALIZED (
				    SELECT r.id
				    FROM locked_campaign c
				    JOIN campaign_recipients r ON r.campaign_id = c.id
				    WHERE r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				      AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				      AND (%1$s)
				    ORDER BY r.id
				    FOR UPDATE OF r SKIP LOCKED
				    LIMIT :batchSize
				)
				UPDATE campaign_recipients r
				SET status = CASE
				        WHEN EXISTS (SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac = r.email_hmac)
				            THEN 'UNSUBSCRIBED'
				        ELSE 'SUPPRESSED'
				    END,
				    exclusion_reason = CASE
				        WHEN EXISTS (SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac = r.email_hmac)
				            THEN 'UNSUBSCRIBED'
				        WHEN EXISTS (SELECT 1 FROM suppression_entries s
				                     WHERE s.email_hmac = r.email_hmac
				                       AND (s.expires_at IS NULL OR s.expires_at > :now))
				            THEN 'GLOBAL_SUPPRESSION'
				        WHEN EXISTS (SELECT 1 FROM campaign_exclusions x
				                     WHERE x.campaign_id = r.campaign_id AND x.email_hmac = r.email_hmac)
				            THEN 'CAMPAIGN_EXCLUDED'
				        WHEN EXISTS (SELECT 1 FROM recipient_delivery_cooldowns cd
				                     WHERE cd.email_hmac = r.email_hmac
				                       AND cd.last_smtp_accepted_at > :cooldownCutoff)
				            THEN 'COOLDOWN_ACTIVE'
				        ELSE 'EVIDENCE_INVALID'
				    END,
				    final_failure_at = :now,
				    delivery_lease_hash = NULL,
				    delivery_lease_expires_at = NULL
				FROM locked_campaign c, locked_recipients target
				WHERE c.id = r.campaign_id AND target.id = r.id
				""".formatted(UNDELIVERABLE_PREDICATE))
				.bind("now", now)
				.bind("cooldownCutoff", now.minus(properties.productionCooldown()))
				.bind("batchSize", properties.batchSize())
				.fetch().rowsUpdated().map(Number::intValue);
	}

	private Mono<Candidate> selectCandidate(Instant now, DiscoveryScan scan) {
		return bindDiscoveryScan(database.sql("""
				SELECT c.id
				FROM campaigns c
				WHERE c.status = 'RUNNING'
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipients held WHERE held.campaign_id=c.id AND held.partner_invite_hold_reason IS NOT NULL)
				  AND EXISTS (SELECT 1 FROM campaign_recipients r
				              WHERE r.campaign_id = c.id
				                AND r.id <> ALL(:excluded)
				                AND (NOT :hasAfter OR (c.id,r.next_attempt_at,r.id) > (:afterCampaign,:afterDue,:afterRecipient))
				                AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				                AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				                AND r.next_attempt_at <= :now
				                AND r.attempt_count < :maximumAttempts
				                AND NOT EXISTS (SELECT 1 FROM campaign_recipients guarded
				                                WHERE guarded.email_hmac = r.email_hmac
				                                  AND guarded.id <> r.id
				                                  AND guarded.status IN ('CONNECTING', 'OUTCOME_UNKNOWN')))
				  AND NOT EXISTS (SELECT 1 FROM campaign_safety_runs sr
				                  WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED', 'RUNNING'))
				ORDER BY c.id
				LIMIT 1
				"""), scan).bind("now", now).bind("maximumAttempts", properties.maximumAttempts())
				.map((row, metadata) -> row.get("id", UUID.class)).one()
				.flatMap(campaignId -> bindDiscoveryScan(database.sql("""
				SELECT r.id, r.campaign_id, r.contact_id, r.paper_id, r.author_id,
				       r.email_ciphertext, r.email_nonce, r.email_hmac, r.email_domain,
				       r.attempt_count, r.next_attempt_at, r.rfc_message_id, r.rendered_subject,
				       r.rendered_html, r.rendered_text,
				       c.smtp_account_id, c.template_version_id, c.from_name, c.from_email,
				       c.reply_to, c.tracking_opens_enabled, c.tracking_clicks_enabled,
				       c.unsubscribe_enabled
				FROM campaign_recipients r
				JOIN campaigns c ON c.id = r.campaign_id
				WHERE c.id = :campaignId AND c.status = 'RUNNING'
				  AND r.id <> ALL(:excluded)
				  AND (NOT :hasAfter OR (c.id,r.next_attempt_at,r.id) > (:afterCampaign,:afterDue,:afterRecipient))
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipients held WHERE held.campaign_id=c.id AND held.partner_invite_hold_reason IS NOT NULL)
				  AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				  AND r.next_attempt_at <= :now
				  AND r.attempt_count < :maximumAttempts
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipients guarded
				                  WHERE guarded.email_hmac = r.email_hmac
				                    AND guarded.id <> r.id
				                    AND guarded.status IN ('CONNECTING', 'OUTCOME_UNKNOWN'))
				  AND NOT EXISTS (SELECT 1 FROM campaign_safety_runs sr
				                  WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED', 'RUNNING'))
				ORDER BY r.next_attempt_at, r.id
				LIMIT 1
				"""), scan).bind("campaignId", campaignId).bind("now", now)
						.bind("maximumAttempts", properties.maximumAttempts())
						.map((row, metadata) -> candidate(row)).one());
	}

	private DatabaseClient.GenericExecuteSpec bindDiscoveryScan(DatabaseClient.GenericExecuteSpec statement, DiscoveryScan scan) {
		DiscoveryPosition after = scan.after;
		return statement.bind("excluded", scan.excluded.toArray(UUID[]::new)).bind("hasAfter", after != null)
				.bind("afterCampaign", after == null ? new UUID(0,0) : after.campaign())
				.bind("afterDue", after == null ? Instant.EPOCH : after.due())
				.bind("afterRecipient", after == null ? new UUID(0,0) : after.recipient());
	}

	private Mono<SmtpRepository.SmtpAccountRecord> lockAccount(UUID id) {
		return database.sql("""
				SELECT id, name, host, port, tls_mode, username, password_ciphertext, password_nonce,
				       from_email, default_from_name, reply_to, per_minute_limit, per_hour_limit,
				       per_day_limit, per_month_limit, per_domain_hour_limit, enabled, last_tested_at,
				       last_test_status, last_test_error, lock_version, created_by, created_at, updated_at
				FROM smtp_accounts WHERE id = :id FOR UPDATE
				""").bind("id", id).map((row, metadata) -> account(row)).one();
	}

	private Mono<Cooldown> lockCooldown(Candidate candidate, Instant now) {
		return database.sql("""
				INSERT INTO recipient_delivery_cooldowns (email_hmac, last_smtp_accepted_at, updated_at)
				VALUES (:hmac, :sentinel, :now)
				ON CONFLICT (email_hmac) DO NOTHING
				""").bind("hmac", candidate.emailHmac()).bind("sentinel", COOLDOWN_SENTINEL)
				.bind("now", now).fetch().rowsUpdated()
				.then(database.sql("""
						SELECT last_smtp_accepted_at FROM recipient_delivery_cooldowns
						WHERE email_hmac = :hmac FOR UPDATE
						""").bind("hmac", candidate.emailHmac())
						.map((row, metadata) -> new Cooldown(row.get(0, Instant.class))).one());
	}

	private Mono<Candidate> verifyAfterLocks(Candidate candidate, Cooldown cooldown, Instant now) {
		if (cooldown.lastAccepted().isAfter(now.minus(properties.productionCooldown()))) {
			return suppressForCooldown(candidate.id(), now).then(Mono.empty());
		}
		return currentEligibility(candidate.id(), now).flatMap(eligibility -> {
			if (eligibility.reason() != null) {
				if (temporarilyIneligible(eligibility.reason())) return Mono.empty();
				return terminateRecipient(candidate.id(), eligibility.reason(), now).then(Mono.empty());
			}
			return database.sql("""
				SELECT count(*)::int AS total
				FROM campaign_recipients
				WHERE email_hmac = :hmac AND status = 'CONNECTING' AND id <> :id
				""").bind("hmac", candidate.emailHmac()).bind("id", candidate.id())
				.map((row, metadata) -> requiredInt(row, "total")).one()
				.flatMap(total -> total == 0 ? Mono.just(candidate) : Mono.empty());
		});
	}

	private Mono<Eligibility> currentEligibility(UUID recipientId, Instant now) {
		return database.sql("""
				SELECT CASE
				    WHEN c.status <> 'RUNNING' THEN 'CAMPAIGN_NOT_RUNNING'
				    WHEN EXISTS (SELECT 1 FROM campaign_safety_runs sr
				                 WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED', 'RUNNING'))
				        THEN 'TEMPORARY_SAFETY_RUN'
				    WHEN EXISTS (SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac = r.email_hmac)
				        THEN 'UNSUBSCRIBED'
				    WHEN EXISTS (SELECT 1 FROM suppression_entries s
				                 WHERE s.email_hmac = r.email_hmac
				                   AND (s.expires_at IS NULL OR s.expires_at > :now))
				        THEN 'GLOBAL_SUPPRESSION'
				    WHEN EXISTS (SELECT 1 FROM campaign_exclusions x
				                 WHERE x.campaign_id = r.campaign_id AND x.email_hmac = r.email_hmac)
				        THEN 'CAMPAIGN_EXCLUDED'
				    WHEN EXISTS (SELECT 1 FROM campaign_recipients unresolved
				                 WHERE unresolved.email_hmac = r.email_hmac
				                   AND unresolved.id <> r.id
				                   AND unresolved.status = 'OUTCOME_UNKNOWN')
				        THEN 'TEMPORARY_UNKNOWN_OUTCOME'
				    WHEN NOT smtp.enabled OR smtp.last_test_status IS DISTINCT FROM 'SUCCEEDED'
				         OR smtp.last_tested_at IS NULL OR smtp.last_tested_at < smtp.updated_at
				        THEN 'DELIVERY_CONFIGURATION_INVALID'
				    WHEN mailbox.id IS NULL OR mailbox.protocol <> 'IMAP' OR NOT mailbox.enabled
				         OR mailbox.last_test_status IS DISTINCT FROM 'SUCCEEDED'
				         OR mailbox.last_tested_at IS NULL OR mailbox.last_tested_at < mailbox.updated_at
				        THEN 'DELIVERY_CONFIGURATION_INVALID'
				    WHEN contact.id IS NULL OR contact.deleted_at IS NOT NULL
				         OR NOT contact.syntax_valid OR contact.example_address
				         OR contact.suppression_status <> 'ACTIVE' THEN 'CONTACT_INVALID'
				    WHEN r.confidence NOT IN ('HIGH', 'MEDIUM') AND NOT (%s) THEN 'EVIDENCE_INVALID'
				    WHEN r.personalization_status <> 'GENERATED'
				         OR r.rendered_subject IS NULL OR btrim(r.rendered_subject) = ''
				         OR r.rendered_html IS NULL OR btrim(r.rendered_html) = ''
				         OR r.rendered_text IS NULL OR btrim(r.rendered_text) = ''
				         OR (r.attempt_count = 0 AND (
				             position('{{unsubscribe_url}}' in r.rendered_html) = 0
				             OR position('{{unsubscribe_url}}' in r.rendered_text) = 0)) THEN 'CONTENT_INVALID'
				    WHEN NOT (%s) THEN 'EVIDENCE_INVALID'
				    ELSE NULL END AS reason
				FROM campaign_recipients r
				JOIN campaigns c ON c.id = r.campaign_id
				JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				LEFT JOIN mailbox_accounts mailbox ON mailbox.id = c.mailbox_account_id
				LEFT JOIN contacts contact ON contact.id = r.contact_id
				WHERE r.id = :id AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				""".formatted(PAPER_CONTACT_POLICY, CURRENT_APPROVED_EVIDENCE)).bind("now", now).bind("id", recipientId)
				.map((row, metadata) -> new Eligibility(row.get("reason", String.class))).one();
	}

	private Mono<Integer> terminateRecipient(UUID recipientId, String reason, Instant now) {
		String status = "UNSUBSCRIBED".equals(reason) ? "UNSUBSCRIBED" : "SUPPRESSED";
		return database.sql("""
				UPDATE campaign_recipients
				SET status = :status, exclusion_reason = :reason, final_failure_at = :now,
				    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL
				WHERE id = :id AND status IN ('QUEUED', 'TEMPORARY_FAILURE')
				""").bind("status", status).bind("reason", reason).bind("now", now)
				.bind("id", recipientId).fetch().rowsUpdated().map(Number::intValue);
	}

	private Mono<ProductionClaim> reserve(
			Candidate candidate, SmtpRepository.SmtpAccountRecord account, Instant now
	) {
		UUID attemptId = UUID.randomUUID();
		int attemptNumber = candidate.attemptCount() + 1;
		byte[] lease = new byte[32];
		RANDOM.nextBytes(lease);
		byte[] leaseHash = sha256(lease);
		String idempotency = "delivery:" + candidate.id() + ":" + attemptNumber;
		String messageId = candidate.messageId() == null
				? "<" + candidate.id() + "@delivery.camel-arxiv.invalid>" : candidate.messageId();
		String correlation = "delivery-" + candidate.id();
		Instant expires = now.plus(properties.leaseDuration());
		Mono<ProductionClaim> claim = Mono.just(new ProductionClaim(
				candidate.id(), candidate.campaignId(), attemptId, attemptNumber,
				idempotency, messageId, correlation, lease,
				candidate.emailCiphertext(), candidate.emailNonce(), candidate.emailHmac(),
				candidate.emailDomain(), account, candidate.templateVersionId(),
				candidate.fromName(), candidate.fromEmail(), candidate.replyTo(),
				candidate.trackingOpens(), candidate.trackingClicks(), candidate.unsubscribeEnabled(),
				candidate.renderedSubject(), candidate.renderedHtml(), candidate.renderedText()));
		return database.sql("""
				UPDATE campaign_recipients r
				SET status = 'CONNECTING', attempt_count = :number,
				    first_attempt_at = COALESCE(first_attempt_at, :now),
				    delivery_lease_hash = :leaseHash,
				    delivery_lease_expires_at = :expires,
				    rfc_message_id = :messageId
				WHERE r.id = :id
				  AND r.partner_invite_hold_reason IS NULL
				  AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				  AND r.next_attempt_at <= :now AND r.attempt_count = :oldAttemptCount
				  AND (r.confidence IN ('HIGH', 'MEDIUM') OR (%s))
				  AND (r.attempt_count > 0 OR (
				      position('{{unsubscribe_url}}' in r.rendered_html) > 0
				      AND position('{{unsubscribe_url}}' in r.rendered_text) > 0))
				  AND NOT EXISTS (SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac = r.email_hmac)
				  AND NOT EXISTS (SELECT 1 FROM suppression_entries s
				                  WHERE s.email_hmac = r.email_hmac
				                    AND (s.expires_at IS NULL OR s.expires_at > :now))
				  AND NOT EXISTS (SELECT 1 FROM campaign_exclusions x
				                  WHERE x.campaign_id = r.campaign_id AND x.email_hmac = r.email_hmac)
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipients unresolved
				                  WHERE unresolved.email_hmac = r.email_hmac
				                    AND unresolved.id <> r.id
				                    AND unresolved.status = 'OUTCOME_UNKNOWN')
				  AND EXISTS (SELECT 1 FROM contacts contact
				              WHERE contact.id = r.contact_id AND contact.syntax_valid
				                AND NOT contact.example_address AND contact.suppression_status = 'ACTIVE'
				                AND contact.deleted_at IS NULL)
				  AND EXISTS (
				      SELECT 1 FROM campaigns c
				      JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				      JOIN mailbox_accounts mailbox ON mailbox.id = c.mailbox_account_id
				      WHERE c.id = r.campaign_id AND c.status = 'RUNNING'
				        AND smtp.id = :smtp AND smtp.enabled AND smtp.last_test_status = 'SUCCEEDED'
				        AND smtp.last_tested_at IS NOT NULL AND smtp.last_tested_at >= smtp.updated_at
				        AND mailbox.protocol = 'IMAP' AND mailbox.enabled
				        AND mailbox.last_test_status = 'SUCCEEDED' AND mailbox.last_tested_at IS NOT NULL
				        AND mailbox.last_tested_at >= mailbox.updated_at
				        AND NOT EXISTS (SELECT 1 FROM campaign_safety_runs sr
				                        WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED', 'RUNNING')))
				  AND (%s)
				""".formatted(PAPER_CONTACT_POLICY, CURRENT_APPROVED_EVIDENCE)).bind("number", attemptNumber).bind("oldAttemptCount", candidate.attemptCount())
				.bind("now", now).bind("leaseHash", leaseHash).bind("expires", expires)
				.bind("messageId", messageId).bind("id", candidate.id()).bind("smtp", account.id())
				.fetch().rowsUpdated()
				.flatMap(updated -> {
					if (updated.longValue() != 1L) {
						return currentEligibility(candidate.id(), now)
								.flatMap(eligibility -> eligibility.reason() == null
										|| temporarilyIneligible(eligibility.reason())
										? Mono.empty()
										: terminateRecipient(candidate.id(), eligibility.reason(), now).then(Mono.empty()));
					}
					return database.sql("""
							INSERT INTO delivery_attempts (
							    id, campaign_recipient_id, smtp_account_id, attempt_number,
							    idempotency_key, status, transport_stage, retryable,
							    rfc_message_id, started_at, partner_preparation_pending
							) VALUES (:attempt, :recipient, :smtp, :number, :key, 'CONNECTING',
							          'CONNECT', false, :messageId, :now, :partnerPending)
							""").bind("attempt", attemptId).bind("recipient", candidate.id())
							.bind("smtp", account.id()).bind("number", attemptNumber)
							.bind("key", idempotency).bind("messageId", messageId).bind("now", now)
							.bind("partnerPending", com.camel_hub.advertisement.campaign.partner.PartnerContentPolicy.optedIn(candidate.renderedHtml(), candidate.renderedText()))
							.fetch().rowsUpdated().then(claim);
				});
	}

	private Mono<Integer> defer(UUID recipientId, Instant release) {
		return database.sql("UPDATE campaign_recipients SET next_attempt_at = :release WHERE id = :id")
				.bind("release", release).bind("id", recipientId)
				.fetch().rowsUpdated().map(Number::intValue);
	}

	private Mono<Integer> suppressForCooldown(UUID recipientId, Instant now) {
		return database.sql("""
				UPDATE campaign_recipients SET status = 'SUPPRESSED', exclusion_reason = 'COOLDOWN_ACTIVE',
				    final_failure_at = :now WHERE id = :id AND status IN ('QUEUED', 'TEMPORARY_FAILURE')
				""").bind("now", now).bind("id", recipientId).fetch().rowsUpdated().map(Number::intValue);
	}

	public Mono<Boolean> completeAccepted(
			UUID recipientId, UUID attemptId, byte[] lease,
			SmtpTransport.SmtpOutcome outcome, Instant now
	) {
		byte[] digest = sha256(lease);
		Mono<Boolean> completion = database.sql("""
				UPDATE campaign_recipients
				SET status = 'SMTP_ACCEPTED', smtp_accepted_at = :now,
				    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL,
				    next_attempt_at = :now
				WHERE id = :recipient AND status = 'CONNECTING'
				  AND delivery_lease_hash = :lease
				  AND EXISTS (SELECT 1 FROM delivery_attempts a
				              WHERE a.id = :attempt AND a.campaign_recipient_id = :recipient
				                AND a.status = 'CONNECTING')
				RETURNING email_hmac
				""").bind("now", now).bind("recipient", recipientId).bind("lease", digest)
				.bind("attempt", attemptId)
				.map((row, metadata) -> row.get("email_hmac", byte[].class)).one()
				.flatMap(emailHmac -> updateAcceptedAttempt(attemptId, outcome, now)
						.then(database.sql("""
								UPDATE recipient_delivery_cooldowns
								SET last_smtp_accepted_at = :now, updated_at = :now
								WHERE email_hmac = :hmac
								""").bind("now", now).bind("hmac", emailHmac).fetch().rowsUpdated())
						.thenReturn(true))
				.defaultIfEmpty(false);
		return completion.as(transactions::transactional);
	}

	private Mono<Long> updateAcceptedAttempt(
			UUID attemptId, SmtpTransport.SmtpOutcome outcome, Instant now
	) {
		DatabaseClient.GenericExecuteSpec update = database.sql("""
				UPDATE delivery_attempts
				SET status = 'SMTP_ACCEPTED', transport_stage = :stage,
				    smtp_response_code = :code, smtp_response_summary = :summary,
				    retryable = false, completed_at = :now
				WHERE id = :attempt AND status = 'CONNECTING'
				""").bind("stage", outcome.stage().name())
				.bind("code", outcome.responseCode() == null ? 250 : outcome.responseCode())
				.bind("now", now).bind("attempt", attemptId);
		update = bindNullable(update, "summary", safeSummary(outcome.responseSummary()), String.class);
		return update.fetch().rowsUpdated();
	}

	public Mono<Boolean> completeFailure(
			UUID recipientId, UUID attemptId, byte[] lease,
			SmtpTransportException failure, Instant now
	) {
		return completeFailureDetailed(recipientId, attemptId, lease, failure, now)
				.map(FailureSettlement::applied);
	}

	public Mono<FailureSettlement> completeFailureDetailed(
			UUID recipientId, UUID attemptId, byte[] lease,
			SmtpTransportException failure, Instant now
	) {
		return completeFailureDetailed(recipientId, attemptId, lease, failure, now, null);
	}

	public Mono<FailureSettlement> completeFailureDetailed(
			UUID recipientId, UUID attemptId, byte[] lease,
			SmtpTransportException failure, Instant now, PreparationDiagnostic diagnostic
	) {
		byte[] digest = sha256(lease);
		return database.sql("""
				SELECT attempt_count FROM campaign_recipients
				WHERE id = :recipient AND status = 'CONNECTING'
				  AND delivery_lease_hash = :lease
				  AND EXISTS (SELECT 1 FROM delivery_attempts a
				              WHERE a.id = :attempt AND a.campaign_recipient_id = :recipient
				                AND a.status = 'CONNECTING')
				FOR UPDATE
				""").bind("recipient", recipientId).bind("lease", digest).bind("attempt", attemptId)
					.map((row, metadata) -> requiredInt(row, "attempt_count")).one()
					.flatMap(attemptNumber -> settleFailure(
							recipientId, attemptId, attemptNumber, failure, now)
							.flatMap(settlement -> settlement.applied() && diagnostic != null
									? diagnostics.record(attemptId, "PRODUCTION", diagnostic).thenReturn(settlement)
									: Mono.just(settlement)))
					.defaultIfEmpty(new FailureSettlement(false, null))
					.as(transactions::transactional);
	}

	private Mono<FailureSettlement> settleFailure(
			UUID recipientId, UUID attemptId, int attemptNumber,
			SmtpTransportException failure, Instant now
	) {
		boolean retry = failure.retryable()
				&& failure.responseCode() != null
				&& failure.responseCode() >= 400 && failure.responseCode() <= 499
				&& attemptNumber < properties.maximumAttempts();
		boolean unknown = failure.status() == AttemptStatus.OUTCOME_UNKNOWN;
		String attemptStatus = unknown ? "OUTCOME_UNKNOWN"
				: failure.status() == AttemptStatus.TEMPORARY_FAILURE ? "TEMPORARY_FAILURE"
				: "PERMANENT_FAILURE";
		String recipientStatus = unknown ? "OUTCOME_UNKNOWN" : retry ? "TEMPORARY_FAILURE" : "PERMANENT_FAILURE";
		Instant next = retry
				? now.plus(attemptNumber == 1 ? properties.firstRetryDelay() : properties.secondRetryDelay())
				: now;
		String enhanced = enhancedStatus(failure.responseSummary());
		String unknownReason = unknown ? "SMTP_OUTCOME_UNKNOWN" : null;

		DatabaseClient.GenericExecuteSpec attempt = database.sql("""
				UPDATE delivery_attempts
				SET status = :status, transport_stage = :stage,
				    smtp_response_code = :code, smtp_enhanced_status_code = :enhanced,
				    smtp_response_summary = :summary, failure_category = :category,
				    outcome_unknown_reason = :unknownReason, retryable = :retryable,
				    completed_at = :now
				WHERE id = :attempt AND status = 'CONNECTING'
				""").bind("status", attemptStatus).bind("stage", failure.stage().name())
				.bind("category", failure.category().name()).bind("retryable", retry)
				.bind("now", now).bind("attempt", attemptId);
		attempt = bindNullable(attempt, "code", failure.responseCode(), Integer.class);
		attempt = bindNullable(attempt, "enhanced", enhanced, String.class);
		attempt = bindNullable(attempt, "summary", safeSummary(failure.responseSummary()), String.class);
		attempt = bindNullable(attempt, "unknownReason", unknownReason, String.class);

		DatabaseClient.GenericExecuteSpec recipient = database.sql("""
				UPDATE campaign_recipients
				SET status = :status, next_attempt_at = :next,
				    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL,
				    final_failure_at = CASE WHEN :terminal THEN :now ELSE final_failure_at END,
				    outcome_unknown_at = CASE WHEN :unknown THEN :now ELSE NULL END,
				    outcome_unknown_reason = CASE WHEN :unknown THEN 'SMTP_OUTCOME_UNKNOWN' ELSE NULL END
				WHERE id = :recipient AND status = 'CONNECTING'
				""").bind("status", recipientStatus).bind("next", next)
				.bind("terminal", !retry).bind("unknown", unknown).bind("now", now)
				.bind("recipient", recipientId);
		return attempt.fetch().rowsUpdated()
				.then(recipient.fetch().rowsUpdated())
				.map(updated -> new FailureSettlement(
						updated.longValue() == 1L, AttemptStatus.valueOf(recipientStatus)));
	}

	public Mono<Integer> reconcileExpiredLeases(Instant now) {
		Mono<Integer> work = database.sql("""
				WITH locked_recipients AS MATERIALIZED (
				    SELECT id
				    FROM campaign_recipients
				    WHERE status = 'CONNECTING' AND delivery_lease_expires_at <= :now
				      AND NOT EXISTS (SELECT 1 FROM delivery_attempts a WHERE a.campaign_recipient_id=campaign_recipients.id AND a.status='CONNECTING' AND a.partner_preparation_pending)
				    ORDER BY delivery_lease_expires_at, id
				    FOR UPDATE SKIP LOCKED
				    LIMIT :batchSize
				), expired AS (
				    UPDATE campaign_recipients
				    SET status = 'OUTCOME_UNKNOWN', outcome_unknown_at = :now,
				        outcome_unknown_reason = 'LEASE_EXPIRED', final_failure_at = :now,
				        delivery_lease_hash = NULL, delivery_lease_expires_at = NULL
				    FROM locked_recipients locked
				    WHERE campaign_recipients.id = locked.id
				      AND campaign_recipients.status = 'CONNECTING'
				    RETURNING campaign_recipients.id
				), attempts AS (
				    UPDATE delivery_attempts a
				    SET status = 'OUTCOME_UNKNOWN', transport_stage = COALESCE(transport_stage, 'CONNECT'),
				        outcome_unknown_reason = 'LEASE_EXPIRED', failure_category = 'UNEXPECTED_FAILURE',
				        retryable = false, completed_at = :now
				    FROM expired e
				    WHERE a.campaign_recipient_id = e.id AND a.status = 'CONNECTING'
				    RETURNING a.id
				)
				SELECT count(*)::int AS total FROM expired
				""").bind("now", now).bind("batchSize", properties.batchSize())
				.map((row, metadata) -> requiredInt(row, "total")).one();
		return reconcilePartnerPreparations(now).flatMap(held -> work.map(unknown -> held + unknown)).as(transactions::transactional);
	}

	/** Recheck the exact live claim after preparation and immediately before partner I/O. */
	public Mono<Boolean> productionEligible(ProductionClaim claim, java.time.Clock clock) {
		return activeProduction(claim, clock, false).as(transactions::transactional);
	}

	/** Commit the SMTP-started fence only after all preparation, with campaign-before-recipient locks. */
	public Mono<Boolean> beginSmtp(ProductionClaim claim, java.time.Clock clock) {
		return activeProduction(claim, clock, true).as(transactions::transactional);
	}

	private Mono<Boolean> activeProduction(ProductionClaim claim, java.time.Clock clock, boolean beginSmtp) {
		return new com.camel_hub.advertisement.campaign.migration.CampaignMigrationDeliveryFence(database)
				.lockProductionAndVerify(claim).filter(Boolean::booleanValue)
				.flatMap(ignored -> Mono.defer(() -> {
					// All potentially waiting row locks are held before consulting time.
					Instant now = clock.instant();
					return database.sql("""
					SELECT r.id FROM campaign_recipients r JOIN campaigns c ON c.id=r.campaign_id
					WHERE r.id=:recipient AND r.campaign_id=:campaign AND c.status='RUNNING'
					 AND r.status='CONNECTING' AND r.delivery_lease_hash=:lease AND r.delivery_lease_expires_at>:now
					 AND NOT EXISTS (SELECT 1 FROM campaign_recipients held WHERE held.campaign_id=c.id AND held.partner_invite_hold_reason IS NOT NULL)
					 AND EXISTS (SELECT 1 FROM delivery_attempts a WHERE a.id=:attempt AND a.campaign_recipient_id=r.id AND a.status='CONNECTING' AND a.smtp_started_at IS NULL)
					 AND NOT (%s)
					""".formatted(UNDELIVERABLE_PREDICATE))
					.bind("recipient", claim.recipientId()).bind("campaign", claim.campaignId()).bind("attempt", claim.attemptId())
					.bind("lease", sha256(claim.leaseDigest())).bind("now", now).bind("cooldownCutoff", now.minus(properties.productionCooldown()))
					.map((row, metadata) -> row.get("id", UUID.class)).one();
				}))
				.flatMap(ignored -> !beginSmtp ? Mono.just(true) : Mono.defer(() -> {
					Instant fenceTime = clock.instant();
					return database.sql("""
					 UPDATE delivery_attempts SET smtp_started_at=:now, partner_preparation_pending=false
					 WHERE id=:attempt AND status='CONNECTING' AND smtp_started_at IS NULL
					 AND EXISTS (SELECT 1 FROM campaign_recipients r WHERE r.id=:recipient AND r.delivery_lease_hash=:lease AND r.delivery_lease_expires_at>:now)
					 """).bind("now", fenceTime).bind("attempt", claim.attemptId()).bind("recipient", claim.recipientId())
						.bind("lease", sha256(claim.leaseDigest())).fetch().rowsUpdated().map(rows -> rows.longValue() == 1);
				})).defaultIfEmpty(false);
	}

	public Mono<Boolean> holdPartnerInvite(ProductionClaim claim, String reason, Instant now) {
		return holdPartnerInvite(claim, reason, now, null);
	}

	public Mono<Boolean> holdPartnerInvite(ProductionClaim claim, String reason, Instant now, PreparationDiagnostic diagnostic) {
		return holdPreparation(claim.campaignId(), claim.recipientId(), claim.attemptId(), sha256(claim.leaseDigest()), reason, now, false, false)
				.flatMap(applied -> applied && diagnostic != null
						? diagnostics.record(claim.attemptId(), "PRODUCTION", diagnostic).thenReturn(true) : Mono.just(applied))
				.as(transactions::transactional);
	}

	/** Only the current executor, after its own fence but strictly before sender.send, may compensate. */
	public Mono<Boolean> holdExpiredBeforeSmtp(ProductionClaim claim, Instant now) {
		return holdExpiredBeforeSmtp(claim, now, null);
	}

	public Mono<Boolean> holdExpiredBeforeSmtp(ProductionClaim claim, Instant now, PreparationDiagnostic diagnostic) {
		return holdPreparation(claim.campaignId(), claim.recipientId(), claim.attemptId(), sha256(claim.leaseDigest()), "EXPIRED", now, false, true)
				.flatMap(applied -> applied && diagnostic != null
						? diagnostics.record(claim.attemptId(), "PRODUCTION", diagnostic).thenReturn(true) : Mono.just(applied))
				.as(transactions::transactional);
	}

	private Mono<Boolean> holdPreparation(UUID campaign, UUID recipient, UUID attempt, byte[] leaseHash, String reason, Instant now, boolean expired, boolean fencedButUnsent) {
		if (!java.util.Set.of("DISABLED", "HELD", "EXPIRED", "CLAIMED", "INVALID_INPUT", "UNAVAILABLE", "PREPARATION_INTERRUPTED").contains(reason)) return Mono.error(new IllegalArgumentException("Invalid partner hold reason"));
		return database.sql("SELECT status FROM campaigns WHERE id=:campaign FOR UPDATE").bind("campaign", campaign)
				.map((row, metadata) -> row.get("status", String.class)).one().flatMap(status -> database.sql("""
				 UPDATE campaign_recipients r SET status=:status, partner_invite_hold_reason=:reason,
				  delivery_lease_hash=NULL, delivery_lease_expires_at=NULL, next_attempt_at=:now
				 WHERE r.id=:recipient AND r.campaign_id=:campaign AND r.status='CONNECTING' AND r.delivery_lease_hash=:lease
				 AND (:fenced OR (:expired AND r.delivery_lease_expires_at<=:now) OR (NOT :expired AND r.delivery_lease_expires_at>:now))
				 AND EXISTS (SELECT 1 FROM delivery_attempts a WHERE a.id=:attempt AND a.campaign_recipient_id=r.id
				  AND a.status='CONNECTING' AND ((NOT :fenced AND a.partner_preparation_pending AND a.smtp_started_at IS NULL)
				   OR (:fenced AND NOT a.partner_preparation_pending AND a.smtp_started_at IS NOT NULL)))
				 """).bind("status", "CANCELED".equals(status) ? "CANCELED" : "QUEUED").bind("reason", reason)
				.bind("now", now).bind("recipient", recipient).bind("campaign", campaign).bind("lease", leaseHash)
				.bind("expired", expired).bind("fenced", fencedButUnsent).bind("attempt", attempt).fetch().rowsUpdated())
				.flatMap(rows -> rows.longValue()!=1 ? Mono.just(false) : database.sql("""
				 UPDATE delivery_attempts SET status='CANCELED', transport_stage=NULL, failure_category=:reason,
				 retryable=false, completed_at=:now, partner_preparation_pending=false WHERE id=:attempt AND status='CONNECTING'
				 """).bind("reason", "PARTNER_INVITE_" + reason).bind("now", now).bind("attempt", attempt).fetch().rowsUpdated()
				.then(database.sql("UPDATE campaigns SET status='PAUSED', status_changed_at=:now, updated_at=:now, lock_version=lock_version+1 WHERE id=:campaign AND status IN ('RUNNING','PAUSED')")
					.bind("now", now).bind("campaign", campaign).fetch().rowsUpdated())
				.then(database.sql("""
				 INSERT INTO audit_logs(action,resource_type,resource_id,trace_id,before_summary,after_summary,result)
				 VALUES ('CAMPAIGN_PARTNER_INVITE_HELD','CAMPAIGN_RECIPIENT',:recipient,:trace,'{}'::jsonb,
				 jsonb_build_object('reason',CAST(:reason AS text),'attemptId',CAST(:attempt AS text)),'SUCCESS')
				 """).bind("recipient", recipient.toString()).bind("trace", attempt.toString().replace("-", ""))
					.bind("reason", reason).bind("attempt", attempt.toString()).fetch().rowsUpdated()).thenReturn(true)).defaultIfEmpty(false);
	}

	private Mono<Integer> reconcilePartnerPreparations(Instant now) {
		return database.sql("""
			 SELECT c.id FROM campaigns c WHERE EXISTS (SELECT 1 FROM campaign_recipients r JOIN delivery_attempts a ON a.campaign_recipient_id=r.id
			 WHERE r.campaign_id=c.id AND r.status='CONNECTING' AND r.delivery_lease_expires_at<=:now AND a.status='CONNECTING' AND a.partner_preparation_pending)
			 ORDER BY c.id FOR UPDATE OF c SKIP LOCKED LIMIT :batchSize
			 """).bind("now", now).bind("batchSize", properties.batchSize()).map((row, metadata) -> row.get("id", UUID.class)).all()
				.concatMap(campaign -> database.sql("""
				 SELECT r.id, r.delivery_lease_hash, a.id AS attempt FROM campaign_recipients r JOIN delivery_attempts a ON a.campaign_recipient_id=r.id
				 WHERE r.campaign_id=:campaign AND r.status='CONNECTING' AND r.delivery_lease_expires_at<=:now AND a.status='CONNECTING' AND a.partner_preparation_pending
				 ORDER BY r.id FOR UPDATE OF r,a SKIP LOCKED
				 """).bind("campaign", campaign).bind("now", now)
					.map((row, metadata) -> new PendingPreparation(campaign, row.get("id", UUID.class), row.get("attempt", UUID.class), row.get("delivery_lease_hash", byte[].class))).all())
				.concatMap(pending -> holdPreparation(pending.campaign(), pending.recipient(), pending.attempt(), pending.leaseHash(), "PREPARATION_INTERRUPTED", now, true, false))
				.filter(Boolean::booleanValue).count().map(Long::intValue);
	}
	private record PendingPreparation(UUID campaign, UUID recipient, UUID attempt, byte[] leaseHash) { }

	public Mono<Integer> activateDueCampaigns(Instant now) {
		return database.sql("""
				WITH due AS MATERIALIZED (
				    SELECT id FROM campaigns
				    WHERE status = 'SCHEDULED' AND scheduled_at <= :now
				    ORDER BY scheduled_at, id
				    FOR UPDATE SKIP LOCKED
				    LIMIT :batchSize
				)
				UPDATE campaigns c SET status = 'RUNNING', started_at = COALESCE(c.started_at, :now),
				    status_changed_at = :now, lock_version = lock_version + 1, updated_at = :now
				FROM due WHERE c.id = due.id AND c.status = 'SCHEDULED'
				""").bind("now", now).bind("batchSize", properties.batchSize())
				.fetch().rowsUpdated().map(Number::intValue);
	}

	public Mono<Integer> reconcileCanceledRecipients(Instant now) {
		return database.sql("""
				WITH canceled AS MATERIALIZED (
				    SELECT r.id
				    FROM campaigns c
				    JOIN campaign_recipients r ON r.campaign_id = c.id
				    WHERE c.status = 'CANCELED'
				      AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				      AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				    ORDER BY r.id
				    FOR UPDATE OF r SKIP LOCKED
				    LIMIT :batchSize
				)
				UPDATE campaign_recipients r
				SET status = 'CANCELED', exclusion_reason = 'CAMPAIGN_CANCELED',
				    final_failure_at = :now, delivery_lease_hash = NULL,
				    delivery_lease_expires_at = NULL
				FROM canceled WHERE r.id = canceled.id
				  AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				""").bind("now", now).bind("batchSize", properties.batchSize())
				.fetch().rowsUpdated().map(Number::intValue);
	}

	public Mono<Integer> reconcileCampaigns(Instant now) {
		return database.sql("""
				WITH terminal AS MATERIALIZED (
				    SELECT c.id
				    FROM campaigns c
				    WHERE c.status = 'RUNNING'
				      AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_campaign_id=c.id)
				      AND EXISTS (SELECT 1 FROM campaign_recipients r WHERE r.campaign_id = c.id)
				      AND NOT EXISTS (
				          SELECT 1 FROM campaign_recipients r WHERE r.campaign_id = c.id
				            AND r.status IN ('QUEUED', 'CONNECTING', 'TEMPORARY_FAILURE'))
				    ORDER BY c.id
				    FOR UPDATE OF c SKIP LOCKED
				    LIMIT :batchSize
				)
				UPDATE campaigns c
				SET status = 'COMPLETED', completed_at = :now, status_changed_at = :now,
				    lock_version = lock_version + 1, updated_at = :now
				FROM terminal WHERE c.id = terminal.id AND c.status = 'RUNNING'
				""").bind("now", now).bind("batchSize", properties.batchSize())
				.fetch().rowsUpdated().map(Number::intValue);
	}

	private Candidate candidate(Row row) {
		return new Candidate(
				row.get("id", UUID.class), row.get("campaign_id", UUID.class),
				row.get("smtp_account_id", UUID.class), row.get("template_version_id", UUID.class),
				copy(row.get("email_ciphertext", byte[].class)), copy(row.get("email_nonce", byte[].class)),
				copy(row.get("email_hmac", byte[].class)), row.get("email_domain", String.class),
				requiredInt(row, "attempt_count"), row.get("rfc_message_id", String.class),
				row.get("from_name", String.class), row.get("from_email", String.class),
				row.get("reply_to", String.class), Boolean.TRUE.equals(row.get("tracking_opens_enabled", Boolean.class)),
				Boolean.TRUE.equals(row.get("tracking_clicks_enabled", Boolean.class)),
				Boolean.TRUE.equals(row.get("unsubscribe_enabled", Boolean.class)),
				row.get("rendered_subject", String.class), row.get("rendered_html", String.class),
				row.get("rendered_text", String.class), row.get("next_attempt_at", Instant.class));
	}

	private SmtpRepository.SmtpAccountRecord account(Row row) {
		return new SmtpRepository.SmtpAccountRecord(
				row.get("id", UUID.class), row.get("name", String.class), row.get("host", String.class),
				requiredInt(row, "port"), SmtpModels.TlsMode.valueOf(row.get("tls_mode", String.class)),
				row.get("username", String.class), copy(row.get("password_ciphertext", byte[].class)),
				copy(row.get("password_nonce", byte[].class)), row.get("from_email", String.class),
				row.get("default_from_name", String.class), row.get("reply_to", String.class),
				requiredInt(row, "per_minute_limit"), requiredInt(row, "per_hour_limit"),
				requiredInt(row, "per_day_limit"), row.get("per_month_limit", Integer.class), requiredInt(row, "per_domain_hour_limit"),
				Boolean.TRUE.equals(row.get("enabled", Boolean.class)), row.get("last_tested_at", Instant.class),
				row.get("last_test_status", String.class), row.get("last_test_error", String.class),
				requiredLong(row, "lock_version"), row.get("created_by", UUID.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}

	private static byte[] sha256(byte[] value) {
		if (value == null || value.length != 32) {
			throw new IllegalArgumentException("Delivery lease is required");
		}
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	private static String enhancedStatus(String summary) {
		if (summary == null) return null;
		Matcher matcher = ENHANCED_STATUS.matcher(summary);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static boolean temporarilyIneligible(String reason) {
		return "TEMPORARY_SAFETY_RUN".equals(reason)
				|| "TEMPORARY_UNKNOWN_OUTCOME".equals(reason)
				|| "CAMPAIGN_NOT_RUNNING".equals(reason);
	}

	private static String safeSummary(String value) {
		return SmtpTransportException.sanitize(value);
	}

	private static int requiredInt(Row row, String name) {
		Number value = row.get(name, Number.class);
		if (value == null) throw new IllegalStateException("Missing numeric database field");
		return value.intValue();
	}

	private static long requiredLong(Row row, String name) {
		Number value = row.get(name, Number.class);
		if (value == null) throw new IllegalStateException("Missing numeric database field");
		return value.longValue();
	}

	private static byte[] copy(byte[] value) {
		return value == null ? null : Arrays.copyOf(value, value.length);
	}

	private static DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, Object value, Class<?> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	private record Candidate(
			UUID id, UUID campaignId, UUID smtpAccountId, UUID templateVersionId,
			byte[] emailCiphertext, byte[] emailNonce, byte[] emailHmac, String emailDomain,
			int attemptCount, String messageId, String fromName, String fromEmail, String replyTo,
			boolean trackingOpens, boolean trackingClicks, boolean unsubscribeEnabled,
			String renderedSubject, String renderedHtml, String renderedText, Instant nextAttemptAt
	) { }

	private record SafetyClaimDecision(SafetyClaim claim, boolean handled) {
		private static SafetyClaimDecision claimed(SafetyClaim claim) {
			return new SafetyClaimDecision(claim, true);
		}

		private static SafetyClaimDecision handledWork() {
			return new SafetyClaimDecision(null, true);
		}

		private static SafetyClaimDecision noWork() {
			return new SafetyClaimDecision(null, false);
		}
	}

	private record SafetyRunCandidate(
			UUID id, UUID campaignId, UUID smtpAccountId, byte[] destinationHmac,
			String destinationMasked, String destinationDomain,
			String fromName, String fromEmail, String replyTo,
			boolean trackingOpens, boolean trackingClicks
	) {
		private SafetyRunCandidate {
			destinationHmac = copy(destinationHmac);
		}

		@Override public byte[] destinationHmac() { return copy(destinationHmac); }
	}

	private record SafetyMessageCandidate(
			UUID id, UUID campaignRecipientId, int attemptCount, String rfcMessageId,
			String subject, String html, String text
	) { }

	private record Cooldown(Instant lastAccepted) { }

	private record Eligibility(String reason) { }


	public record FailureSettlement(boolean applied, AttemptStatus recipientStatus) { }

	public sealed interface DeliveryClaim permits ProductionClaim, SafetyClaim { }

	public record ProductionClaim(
			UUID recipientId, UUID campaignId, UUID attemptId, int attemptNumber,
			String idempotencyKey, String rfcMessageId, String correlationId, byte[] leaseDigest,
			byte[] emailCiphertext, byte[] emailNonce, byte[] emailHmac, String emailDomain,
			SmtpRepository.SmtpAccountRecord smtpAccount, UUID templateVersionId,
			String fromName, String fromEmail, String replyTo,
			boolean trackingOpensEnabled, boolean trackingClicksEnabled, boolean unsubscribeEnabled,
			String renderedSubject, String renderedHtml, String renderedText
	) implements DeliveryClaim {
		public ProductionClaim {
			leaseDigest = copy(leaseDigest);
			emailCiphertext = copy(emailCiphertext);
			emailNonce = copy(emailNonce);
			emailHmac = copy(emailHmac);
		}

		@Override public byte[] leaseDigest() { return copy(leaseDigest); }
		@Override public byte[] emailCiphertext() { return copy(emailCiphertext); }
		@Override public byte[] emailNonce() { return copy(emailNonce); }
		@Override public byte[] emailHmac() { return copy(emailHmac); }
	}

	public record SafetyClaim(
			UUID messageId, UUID runId, UUID campaignRecipientId, UUID attemptId, int attemptNumber,
			String idempotencyKey, String rfcMessageId, String correlationId, byte[] leaseDigest,
			byte[] destinationHmac, String destinationMasked, String destinationDomain,
			SmtpRepository.SmtpAccountRecord smtpAccount,
			String fromName, String fromEmail, String replyTo,
			boolean trackingOpensEnabled, boolean trackingClicksEnabled,
			String renderedSubject, String renderedHtml, String renderedText
	) implements DeliveryClaim {
		public SafetyClaim {
			leaseDigest = copy(leaseDigest);
			destinationHmac = copy(destinationHmac);
		}

		/** Exact validated outbound bytes, not an authorization; the durable fence compares them to storage. */
		public SafetyClaim withPreparedBodies(String subject, String html, String text) {
			return new SafetyClaim(messageId,runId,campaignRecipientId,attemptId,attemptNumber,idempotencyKey,rfcMessageId,
				correlationId,leaseDigest,destinationHmac,destinationMasked,destinationDomain,smtpAccount,fromName,fromEmail,
				replyTo,trackingOpensEnabled,trackingClicksEnabled,subject,html,text);
		}

		@Override public byte[] leaseDigest() { return copy(leaseDigest); }
		@Override public byte[] destinationHmac() { return copy(destinationHmac); }
	}
}
