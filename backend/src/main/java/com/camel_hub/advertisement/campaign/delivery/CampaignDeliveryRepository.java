package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.email.smtp.SmtpModels;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
	private static final String CURRENT_APPROVED_EVIDENCE = """
			EXISTS (
			    SELECT 1
			    FROM paper_author_contacts pac
			    JOIN paper_authors pa ON pa.id = pac.paper_author_id
			    JOIN extraction_runs er ON er.id = pac.extraction_run_id
			    WHERE pac.contact_id = r.contact_id
			      AND pac.paper_id = r.paper_id
			      AND pa.author_id = r.author_id
			      AND pa.paper_id = r.paper_id
			      AND pac.verification_status = 'CONFIRMED'
			      AND pac.confidence = 'HIGH'
			      AND pac.human_verified
			      AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
			      AND pac.id = (
			          SELECT latest_pac.id
			          FROM paper_author_contacts latest_pac
			          JOIN extraction_runs latest_er ON latest_er.id = latest_pac.extraction_run_id
			            AND latest_er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
			          WHERE latest_pac.contact_id = r.contact_id
			            AND latest_pac.paper_id = r.paper_id
			            AND latest_pac.paper_author_id = pa.id
			          ORDER BY latest_er.completed_at DESC NULLS LAST,
			                   latest_pac.created_at DESC, latest_pac.id
			          LIMIT 1)
			      AND EXISTS (SELECT 1 FROM extraction_evidence ev
			                  WHERE ev.paper_author_contact_id = pac.id)
			)
			""";
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
			OR r.confidence <> 'HIGH'
			OR r.rendered_subject IS NULL OR btrim(r.rendered_subject) = ''
			OR r.rendered_html IS NULL OR btrim(r.rendered_html) = ''
			OR r.rendered_text IS NULL OR btrim(r.rendered_text) = ''
			OR (r.attempt_count = 0 AND (
			    position('{{unsubscribe_url}}' in r.rendered_html) = 0
			    OR position('{{unsubscribe_url}}' in r.rendered_text) = 0))
			OR NOT (%s)
			""".formatted(CURRENT_APPROVED_EVIDENCE);

	private final DatabaseClient database;
	private final TransactionalOperator transactions;
	private final CampaignDeliveryProperties properties;

	public CampaignDeliveryRepository(
			DatabaseClient database, TransactionalOperator transactions,
			CampaignDeliveryProperties properties, CampaignSafetyProperties safety
	) {
		this.database = database;
		this.transactions = transactions;
		this.properties = properties;
	}

	/** Claims at most one recipient and commits its lease before returning. */
	public Mono<ProductionClaim> claimNext(Instant now) {
		Mono<ProductionClaim> work = selectCandidate(now)
				.flatMap(candidate -> lockAccount(candidate.smtpAccountId())
						.flatMap(account -> lockCooldown(candidate, now)
								.flatMap(cooldown -> verifyAfterLocks(candidate, cooldown, now)
										.flatMap(eligible -> loadReservations(account.id(), now)
												.collectList()
												.flatMap(reservations -> {
													Instant release = capacityRelease(account, candidate.emailDomain(), reservations, now);
													return release == null
															? reserve(eligible, account, now)
															: defer(eligible.id(), release).then(Mono.empty());
												})))));
		return work.as(transactions::transactional);
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
				            AND (%1$s))
				    ORDER BY c.id
				    FOR UPDATE OF c SKIP LOCKED
				    LIMIT 1
				), locked_recipients AS MATERIALIZED (
				    SELECT r.id
				    FROM locked_campaign c
				    JOIN campaign_recipients r ON r.campaign_id = c.id
				    WHERE r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
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

	private Mono<Candidate> selectCandidate(Instant now) {
		return database.sql("""
				SELECT c.id
				FROM campaigns c
				WHERE c.status = 'RUNNING'
				  AND EXISTS (SELECT 1 FROM campaign_recipients r
				              WHERE r.campaign_id = c.id
				                AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				                AND r.next_attempt_at <= :now
				                AND r.attempt_count < :maximumAttempts
				                AND NOT EXISTS (SELECT 1 FROM campaign_recipients guarded
				                                WHERE guarded.email_hmac = r.email_hmac
				                                  AND guarded.id <> r.id
				                                  AND guarded.status IN ('CONNECTING', 'OUTCOME_UNKNOWN')))
				  AND NOT EXISTS (SELECT 1 FROM campaign_safety_runs sr
				                  WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED', 'RUNNING'))
				ORDER BY c.id
				FOR SHARE OF c SKIP LOCKED
				LIMIT 1
				""").bind("now", now).bind("maximumAttempts", properties.maximumAttempts())
				.map((row, metadata) -> row.get("id", UUID.class)).one()
				.flatMap(campaignId -> database.sql("""
				SELECT r.id, r.campaign_id, r.contact_id, r.paper_id, r.author_id,
				       r.email_ciphertext, r.email_nonce, r.email_hmac, r.email_domain,
				       r.attempt_count, r.rfc_message_id, r.rendered_subject,
				       r.rendered_html, r.rendered_text,
				       c.smtp_account_id, c.template_version_id, c.from_name, c.from_email,
				       c.reply_to, c.tracking_opens_enabled, c.tracking_clicks_enabled,
				       c.unsubscribe_enabled
				FROM campaign_recipients r
				JOIN campaigns c ON c.id = r.campaign_id
				WHERE c.id = :campaignId AND c.status = 'RUNNING'
				  AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				  AND r.next_attempt_at <= :now
				  AND r.attempt_count < :maximumAttempts
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipients guarded
				                  WHERE guarded.email_hmac = r.email_hmac
				                    AND guarded.id <> r.id
				                    AND guarded.status IN ('CONNECTING', 'OUTCOME_UNKNOWN'))
				  AND NOT EXISTS (SELECT 1 FROM campaign_safety_runs sr
				                  WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED', 'RUNNING'))
				ORDER BY r.next_attempt_at, r.id
				FOR UPDATE OF r SKIP LOCKED
				LIMIT 1
				""").bind("campaignId", campaignId).bind("now", now)
						.bind("maximumAttempts", properties.maximumAttempts())
						.map((row, metadata) -> candidate(row)).one());
	}

	private Mono<SmtpRepository.SmtpAccountRecord> lockAccount(UUID id) {
		return database.sql("""
				SELECT id, name, host, port, tls_mode, username, password_ciphertext, password_nonce,
				       from_email, default_from_name, reply_to, per_minute_limit, per_hour_limit,
				       per_day_limit, per_domain_hour_limit, enabled, last_tested_at,
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
				    WHEN r.confidence <> 'HIGH' THEN 'EVIDENCE_INVALID'
				    WHEN r.personalization_status <> 'GENERATED'
				         OR r.rendered_subject IS NULL OR btrim(r.rendered_subject) = ''
				         OR r.rendered_html IS NULL OR btrim(r.rendered_html) = ''
				         OR r.rendered_text IS NULL OR btrim(r.rendered_text) = ''
				         OR (r.attempt_count = 0 AND (
				             position('{{unsubscribe_url}}' in r.rendered_html) = 0
				             OR position('{{unsubscribe_url}}' in r.rendered_text) = 0)) THEN 'CONTENT_INVALID'
				    WHEN NOT EXISTS (
				        SELECT 1 FROM paper_author_contacts pac
				        JOIN paper_authors pa ON pa.id = pac.paper_author_id
				        JOIN extraction_runs er ON er.id = pac.extraction_run_id
				        WHERE pac.contact_id = r.contact_id AND pac.paper_id = r.paper_id
				          AND pa.author_id = r.author_id AND pa.paper_id = r.paper_id
				          AND pac.verification_status = 'CONFIRMED'
				          AND pac.confidence = 'HIGH' AND pac.human_verified
				          AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
				          AND pac.id = (
				              SELECT latest_pac.id
				              FROM paper_author_contacts latest_pac
				              JOIN extraction_runs latest_er ON latest_er.id = latest_pac.extraction_run_id
				                AND latest_er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
				              WHERE latest_pac.contact_id = r.contact_id
				                AND latest_pac.paper_id = r.paper_id
				                AND latest_pac.paper_author_id = pa.id
				              ORDER BY latest_er.completed_at DESC NULLS LAST,
				                       latest_pac.created_at DESC, latest_pac.id
				              LIMIT 1)
				          AND EXISTS (SELECT 1 FROM extraction_evidence ev
				                      WHERE ev.paper_author_contact_id = pac.id)) THEN 'EVIDENCE_INVALID'
				    ELSE NULL END AS reason
				FROM campaign_recipients r
				JOIN campaigns c ON c.id = r.campaign_id
				JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				LEFT JOIN mailbox_accounts mailbox ON mailbox.id = c.mailbox_account_id
				LEFT JOIN contacts contact ON contact.id = r.contact_id
				WHERE r.id = :id AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				""").bind("now", now).bind("id", recipientId)
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

	private Flux<Reservation> loadReservations(UUID smtpAccountId, Instant now) {
		return database.sql("""
				SELECT a.started_at, r.email_domain
				FROM delivery_attempts a
				JOIN campaign_recipients r ON r.id = a.campaign_recipient_id
				WHERE a.smtp_account_id = :smtp
				  AND a.status IN ('CONNECTING', 'SMTP_ACCEPTED')
				  AND a.started_at > :dayCutoff
				UNION ALL
				SELECT a.started_at,
				       nullif(lower(split_part(sr.destination_masked, '@', 2)), '')
				FROM campaign_safety_attempts a
				JOIN campaign_safety_messages m ON m.id = a.safety_message_id
				JOIN campaign_safety_runs sr ON sr.id = m.run_id
				WHERE m.smtp_account_id = :smtp
				  AND a.status IN ('CONNECTING', 'SMTP_ACCEPTED')
				  AND a.started_at > :dayCutoff
				""").bind("smtp", smtpAccountId).bind("dayCutoff", now.minus(Duration.ofDays(1)))
				.map((row, metadata) -> new Reservation(
						row.get("started_at", Instant.class), row.get("email_domain", String.class)))
				.all();
	}

	private Instant capacityRelease(
			SmtpRepository.SmtpAccountRecord account, String recipientDomain,
			List<Reservation> reservations, Instant now
	) {
		List<Instant> saturated = new ArrayList<>();
		addRelease(saturated, reservations, account.perMinuteLimit(), Duration.ofMinutes(1), now, null);
		addRelease(saturated, reservations, account.perHourLimit(), Duration.ofHours(1), now, null);
		addRelease(saturated, reservations, account.perDayLimit(), Duration.ofDays(1), now, null);
		addRelease(saturated, reservations, account.perDomainHourLimit(), Duration.ofHours(1), now,
				recipientDomain);
		return saturated.stream().max(Comparator.naturalOrder()).orElse(null);
	}

	private void addRelease(
			List<Instant> releases, List<Reservation> reservations, int limit,
			Duration window, Instant now, String domain
	) {
		List<Instant> inWindow = reservations.stream()
				.filter(item -> item.startedAt().isAfter(now.minus(window)))
				.filter(item -> domain == null || domain.equalsIgnoreCase(item.domain()))
				.map(Reservation::startedAt).sorted().toList();
		if (inWindow.size() >= limit) {
			releases.add(inWindow.get(inWindow.size() - limit).plus(window));
		}
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
				  AND r.status IN ('QUEUED', 'TEMPORARY_FAILURE')
				  AND r.next_attempt_at <= :now AND r.attempt_count = :oldAttemptCount
				  AND r.confidence = 'HIGH'
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
				  AND EXISTS (
				      SELECT 1 FROM paper_author_contacts pac
				      JOIN paper_authors pa ON pa.id = pac.paper_author_id
				      JOIN extraction_runs er ON er.id = pac.extraction_run_id
				      WHERE pac.contact_id = r.contact_id AND pac.paper_id = r.paper_id
				        AND pa.author_id = r.author_id AND pa.paper_id = r.paper_id
				        AND pac.verification_status = 'CONFIRMED'
				        AND pac.confidence = 'HIGH' AND pac.human_verified
				        AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
				        AND pac.id = (
				            SELECT latest_pac.id
				            FROM paper_author_contacts latest_pac
				            JOIN extraction_runs latest_er ON latest_er.id = latest_pac.extraction_run_id
				              AND latest_er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
				            WHERE latest_pac.contact_id = r.contact_id
				              AND latest_pac.paper_id = r.paper_id
				              AND latest_pac.paper_author_id = pa.id
				            ORDER BY latest_er.completed_at DESC NULLS LAST,
				                     latest_pac.created_at DESC, latest_pac.id
				            LIMIT 1)
				        AND EXISTS (SELECT 1 FROM extraction_evidence ev
				                    WHERE ev.paper_author_contact_id = pac.id))
				""").bind("number", attemptNumber).bind("oldAttemptCount", candidate.attemptCount())
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
							    rfc_message_id, started_at
							) VALUES (:attempt, :recipient, :smtp, :number, :key, 'CONNECTING',
							          'CONNECT', false, :messageId, :now)
							""").bind("attempt", attemptId).bind("recipient", candidate.id())
							.bind("smtp", account.id()).bind("number", attemptNumber)
							.bind("key", idempotency).bind("messageId", messageId).bind("now", now)
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
							recipientId, attemptId, attemptNumber, failure, now))
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
		return work.as(transactions::transactional);
	}

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
				row.get("rendered_text", String.class));
	}

	private SmtpRepository.SmtpAccountRecord account(Row row) {
		return new SmtpRepository.SmtpAccountRecord(
				row.get("id", UUID.class), row.get("name", String.class), row.get("host", String.class),
				requiredInt(row, "port"), SmtpModels.TlsMode.valueOf(row.get("tls_mode", String.class)),
				row.get("username", String.class), copy(row.get("password_ciphertext", byte[].class)),
				copy(row.get("password_nonce", byte[].class)), row.get("from_email", String.class),
				row.get("default_from_name", String.class), row.get("reply_to", String.class),
				requiredInt(row, "per_minute_limit"), requiredInt(row, "per_hour_limit"),
				requiredInt(row, "per_day_limit"), requiredInt(row, "per_domain_hour_limit"),
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
			String renderedSubject, String renderedHtml, String renderedText
	) { }

	private record Cooldown(Instant lastAccepted) { }

	private record Eligibility(String reason) { }

	private record Reservation(Instant startedAt, String domain) { }

	public record FailureSettlement(boolean applied, AttemptStatus recipientStatus) { }

	public record ProductionClaim(
			UUID recipientId, UUID campaignId, UUID attemptId, int attemptNumber,
			String idempotencyKey, String rfcMessageId, String correlationId, byte[] leaseDigest,
			byte[] emailCiphertext, byte[] emailNonce, byte[] emailHmac, String emailDomain,
			SmtpRepository.SmtpAccountRecord smtpAccount, UUID templateVersionId,
			String fromName, String fromEmail, String replyTo,
			boolean trackingOpensEnabled, boolean trackingClicksEnabled, boolean unsubscribeEnabled,
			String renderedSubject, String renderedHtml, String renderedText
	) {
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
}
