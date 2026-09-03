package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.CampaignConflictException;
import com.camel_hub.advertisement.campaign.CampaignNotFoundException;
import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns only the campaign_safety_* aggregate and its privacy-minimal wake-up. */
public final class CampaignSafetyRepository {
	private static final String SUBJECT_BANNER = "[SAFETY TEST] ";
	private static final String HTML_BANNER = "<div role=\"note\"><strong>SAFETY TEST</strong> — redirected "
			+ "to the configured safety inbox; no author will receive this message.</div>";
	private static final String TEXT_BANNER = "[SAFETY TEST — redirected to the configured safety inbox; "
			+ "no author will receive this message]\n\n";

	private final DatabaseClient database;
	private final TransactionalOperator transactions;
	private final ObjectMapper objectMapper;
	private final CampaignSafetyContentPolicy contentPolicy = new CampaignSafetyContentPolicy();

	public CampaignSafetyRepository(
			DatabaseClient database, TransactionalOperator transactions, ObjectMapper objectMapper
	) {
		this.database = database;
		this.transactions = transactions;
		this.objectMapper = objectMapper;
	}

	public Mono<MaterializedRun> materialize(MaterializeCommand command) {
		validateCommand(command);
		Mono<MaterializedRun> work = lockCampaign(command.campaignId())
				.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.flatMap(campaign -> validateCampaign(campaign, command)
						.then(validateCurrentExclusivity(command.campaignId()))
						.then(loadDrafts(command.campaignId(), command.recipientLimit()).collectList())
						.flatMap(drafts -> insertAggregate(campaign, drafts, command)));
		return work.as(transactions::transactional);
	}

	public Flux<RunSnapshot> list(UUID campaignId) {
		return database.sql("SELECT id FROM campaigns WHERE id = :campaign")
				.bind("campaign", campaignId).map((row, metadata) -> row.get("id", UUID.class)).one()
				.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.flatMapMany(ignored -> database.sql(runViewSql()
						+ " WHERE sr.campaign_id = :campaign ORDER BY sr.created_at DESC, sr.id")
						.bind("campaign", campaignId).map((row, metadata) -> runSnapshot(row)).all()
						.concatMap(this::withMessages));
	}

	public Mono<RunSnapshot> get(UUID campaignId, UUID runId) {
		return database.sql(runViewSql() + " WHERE sr.campaign_id = :campaign AND sr.id = :run")
				.bind("campaign", campaignId).bind("run", runId)
				.map((row, metadata) -> runSnapshot(row)).one().flatMap(this::withMessages);
	}

	public Mono<Boolean> cancel(
			UUID campaignId, UUID runId, long expectedLockVersion,
			UUID actorId, Instant now, String traceId
	) {
		Mono<Boolean> work = database.sql("""
				SELECT id, status, lock_version FROM campaign_safety_runs
				WHERE id = :run AND campaign_id = :campaign
				FOR UPDATE
				""").bind("run", runId).bind("campaign", campaignId)
				.map((row, metadata) -> new LockedRun(
						row.get("id", UUID.class), row.get("status", String.class), requiredLong(row, "lock_version")))
				.one().switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign safety run was not found")))
				.flatMap(run -> {
					if (run.lockVersion() != expectedLockVersion) {
						return Mono.error(new CampaignConflictException("Campaign safety run changed; refresh and retry"));
					}
					if (!List.of("QUEUED", "RUNNING").contains(run.status())) {
						return Mono.error(new CampaignConflictException("Campaign safety run is already terminal"));
					}
					return database.sql("""
							SELECT id FROM campaign_safety_messages
							WHERE run_id = :run ORDER BY id FOR UPDATE
							""").bind("run", runId).map((row, metadata) -> row.get("id", UUID.class)).all()
							.then(database.sql("""
									UPDATE campaign_safety_messages
									SET status = 'CANCELED', delivery_lease_hash = NULL,
									    delivery_lease_expires_at = NULL, next_attempt_at = :now
									WHERE run_id = :run AND status IN ('QUEUED','TEMPORARY_FAILURE')
									""").bind("run", runId).bind("now", now).fetch().rowsUpdated())
							.then(database.sql("""
									UPDATE campaign_safety_runs
									SET status = 'CANCELED', completed_at = :now, lock_version = lock_version + 1
									WHERE id = :run AND status IN ('QUEUED','RUNNING')
									""").bind("run", runId).bind("now", now).fetch().rowsUpdated())
							.flatMap(updated -> database.sql("""
									INSERT INTO audit_logs (
									    actor_user_id, action, resource_type, resource_id, user_agent_summary,
									    trace_id, before_summary, after_summary, result
									) VALUES (:actor, 'CAMPAIGN_SAFETY_CANCELED', 'CAMPAIGN_SAFETY_RUN', :resource,
									          'campaign-safety-api', :trace, CAST(:before AS jsonb),
									          '{"status":"CANCELED"}'::jsonb, 'SUCCESS')
									""").bind("actor", actorId).bind("resource", runId.toString())
									.bind("trace", traceId).bind("before", json(Map.of("status", run.status())))
									.fetch().rowsUpdated().thenReturn(updated.longValue() == 1L));
				});
		return work.as(transactions::transactional);
	}

	public Mono<Boolean> completeAccepted(
			UUID messageId, UUID attemptId, byte[] lease,
			SmtpTransport.SmtpOutcome outcome, Instant now
	) {
		byte[] digest = digestLease(lease);
		Mono<Boolean> work = lookupRun(messageId)
				.flatMap(runId -> lockRun(runId)
						.then(lockMessage(messageId, attemptId, digest))
						.flatMap(message -> lockAttempt(attemptId, messageId)
								.then(updateAcceptedAttempt(attemptId, outcome, now))
								.then(updateAcceptedMessage(messageId, digest, now))
								.flatMap(updated -> aggregate(runId, now).thenReturn(updated == 1L))))
				.defaultIfEmpty(false);
		return work.as(transactions::transactional);
	}

	public Mono<SafetyFailureSettlement> completeFailure(
			UUID messageId, UUID attemptId, byte[] lease,
			SmtpTransportException failure, Instant now, int maximumAttempts,
			java.time.Duration firstRetryDelay, java.time.Duration secondRetryDelay
	) {
		byte[] digest = digestLease(lease);
		Mono<SafetyFailureSettlement> work = lookupRun(messageId)
				.flatMap(runId -> lockRun(runId)
						.flatMap(run -> lockMessage(messageId, attemptId, digest)
								.flatMap(message -> lockAttempt(attemptId, messageId)
										.then(settleFailure(run, message, attemptId, failure, now,
												maximumAttempts, firstRetryDelay, secondRetryDelay))
										.flatMap(settlement -> aggregate(run.id(), now).thenReturn(settlement)))))
				.defaultIfEmpty(new SafetyFailureSettlement(false, null));
		return work.as(transactions::transactional);
	}

	public Mono<Integer> reconcileExpiredLeases(Instant now, int batchSize) {
		Mono<Integer> work = database.sql("""
				SELECT sr.id
				FROM campaign_safety_runs sr
				WHERE sr.status IN ('QUEUED','RUNNING','CANCELED')
				  AND EXISTS (SELECT 1 FROM campaign_safety_messages m
				              WHERE m.run_id = sr.id AND m.status = 'CONNECTING'
				                AND m.delivery_lease_expires_at <= :now)
				ORDER BY sr.id
				FOR UPDATE SKIP LOCKED
				LIMIT :limit
				""").bind("now", now).bind("limit", batchSize)
				.map((row, metadata) -> row.get("id", UUID.class)).all()
				.concatMap(runId -> reconcileExpiredRun(runId, now)).reduce(0, Integer::sum);
		return work.as(transactions::transactional);
	}

	public Mono<Integer> reconcileAggregates(Instant now, int batchSize) {
		return database.sql("""
				SELECT id FROM campaign_safety_runs
				WHERE status IN ('QUEUED','RUNNING') ORDER BY id
				FOR UPDATE SKIP LOCKED LIMIT :limit
				""").bind("limit", batchSize).map((row, metadata) -> row.get("id", UUID.class)).all()
				.concatMap(runId -> aggregate(runId, now)).reduce(0, Integer::sum)
				.as(transactions::transactional);
	}

	/**
	 * Makes the runtime kill switch durable. CONNECTING messages remain fenced for a
	 * late settlement or lease reconciliation while the aggregate becomes sticky CANCELED.
	 */
	public Mono<Integer> cancelActiveRunsBecauseDisabled(Instant now, int batchSize) {
		if (now == null || batchSize < 1) {
			return Mono.error(new IllegalArgumentException("Campaign safety disabled reconciliation is invalid"));
		}
		Mono<Integer> work = database.sql("""
				SELECT id, created_by, status
				FROM campaign_safety_runs
				WHERE status IN ('QUEUED','RUNNING')
				ORDER BY created_at, id
				FOR UPDATE SKIP LOCKED
				LIMIT :limit
				""").bind("limit", batchSize)
				.map((row, metadata) -> new DisabledRun(
						row.get("id", UUID.class), row.get("created_by", UUID.class),
						row.get("status", String.class))).all()
				.concatMap(run -> cancelBecauseDisabled(run, now)).reduce(0, Integer::sum);
		return work.as(transactions::transactional);
	}

	private Mono<Integer> cancelBecauseDisabled(DisabledRun run, Instant now) {
		return database.sql("""
				SELECT id FROM campaign_safety_messages
				WHERE run_id = :run ORDER BY id FOR UPDATE
				""").bind("run", run.id()).map((row, metadata) -> row.get("id", UUID.class)).all()
				.then(database.sql("""
						UPDATE campaign_safety_messages
						SET status = 'CANCELED', delivery_lease_hash = NULL,
						    delivery_lease_expires_at = NULL, next_attempt_at = :now
						WHERE run_id = :run AND status IN ('QUEUED','TEMPORARY_FAILURE')
						""").bind("run", run.id()).bind("now", now).fetch().rowsUpdated())
				.then(database.sql("""
						UPDATE campaign_safety_runs
						SET status = 'CANCELED', completed_at = :now, lock_version = lock_version + 1
						WHERE id = :run AND status IN ('QUEUED','RUNNING')
						""").bind("run", run.id()).bind("now", now).fetch().rowsUpdated())
				.flatMap(updated -> updated.longValue() != 1L ? Mono.just(0)
						: insertDisabledCancellationAudit(run));
	}

	private Mono<Integer> insertDisabledCancellationAudit(DisabledRun run) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				INSERT INTO audit_logs (
				    actor_user_id, action, resource_type, resource_id,
				    user_agent_summary, trace_id, before_summary, after_summary, result
				) VALUES (
				    :actor, 'CAMPAIGN_SAFETY_DISABLED_CANCELED', 'CAMPAIGN_SAFETY_RUN',
				    :resource, 'campaign-safety-worker', 'campaign-safety-disabled',
				    CAST(:before AS jsonb),
				    '{"status":"CANCELED","reason":"SAFETY_DISABLED"}'::jsonb, 'SUCCESS'
				)
				""").bind("resource", run.id().toString())
				.bind("before", json(Map.of("status", run.status())));
		statement = bindNullable(statement, "actor", run.actorId(), UUID.class);
		return statement.fetch().rowsUpdated().thenReturn(1);
	}

	public Mono<PreparationState> lockPreparation(
			com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.SafetyClaim claim,
			byte[] leaseHash, Instant now
	) {
		return database.sql("""
				SELECT id, destination_hmac, tracking_opens_enabled, tracking_clicks_enabled
				FROM campaign_safety_runs
				WHERE id = :run AND status IN ('QUEUED','RUNNING')
				FOR UPDATE
				""").bind("run", claim.runId()).map((row, metadata) -> new PreparationRun(
				row.get("id", UUID.class), copy(row.get("destination_hmac", byte[].class)),
				Boolean.TRUE.equals(row.get("tracking_opens_enabled", Boolean.class)),
				Boolean.TRUE.equals(row.get("tracking_clicks_enabled", Boolean.class)))).one()
				.flatMap(run -> database.sql("""
						SELECT m.id, m.run_id, m.campaign_recipient_id, m.attempt_count,
						       m.delivery_lease_expires_at, m.rendered_subject, m.rendered_html, m.rendered_text,
						       previous.status AS previous_attempt_status,
						       previous.retryable AS previous_attempt_retryable,
						       previous.smtp_response_code AS previous_response_code,
						       previous.failure_category AS previous_failure_category
						FROM campaign_safety_messages m
						JOIN campaign_safety_attempts current_attempt
						  ON current_attempt.id = :attempt AND current_attempt.safety_message_id = m.id
						 AND current_attempt.attempt_number = m.attempt_count
						 AND current_attempt.status = 'CONNECTING'
						LEFT JOIN campaign_safety_attempts previous
						  ON previous.safety_message_id = m.id
						 AND previous.attempt_number = m.attempt_count - 1
						WHERE m.id = :message AND m.run_id = :run AND m.status = 'CONNECTING'
						  AND m.attempt_count = :attemptNumber AND m.delivery_lease_hash = :lease
						  AND m.delivery_lease_expires_at > :now
						FOR UPDATE OF m, current_attempt
						""").bind("attempt", claim.attemptId()).bind("message", claim.messageId()).bind("run", claim.runId())
						.bind("attemptNumber", claim.attemptNumber()).bind("lease", leaseHash).bind("now", now)
						.map((row, metadata) -> new PreparationState(
								row.get("id", UUID.class), row.get("run_id", UUID.class),
								row.get("campaign_recipient_id", UUID.class), requiredInt(row, "attempt_count"),
								row.get("delivery_lease_expires_at", Instant.class), run.destinationHmac(),
								run.trackingOpens(), run.trackingClicks(), row.get("rendered_subject", String.class),
								row.get("rendered_html", String.class), row.get("rendered_text", String.class),
								row.get("previous_attempt_status", String.class),
								row.get("previous_attempt_retryable", Boolean.class),
								row.get("previous_response_code", Integer.class),
								row.get("previous_failure_category", String.class)))
						.one());
	}

	public Mono<Void> insertLink(
			UUID id, UUID messageId, String type, String targetUrl, byte[] targetHash,
			byte[] tokenHash, Instant expiresAt, Instant now
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				INSERT INTO campaign_safety_links (
				    id, safety_message_id, target_url, target_url_hash,
				    token_type, token_hash, expires_at, created_at
				) VALUES (:id, :message, :target, :targetHash, :type, :tokenHash, :expires, :now)
				""").bind("id", id).bind("message", messageId).bind("type", type)
				.bind("tokenHash", tokenHash).bind("expires", expiresAt).bind("now", now);
		statement = bindNullable(statement, "target", targetUrl, String.class);
		statement = bindNullable(statement, "targetHash", targetHash, byte[].class);
		return statement.fetch().rowsUpdated().then();
	}

	public Flux<TrackingArtifact> artifacts(UUID messageId) {
		return database.sql("""
				SELECT id, token_type, target_url, target_url_hash, token_hash, expires_at
				FROM campaign_safety_links WHERE safety_message_id = :message
				ORDER BY token_type, target_url NULLS FIRST, id
				""").bind("message", messageId).map((row, metadata) -> new TrackingArtifact(
				row.get("id", UUID.class), row.get("token_type", String.class), row.get("target_url", String.class),
				copy(row.get("target_url_hash", byte[].class)), copy(row.get("token_hash", byte[].class)),
				row.get("expires_at", Instant.class))).all();
	}

	public Mono<Void> rotateFrozenLink(
			UUID messageId, TrackingArtifact expected, byte[] replacementTokenHash,
			Instant replacementExpiresAt
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				UPDATE campaign_safety_links
				SET token_hash = :replacementHash, expires_at = :replacementExpires
				WHERE id = :id AND safety_message_id = :message AND token_type = :type
				  AND token_hash = :expectedHash AND expires_at = :expectedExpires
				  AND target_url IS NOT DISTINCT FROM :target
				  AND target_url_hash IS NOT DISTINCT FROM :targetHash
				""").bind("replacementHash", replacementTokenHash)
				.bind("replacementExpires", replacementExpiresAt).bind("id", expected.id())
				.bind("message", messageId).bind("type", expected.type())
				.bind("expectedHash", expected.tokenHash()).bind("expectedExpires", expected.expiresAt());
		statement = bindNullable(statement, "target", expected.targetUrl(), String.class);
		statement = bindNullable(statement, "targetHash", expected.targetHash(), byte[].class);
		return statement.fetch().rowsUpdated()
				.flatMap(updated -> updated.longValue() == 1L ? Mono.empty()
						: Mono.error(new IllegalStateException("Campaign safety frozen artifact changed")));
	}

	public Mono<Void> persistPreparedBodies(
			com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.SafetyClaim claim,
			byte[] leaseHash, String subject, String html, String text, Instant now
	) {
		return database.sql("""
				UPDATE campaign_safety_messages m
				SET rendered_subject = :subject, rendered_html = :html, rendered_text = :text
				WHERE m.id = :message AND m.run_id = :run AND m.status = 'CONNECTING'
				  AND m.delivery_lease_hash = :lease AND m.delivery_lease_expires_at > :now
				  AND EXISTS (SELECT 1 FROM campaign_safety_runs sr
				              WHERE sr.id = m.run_id AND sr.status IN ('QUEUED','RUNNING'))
				""").bind("subject", subject).bind("html", html).bind("text", text)
				.bind("message", claim.messageId()).bind("run", claim.runId()).bind("lease", leaseHash)
				.bind("now", now).fetch().rowsUpdated()
				.flatMap(updated -> updated.longValue() == 1L ? Mono.empty()
						: Mono.error(new IllegalStateException("Campaign safety preparation lease is no longer active")));
	}

	public Mono<ResolvedCallback> resolveCallback(byte[] tokenHash, String type, Instant now) {
		return database.sql("""
				SELECT link.id AS link_id, link.safety_message_id, message.run_id, link.target_url
				FROM campaign_safety_links link
				JOIN campaign_safety_messages message ON message.id = link.safety_message_id
				WHERE link.token_hash = :tokenHash AND link.token_type = :type AND link.expires_at > :now
				  AND (message.status = 'SMTP_ACCEPTED'
				       OR (message.status = 'CONNECTING' AND message.delivery_lease_expires_at > :now))
				""").bind("tokenHash", tokenHash).bind("type", type).bind("now", now)
				.map((row, metadata) -> new ResolvedCallback(
						row.get("run_id", UUID.class), row.get("safety_message_id", UUID.class),
						row.get("link_id", UUID.class), row.get("target_url", String.class))).one();
	}

	public Mono<Boolean> observeCallback(
			ResolvedCallback callback, String type, Observation observation, Instant now
	) {
		return database.sql("""
				INSERT INTO campaign_safety_events (
				    run_id, safety_message_id, safety_link_id, event_type,
				    fingerprint_hash, minute_bucket, occurred_at, classification, classification_reason
				) VALUES (:run, :message, :link, :type, :fingerprint, :minute, :now, :classification, :reason)
				ON CONFLICT (safety_link_id, fingerprint_hash, minute_bucket)
				    WHERE event_type IN ('OPEN','CLICK','UNSUBSCRIBE') DO NOTHING
				""").bind("run", callback.runId()).bind("message", callback.messageId())
				.bind("link", callback.linkId()).bind("type", type)
				.bind("fingerprint", observation.fingerprintHash()).bind("minute", now.getEpochSecond() / 60)
				.bind("now", now).bind("classification", observation.classification())
				.bind("reason", observation.reason()).fetch().rowsUpdated()
				.map(updated -> updated.longValue() == 1L);
	}

	private Mono<UUID> lookupRun(UUID messageId) {
		return database.sql("SELECT run_id FROM campaign_safety_messages WHERE id = :message")
				.bind("message", messageId).map((row, metadata) -> row.get("run_id", UUID.class)).one();
	}

	private Mono<LockedRun> lockRun(UUID runId) {
		return database.sql("SELECT id, status, lock_version FROM campaign_safety_runs WHERE id = :run FOR UPDATE")
				.bind("run", runId).map((row, metadata) -> new LockedRun(
						row.get("id", UUID.class), row.get("status", String.class), requiredLong(row, "lock_version")))
				.one();
	}

	private Mono<LockedMessage> lockMessage(UUID messageId, UUID attemptId, byte[] leaseDigest) {
		return database.sql("""
				SELECT m.id, m.run_id, m.attempt_count
				FROM campaign_safety_messages m
				WHERE m.id = :message AND m.status = 'CONNECTING'
				  AND m.delivery_lease_hash = :lease
				  AND EXISTS (SELECT 1 FROM campaign_safety_attempts a
				              WHERE a.id = :attempt AND a.safety_message_id = m.id AND a.status = 'CONNECTING')
				FOR UPDATE OF m
				""").bind("message", messageId).bind("lease", leaseDigest).bind("attempt", attemptId)
				.map((row, metadata) -> new LockedMessage(
						row.get("id", UUID.class), row.get("run_id", UUID.class), requiredInt(row, "attempt_count")))
				.one();
	}

	private Mono<UUID> lockAttempt(UUID attemptId, UUID messageId) {
		return database.sql("""
				SELECT id FROM campaign_safety_attempts
				WHERE id = :attempt AND safety_message_id = :message AND status = 'CONNECTING'
				FOR UPDATE
				""").bind("attempt", attemptId).bind("message", messageId)
				.map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	private Mono<Long> updateAcceptedAttempt(
			UUID attemptId, SmtpTransport.SmtpOutcome outcome, Instant now
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				UPDATE campaign_safety_attempts
				SET status = 'SMTP_ACCEPTED', transport_stage = :stage,
				    smtp_response_code = :code, smtp_response_summary = :summary,
				    retryable = false, completed_at = :now
				WHERE id = :attempt AND status = 'CONNECTING'
				""").bind("stage", outcome.stage().name())
				.bind("code", outcome.responseCode() == null ? 250 : outcome.responseCode())
				.bind("now", now).bind("attempt", attemptId);
		statement = bindNullable(statement, "summary", SmtpTransportException.sanitize(outcome.responseSummary()), String.class);
		return statement.fetch().rowsUpdated();
	}

	private Mono<Long> updateAcceptedMessage(UUID messageId, byte[] lease, Instant now) {
		return database.sql("""
				UPDATE campaign_safety_messages
				SET status = 'SMTP_ACCEPTED', smtp_accepted_at = :now,
				    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL,
				    next_attempt_at = :now
				WHERE id = :message AND status = 'CONNECTING' AND delivery_lease_hash = :lease
				""").bind("now", now).bind("message", messageId).bind("lease", lease)
				.fetch().rowsUpdated();
	}

	private Mono<SafetyFailureSettlement> settleFailure(
			LockedRun run, LockedMessage message, UUID attemptId, SmtpTransportException failure,
			Instant now, int maximumAttempts,
			java.time.Duration firstRetryDelay, java.time.Duration secondRetryDelay
	) {
		boolean explicitFourHundred = failure.category() == SmtpTransportException.FailureCategory.SMTP_REJECTED
				&& failure.retryable() && failure.responseCode() != null
				&& failure.responseCode() >= 400 && failure.responseCode() <= 499;
		boolean retry = explicitFourHundred && message.attemptCount() < maximumAttempts
				&& List.of("QUEUED", "RUNNING").contains(run.status());
		boolean unknown = failure.status() == AttemptStatus.OUTCOME_UNKNOWN;
		String attemptStatus = unknown ? "OUTCOME_UNKNOWN"
				: explicitFourHundred ? "TEMPORARY_FAILURE" : "PERMANENT_FAILURE";
		String messageStatus = unknown ? "OUTCOME_UNKNOWN"
				: retry ? "TEMPORARY_FAILURE" : "PERMANENT_FAILURE";
		Instant next = retry ? now.plus(message.attemptCount() == 1 ? firstRetryDelay : secondRetryDelay) : now;
		DatabaseClient.GenericExecuteSpec attempt = database.sql("""
				UPDATE campaign_safety_attempts
				SET status = :status, transport_stage = :stage, smtp_response_code = :code,
				    smtp_response_summary = :summary, failure_category = :category,
				    outcome_unknown_reason = :unknownReason, retryable = :retryable,
				    completed_at = :now
				WHERE id = :attempt AND status = 'CONNECTING'
				""").bind("status", attemptStatus).bind("stage", failure.stage().name())
				.bind("category", failure.category().name()).bind("retryable", retry)
				.bind("now", now).bind("attempt", attemptId);
		attempt = bindNullable(attempt, "code", failure.responseCode(), Integer.class);
		attempt = bindNullable(attempt, "summary", SmtpTransportException.sanitize(failure.responseSummary()), String.class);
		attempt = bindNullable(attempt, "unknownReason", unknown ? "SMTP_OUTCOME_UNKNOWN" : null, String.class);
		DatabaseClient.GenericExecuteSpec messageUpdate = database.sql("""
				UPDATE campaign_safety_messages
				SET status = :status, next_attempt_at = :next,
				    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL,
				    outcome_unknown_at = CASE WHEN :unknown THEN :now ELSE NULL END,
				    outcome_unknown_reason = CASE WHEN :unknown THEN 'SMTP_OUTCOME_UNKNOWN' ELSE NULL END
				WHERE id = :message AND status = 'CONNECTING'
				""").bind("status", messageStatus).bind("next", next).bind("unknown", unknown)
				.bind("now", now).bind("message", message.id());
		return attempt.fetch().rowsUpdated().then(messageUpdate.fetch().rowsUpdated())
				.map(updated -> new SafetyFailureSettlement(updated.longValue() == 1L, messageStatus));
	}

	private Mono<Integer> reconcileExpiredRun(UUID runId, Instant now) {
		return database.sql("""
				SELECT id FROM campaign_safety_messages
				WHERE run_id = :run AND status = 'CONNECTING' AND delivery_lease_expires_at <= :now
				ORDER BY id FOR UPDATE
				""").bind("run", runId).bind("now", now)
				.map((row, metadata) -> row.get("id", UUID.class)).all().collectList()
				.flatMap(ids -> {
					if (ids.isEmpty()) return Mono.just(0);
					return database.sql("""
							UPDATE campaign_safety_attempts a
							SET status = 'OUTCOME_UNKNOWN', transport_stage = COALESCE(transport_stage, 'CONNECT'),
							    failure_category = 'UNEXPECTED_FAILURE', outcome_unknown_reason = 'LEASE_EXPIRED',
							    retryable = false, completed_at = :now
							WHERE a.safety_message_id = ANY(:messages) AND a.status = 'CONNECTING'
							""").bind("messages", ids.toArray(UUID[]::new)).bind("now", now).fetch().rowsUpdated()
							.then(database.sql("""
									UPDATE campaign_safety_messages
									SET status = 'OUTCOME_UNKNOWN', outcome_unknown_at = :now,
									    outcome_unknown_reason = 'LEASE_EXPIRED', delivery_lease_hash = NULL,
									    delivery_lease_expires_at = NULL, next_attempt_at = :now
									WHERE id = ANY(:messages) AND status = 'CONNECTING'
									""").bind("messages", ids.toArray(UUID[]::new)).bind("now", now)
									.fetch().rowsUpdated())
							.flatMap(updated -> aggregate(runId, now).thenReturn(updated.intValue()));
				});
	}

	private Mono<Integer> aggregate(UUID runId, Instant now) {
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

	private Mono<CampaignRow> lockCampaign(UUID campaignId) {
		return database.sql("""
				SELECT id, smtp_account_id
				FROM campaigns
				WHERE id = :campaign
				FOR UPDATE
				""").bind("campaign", campaignId)
				.map((row, metadata) -> new CampaignLock(
						row.get("id", UUID.class), row.get("smtp_account_id", UUID.class)))
				.one().flatMap(lock -> database.sql("""
						SELECT id FROM smtp_accounts WHERE id = :smtp FOR UPDATE
						""").bind("smtp", lock.smtpAccountId())
						.map((row, metadata) -> row.get("id", UUID.class)).one()
						.then(loadCampaignSnapshot(lock.id())));
	}

	private Mono<CampaignRow> loadCampaignSnapshot(UUID campaignId) {
		return database.sql("""
				SELECT c.id, c.smtp_account_id, c.status, c.lock_version,
				       c.from_name, c.from_email, c.reply_to,
				       c.tracking_opens_enabled, c.tracking_clicks_enabled,
				       smtp.enabled AS smtp_enabled, smtp.last_test_status,
				       smtp.last_tested_at, smtp.updated_at,
				       EXISTS (SELECT 1 FROM campaign_safety_runs sr
				               WHERE sr.campaign_id = c.id AND sr.status IN ('QUEUED','RUNNING')) AS active_run,
				       EXISTS (SELECT 1 FROM campaign_recipients r
				               WHERE r.campaign_id = c.id AND r.status = 'CONNECTING') AS production_connecting
				FROM campaigns c
				JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				WHERE c.id = :campaign
				""").bind("campaign", campaignId).map((row, metadata) -> new CampaignRow(
				row.get("id", UUID.class), row.get("smtp_account_id", UUID.class),
				row.get("status", String.class), requiredLong(row, "lock_version"),
				row.get("from_name", String.class), row.get("from_email", String.class),
				row.get("reply_to", String.class),
				Boolean.TRUE.equals(row.get("tracking_opens_enabled", Boolean.class)),
				Boolean.TRUE.equals(row.get("tracking_clicks_enabled", Boolean.class)),
				Boolean.TRUE.equals(row.get("smtp_enabled", Boolean.class)),
				row.get("last_test_status", String.class), row.get("last_tested_at", Instant.class),
				row.get("updated_at", Instant.class), Boolean.TRUE.equals(row.get("active_run", Boolean.class)),
				Boolean.TRUE.equals(row.get("production_connecting", Boolean.class)))).one();
	}

	private Mono<RunSnapshot> withMessages(RunSnapshot run) {
		return database.sql("""
				SELECT id, campaign_recipient_id, status, attempt_count,
				       smtp_accepted_at, outcome_unknown_at, outcome_unknown_reason
				FROM campaign_safety_messages
				WHERE run_id = :run
				ORDER BY created_at, id
				""").bind("run", run.id()).map((row, metadata) -> new MessageSnapshot(
				row.get("id", UUID.class), row.get("campaign_recipient_id", UUID.class),
				row.get("status", String.class), requiredInt(row, "attempt_count"),
				row.get("smtp_accepted_at", Instant.class), row.get("outcome_unknown_at", Instant.class),
				row.get("outcome_unknown_reason", String.class))).all().collectList()
				.map(messages -> run.withMessages(messages));
	}

	private String runViewSql() {
		return """
				SELECT sr.id, sr.campaign_id, sr.status, sr.recipient_limit, sr.destination_masked,
				       sr.lock_version, sr.started_at, sr.completed_at, sr.created_at,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id) AS total,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id AND m.status = 'QUEUED') AS queued,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id AND m.status = 'CONNECTING') AS connecting,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id AND m.status = 'SMTP_ACCEPTED') AS accepted,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id AND m.status = 'TEMPORARY_FAILURE') AS temporary_failure,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id AND m.status = 'PERMANENT_FAILURE') AS permanent_failure,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id AND m.status = 'CANCELED') AS canceled,
				       (SELECT count(*)::int FROM campaign_safety_messages m WHERE m.run_id = sr.id AND m.status = 'OUTCOME_UNKNOWN') AS outcome_unknown,
				       (SELECT count(*)::bigint FROM campaign_safety_events e WHERE e.run_id = sr.id AND e.event_type = 'OPEN') AS opens,
				       (SELECT count(*)::bigint FROM campaign_safety_events e WHERE e.run_id = sr.id AND e.event_type = 'CLICK') AS clicks,
				       (SELECT count(*)::bigint FROM campaign_safety_events e WHERE e.run_id = sr.id AND e.event_type = 'UNSUBSCRIBE') AS unsubscribes,
				       (SELECT count(*)::bigint FROM campaign_safety_events e WHERE e.run_id = sr.id AND e.event_type = 'REPLY') AS replies,
				       (SELECT count(*)::bigint FROM campaign_safety_events e WHERE e.run_id = sr.id AND e.event_type = 'AUTO_REPLY') AS auto_replies,
				       (SELECT count(*)::bigint FROM campaign_safety_events e WHERE e.run_id = sr.id AND e.event_type = 'BOUNCE') AS bounces
				FROM campaign_safety_runs sr
				""";
	}

	private RunSnapshot runSnapshot(Row row) {
		return new RunSnapshot(
				row.get("id", UUID.class), row.get("campaign_id", UUID.class), row.get("status", String.class),
				requiredInt(row, "recipient_limit"), row.get("destination_masked", String.class),
				requiredInt(row, "total"), requiredInt(row, "queued"), requiredInt(row, "connecting"),
				requiredInt(row, "accepted"), requiredInt(row, "temporary_failure"),
				requiredInt(row, "permanent_failure"), requiredInt(row, "canceled"),
				requiredInt(row, "outcome_unknown"), requiredLong(row, "opens"), requiredLong(row, "clicks"),
				requiredLong(row, "unsubscribes"), requiredLong(row, "replies"),
				requiredLong(row, "auto_replies"), requiredLong(row, "bounces"),
				requiredLong(row, "lock_version"), row.get("started_at", Instant.class),
				row.get("completed_at", Instant.class), row.get("created_at", Instant.class), List.of());
	}

	private Mono<Void> validateCampaign(CampaignRow campaign, MaterializeCommand command) {
		if (campaign.lockVersion() != command.expectedLockVersion()) {
			return Mono.error(new CampaignConflictException("Campaign changed; refresh before starting a safety run"));
		}
		if (List.of("COMPLETED", "CANCELED").contains(campaign.status())) {
			return Mono.error(new CampaignValidationException("Campaign is not approved for a safety run"));
		}
		if (!campaign.smtpEnabled() || !"SUCCEEDED".equals(campaign.smtpHealth())
				|| campaign.smtpTestedAt() == null || campaign.smtpUpdatedAt() == null
				|| campaign.smtpTestedAt().isBefore(campaign.smtpUpdatedAt())) {
			return Mono.error(new CampaignValidationException("Campaign SMTP account is not healthy"));
		}
		if (campaign.activeRun()) {
			return Mono.error(new CampaignConflictException("Campaign already has an active safety run"));
		}
		if (campaign.productionConnecting()) {
			return Mono.error(new CampaignConflictException("Campaign delivery is already handed to SMTP"));
		}
		if (contentPolicy.containsForbiddenSenderMetadata(
				campaign.fromName(), campaign.fromEmail(), campaign.replyTo())) {
			return Mono.error(new CampaignValidationException("Campaign safety sender metadata is invalid"));
		}
		return Mono.empty();
	}

	private Flux<SourceDraft> loadDrafts(UUID campaignId, int limit) {
		return database.sql("""
				SELECT r.id, r.rendered_subject, r.rendered_html, r.rendered_text
				FROM campaign_recipients r
				WHERE r.campaign_id = :campaign
				  AND r.status IN ('QUEUED','TEMPORARY_FAILURE')
				  AND r.personalization_status = 'GENERATED'
				  AND r.attempt_count = 0
				  AND r.rendered_subject IS NOT NULL AND btrim(r.rendered_subject) <> ''
				  AND r.rendered_html IS NOT NULL AND btrim(r.rendered_html) <> ''
				  AND r.rendered_text IS NOT NULL AND btrim(r.rendered_text) <> ''
				  AND NOT EXISTS (SELECT 1 FROM tracking_tokens token
				                  WHERE token.campaign_recipient_id = r.id)
				ORDER BY r.id
				FOR UPDATE OF r
				LIMIT :limit
				""").bind("campaign", campaignId).bind("limit", limit)
				.map((row, metadata) -> new SourceDraft(
						row.get("id", UUID.class), row.get("rendered_subject", String.class),
						row.get("rendered_html", String.class), row.get("rendered_text", String.class)))
				.all();
	}

	private Mono<Void> validateCurrentExclusivity(UUID campaignId) {
		return database.sql("""
				SELECT EXISTS (SELECT 1 FROM campaign_safety_runs sr
				               WHERE sr.campaign_id = :campaign AND sr.status IN ('QUEUED','RUNNING')) AS active_run,
				       EXISTS (SELECT 1 FROM campaign_recipients r
				               WHERE r.campaign_id = :campaign AND r.status = 'CONNECTING') AS production_connecting
				""").bind("campaign", campaignId)
				.map((row, metadata) -> new ExclusiveState(
						Boolean.TRUE.equals(row.get("active_run", Boolean.class)),
						Boolean.TRUE.equals(row.get("production_connecting", Boolean.class))))
				.one().flatMap(state -> {
					if (state.activeRun()) {
						return Mono.error(new CampaignConflictException("Campaign already has an active safety run"));
					}
					if (state.productionConnecting()) {
						return Mono.error(new CampaignConflictException("Campaign delivery is already handed to SMTP"));
					}
					return Mono.empty();
				});
	}

	private Mono<MaterializedRun> insertAggregate(
			CampaignRow campaign, List<SourceDraft> drafts, MaterializeCommand command
	) {
		if (drafts.isEmpty()) {
			return Mono.error(new CampaignValidationException("Campaign has no immutable generated drafts"));
		}
		for (SourceDraft draft : drafts) validateDraft(draft);
		UUID runId = UUID.randomUUID();
		UUID outboxId = UUID.randomUUID();
		List<UUID> recipientIds = drafts.stream().map(SourceDraft::id).toList();
		Mono<Void> run = database.sql("""
				INSERT INTO campaign_safety_runs (
				    id, campaign_id, smtp_account_id, created_by, recipient_limit,
				    destination_hmac, destination_masked, from_name_snapshot,
				    from_email_snapshot, reply_to_snapshot, tracking_opens_enabled,
				    tracking_clicks_enabled, status, created_at
				) VALUES (:id, :campaign, :smtp, :actor, :limit, :hmac, :masked,
				          :fromName, :fromEmail, :replyTo, :opens, :clicks, 'QUEUED', :now)
				""").bind("id", runId).bind("campaign", campaign.id()).bind("smtp", campaign.smtpAccountId())
				.bind("actor", command.actorId()).bind("limit", command.recipientLimit())
				.bind("hmac", command.destinationHmac()).bind("masked", command.destinationMasked())
				.bind("fromName", campaign.fromName()).bind("fromEmail", campaign.fromEmail())
				.bind("replyTo", campaign.replyTo()).bind("opens", campaign.trackingOpens())
				.bind("clicks", campaign.trackingClicks())
				.bind("now", command.now()).fetch().rowsUpdated().then();
		Mono<Void> messages = Flux.fromIterable(drafts).concatMap(draft -> {
			UUID messageId = UUID.randomUUID();
			return database.sql("""
					INSERT INTO campaign_safety_messages (
					    id, run_id, campaign_recipient_id, smtp_account_id, status,
					    next_attempt_at, attempt_count, rfc_message_id,
					    rendered_subject, rendered_html, rendered_text, created_at
					) VALUES (:id, :run, :recipient, :smtp, 'QUEUED', :now, 0, :messageId,
					          :subject, :html, :text, :now)
					""").bind("id", messageId).bind("run", runId).bind("recipient", draft.id())
					.bind("smtp", campaign.smtpAccountId()).bind("now", command.now())
					.bind("messageId", "<safety-" + messageId + "@delivery.camel-arxiv.invalid>")
					.bind("subject", SUBJECT_BANNER + draft.subject())
					.bind("html", HTML_BANNER + draft.html()).bind("text", TEXT_BANNER + draft.text())
					.fetch().rowsUpdated();
		}).then();
		String payload = json(Map.of(
				"version", 1, "messageId", outboxId, "safetyRunId", runId,
				"action", "SAFETY_START", "traceId", command.traceId(), "createdAt", command.now()));
		Mono<Void> outbox = database.sql("""
				INSERT INTO outbox_messages (
				    id, topic_name, routing_key, message_type, message_version,
				    aggregate_id, idempotency_key, payload, trace_id, available_at
				) VALUES (:id, 'camel.mail.delivery.jobs.v1', 'mail.delivery.wakeup',
				          'CAMPAIGN_DELIVERY_WAKEUP', 1, :run, :key, CAST(:payload AS jsonb), :trace, :now)
				""").bind("id", outboxId).bind("run", runId).bind("key", "campaign-safety:start:" + runId)
				.bind("payload", payload).bind("trace", command.traceId()).bind("now", command.now())
				.fetch().rowsUpdated().then();
		Mono<Void> audit = database.sql("""
				INSERT INTO audit_logs (
				    actor_user_id, action, resource_type, resource_id, user_agent_summary,
				    trace_id, before_summary, after_summary, result
				) VALUES (:actor, 'CAMPAIGN_SAFETY_STARTED', 'CAMPAIGN_SAFETY_RUN', :resource,
				          'campaign-safety-api', :trace, '{}'::jsonb,
				          CAST(:after AS jsonb), 'SUCCESS')
				""").bind("actor", command.actorId()).bind("resource", runId.toString())
				.bind("trace", command.traceId()).bind("after", json(Map.of(
						"status", "QUEUED", "recipientCount", drafts.size(),
						"destinationMasked", command.destinationMasked())))
				.fetch().rowsUpdated().then();
		return run.then(messages).then(outbox).then(audit)
				.thenReturn(new MaterializedRun(runId, campaign.id(), recipientIds));
	}

	private void validateDraft(SourceDraft draft) {
		try {
			contentPolicy.validateSource(draft.subject(), draft.html(), draft.text());
		}
		catch (IllegalArgumentException rejected) {
			throw new CampaignValidationException("Safety source draft contains forbidden delivery data");
		}
	}

	private void validateCommand(MaterializeCommand command) {
		if (command == null || command.campaignId() == null || command.actorId() == null
				|| command.expectedLockVersion() < 0 || command.recipientLimit() < 1 || command.recipientLimit() > 20
				|| command.destinationHmac() == null || command.destinationHmac().length != 32
				|| command.destinationMasked() == null || !command.destinationMasked().matches("[^@]+@[^@]+")
				|| command.now() == null || command.traceId() == null
				|| !command.traceId().matches("[A-Za-z0-9_-]{1,64}")) {
			throw new CampaignValidationException("Campaign safety materialization request is invalid");
		}
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Campaign safety wake-up could not be serialized", exception);
		}
	}

	private static long requiredLong(Row row, String name) {
		Number number = row.get(name, Number.class);
		if (number == null) throw new IllegalStateException("Missing campaign safety numeric field");
		return number.longValue();
	}

	private static int requiredInt(Row row, String name) {
		Number number = row.get(name, Number.class);
		if (number == null) throw new IllegalStateException("Missing campaign safety numeric field");
		return number.intValue();
	}

	private static byte[] digestLease(byte[] lease) {
		if (lease == null || lease.length != 32) {
			throw new IllegalArgumentException("Campaign safety delivery lease is required");
		}
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(lease);
		}
		catch (java.security.GeneralSecurityException impossible) {
			throw new IllegalStateException("Campaign safety lease digest is unavailable", impossible);
		}
	}

	private static DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, Object value, Class<?> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	private static byte[] copy(byte[] value) {
		return value == null ? null : Arrays.copyOf(value, value.length);
	}

	private record CampaignRow(
			UUID id, UUID smtpAccountId, String status, long lockVersion,
			String fromName, String fromEmail, String replyTo,
			boolean trackingOpens, boolean trackingClicks,
			boolean smtpEnabled, String smtpHealth, Instant smtpTestedAt, Instant smtpUpdatedAt,
			boolean activeRun, boolean productionConnecting
	) { }
	private record CampaignLock(UUID id, UUID smtpAccountId) { }

	private record SourceDraft(UUID id, String subject, String html, String text) { }
	private record ExclusiveState(boolean activeRun, boolean productionConnecting) { }
	private record LockedRun(UUID id, String status, long lockVersion) { }

	private record DisabledRun(UUID id, UUID actorId, String status) { }
	private record LockedMessage(UUID id, UUID runId, int attemptCount) { }
	private record PreparationRun(UUID id, byte[] destinationHmac, boolean trackingOpens, boolean trackingClicks) {
		private PreparationRun {
			destinationHmac = copy(destinationHmac);
		}

		@Override public byte[] destinationHmac() { return copy(destinationHmac); }
	}

	public record MaterializeCommand(
			UUID campaignId, UUID actorId, long expectedLockVersion, int recipientLimit,
			byte[] destinationHmac, String destinationMasked, Instant now, String traceId
	) {
		public MaterializeCommand {
			destinationHmac = destinationHmac == null ? null : Arrays.copyOf(destinationHmac, destinationHmac.length);
		}

		@Override public byte[] destinationHmac() {
			return destinationHmac == null ? null : Arrays.copyOf(destinationHmac, destinationHmac.length);
		}
	}

	public record MaterializedRun(UUID id, UUID campaignId, List<UUID> recipientIds) {
		public MaterializedRun {
			recipientIds = List.copyOf(new ArrayList<>(recipientIds));
		}
	}

	public record RunSnapshot(
			UUID id, UUID campaignId, String status, int recipientLimit, String destinationMasked,
			int total, int queued, int connecting, int accepted, int temporaryFailure,
			int permanentFailure, int canceled, int outcomeUnknown,
			long opens, long clicks, long unsubscribes, long replies, long autoReplies, long bounces,
			long lockVersion, Instant startedAt, Instant completedAt, Instant createdAt,
			List<MessageSnapshot> messages
	) {
		public RunSnapshot {
			messages = List.copyOf(messages);
		}

		RunSnapshot withMessages(List<MessageSnapshot> value) {
			return new RunSnapshot(id, campaignId, status, recipientLimit, destinationMasked,
					total, queued, connecting, accepted, temporaryFailure, permanentFailure,
					canceled, outcomeUnknown, opens, clicks, unsubscribes, replies, autoReplies, bounces,
					lockVersion, startedAt, completedAt, createdAt, value);
		}
	}

	public record MessageSnapshot(
			UUID id, UUID campaignRecipientId, String status, int attemptCount,
			Instant smtpAcceptedAt, Instant outcomeUnknownAt, String outcomeUnknownReason
	) { }

	public record SafetyFailureSettlement(boolean applied, String messageStatus) { }

	public record PreparationState(
			UUID messageId, UUID runId, UUID campaignRecipientId, int attemptNumber,
			Instant leaseExpiresAt, byte[] destinationHmac,
			boolean trackingOpens, boolean trackingClicks,
			String subject, String html, String text,
			String previousAttemptStatus, Boolean previousAttemptRetryable,
			Integer previousResponseCode, String previousFailureCategory
	) {
		public PreparationState {
			destinationHmac = copy(destinationHmac);
		}

		@Override public byte[] destinationHmac() { return copy(destinationHmac); }
	}

	public record TrackingArtifact(
			UUID id, String type, String targetUrl, byte[] targetHash, byte[] tokenHash, Instant expiresAt
	) {
		public TrackingArtifact {
			targetHash = copy(targetHash);
			tokenHash = copy(tokenHash);
		}

		@Override public byte[] targetHash() { return copy(targetHash); }
		@Override public byte[] tokenHash() { return copy(tokenHash); }
	}

	public record ResolvedCallback(UUID runId, UUID messageId, UUID linkId, String targetUrl) { }

	public record Observation(String classification, String reason, byte[] fingerprintHash) {
		public Observation {
			fingerprintHash = copy(fingerprintHash);
		}

		@Override public byte[] fingerprintHash() { return copy(fingerprintHash); }
	}
}
