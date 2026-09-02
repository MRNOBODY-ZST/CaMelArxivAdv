package com.camel_hub.advertisement.campaign;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class CampaignWorkflowRepository {

	private static final String RECIPIENT_READINESS_CTES = """
			WITH recipient_state AS (
			    SELECT r.id,
			           jsonb_build_array(
			             r.id, r.contact_id, r.paper_id, r.author_id, encode(r.email_hmac, 'hex'),
			             r.confidence, r.personalization_status,
			             r.rendered_subject, r.rendered_html, r.rendered_text
			           )::text AS snapshot_part,
			           r.personalization_status = 'GENERATED'
			             AND length(trim(coalesce(r.rendered_subject, ''))) > 0
			             AND length(trim(coalesce(r.rendered_html, ''))) > 0
			             AND length(trim(coalesce(r.rendered_text, ''))) > 0 AS content_ready,
			           position('{{unsubscribe_url}}' in coalesce(r.rendered_html, '')) > 0
			             AND position('{{unsubscribe_url}}' in coalesce(r.rendered_text, '')) > 0 AS unsubscribe_present,
			           r.confidence <> 'HIGH' AS confidence_not_high,
			           c.id IS NULL OR c.suppression_status <> 'ACTIVE' OR c.deleted_at IS NOT NULL AS contact_inactive,
			           c.id IS NOT NULL AND c.deleted_at IS NOT NULL AS contact_deleted,
			           c.id IS NULL OR NOT c.syntax_valid AS syntax_invalid,
			           c.id IS NULL OR c.example_address AS example_address,
			           r.author_id IS NULL OR relation.id IS NULL AS author_relation_missing,
			           relation.id IS NOT NULL AND evidence.id IS NOT NULL
			             AND evidence.confidence <> 'HIGH' AS evidence_not_high,
			           relation.id IS NOT NULL AND evidence.id IS NOT NULL
			             AND NOT evidence.human_verified AS evidence_unverified,
			           relation.id IS NOT NULL AND evidence.id IS NOT NULL
			             AND evidence.verification_status <> 'CONFIRMED' AS evidence_unconfirmed,
			           relation.id IS NOT NULL AND (evidence.id IS NULL OR NOT EXISTS (
			               SELECT 1 FROM extraction_evidence ee
			               WHERE ee.paper_author_contact_id = evidence.id
			           )) AS evidence_missing,
			           EXISTS (
			               SELECT 1 FROM suppression_entries se
			               WHERE se.email_hmac = r.email_hmac
			                 AND (se.expires_at IS NULL OR se.expires_at > now())
			           ) AS suppressed,
			           EXISTS (
			               SELECT 1 FROM unsubscribe_records ur WHERE ur.email_hmac = r.email_hmac
			           ) AS unsubscribed,
			           EXISTS (
			               SELECT 1 FROM campaign_exclusions ce
			               WHERE ce.campaign_id = r.campaign_id AND ce.email_hmac = r.email_hmac
			           ) AS campaign_excluded,
			           EXISTS (
			               SELECT 1 FROM recipient_delivery_cooldowns cooldown
			               WHERE cooldown.email_hmac = r.email_hmac
			                 AND cooldown.last_smtp_accepted_at >= :cooldownCutoff
			           ) AS cooldown_active
			    FROM campaign_recipients r
			    LEFT JOIN contacts c ON c.id = r.contact_id
			    LEFT JOIN paper_authors relation
			      ON relation.paper_id = r.paper_id AND relation.author_id = r.author_id
			    LEFT JOIN LATERAL (
			        SELECT pac.id, pac.confidence, pac.human_verified, pac.verification_status
			        FROM paper_author_contacts pac
			        JOIN extraction_runs er ON er.id = pac.extraction_run_id
			          AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
			        WHERE pac.contact_id = r.contact_id
			          AND pac.paper_id = r.paper_id
			          AND pac.paper_author_id = relation.id
			        ORDER BY er.completed_at DESC NULLS LAST, pac.created_at DESC, pac.id
			        LIMIT 1
			    ) evidence ON true
			    WHERE r.campaign_id = %s
			), tallies AS (
			    SELECT count(*) AS total,
			           count(*) FILTER (WHERE content_ready) AS content_ready,
			           count(*) FILTER (WHERE unsubscribe_present) AS unsubscribe_present,
			           count(*) FILTER (WHERE confidence_not_high) AS confidence_not_high,
			           count(*) FILTER (WHERE contact_inactive) AS contact_inactive,
			           count(*) FILTER (WHERE contact_deleted) AS contact_deleted,
			           count(*) FILTER (WHERE syntax_invalid) AS syntax_invalid,
			           count(*) FILTER (WHERE example_address) AS example_address,
			           count(*) FILTER (WHERE author_relation_missing) AS author_relation_missing,
			           count(*) FILTER (WHERE evidence_not_high) AS evidence_not_high,
			           count(*) FILTER (WHERE evidence_unverified) AS evidence_unverified,
			           count(*) FILTER (WHERE evidence_unconfirmed) AS evidence_unconfirmed,
			           count(*) FILTER (WHERE evidence_missing) AS evidence_missing,
			           count(*) FILTER (WHERE suppressed) AS suppressed,
			           count(*) FILTER (WHERE unsubscribed) AS unsubscribed,
			           count(*) FILTER (WHERE campaign_excluded) AS campaign_excluded,
			           count(*) FILTER (WHERE cooldown_active) AS cooldown_active,
			           count(*) FILTER (WHERE content_ready AND unsubscribe_present
			             AND NOT confidence_not_high AND NOT contact_inactive AND NOT contact_deleted
			             AND NOT syntax_invalid AND NOT example_address AND NOT author_relation_missing
			             AND NOT evidence_not_high AND NOT evidence_unverified AND NOT evidence_unconfirmed
			             AND NOT evidence_missing AND NOT suppressed AND NOT unsubscribed
			             AND NOT campaign_excluded AND NOT cooldown_active) AS eligible,
			           encode(digest(coalesce(string_agg(snapshot_part, E'\\n' ORDER BY id), ''), 'sha256'), 'hex')
			             AS recipient_fingerprint
			    FROM recipient_state
			)
			""";

	private final DatabaseClient databaseClient;

	public CampaignWorkflowRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Mono<PreflightRecord> preflight(UUID campaignId, Instant cooldownCutoff) {
		return databaseClient.sql(RECIPIENT_READINESS_CTES.formatted(":campaignId") + """
				SELECT c.id, c.purpose, c.from_name, c.from_email, c.reply_to,
				       c.lock_version,
				       c.tracking_opens_enabled, c.tracking_clicks_enabled,
				       smtp.enabled AS smtp_enabled, smtp.last_test_status AS smtp_test_status,
				       smtp.last_tested_at IS NOT NULL AND smtp.last_tested_at >= smtp.updated_at AS smtp_test_current,
				       smtp.per_minute_limit, smtp.per_hour_limit, smtp.per_day_limit,
				       smtp.per_domain_hour_limit,
				       mailbox.id AS mailbox_id, mailbox.protocol AS mailbox_protocol,
				       mailbox.enabled AS mailbox_enabled,
				       mailbox.last_test_status AS mailbox_test_status,
				       mailbox.last_tested_at IS NOT NULL AND mailbox.last_tested_at >= mailbox.updated_at
				         AS mailbox_test_current,
				       tallies.*
				FROM campaigns c
				JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				LEFT JOIN mailbox_accounts mailbox ON mailbox.id = c.mailbox_account_id
				CROSS JOIN tallies
				WHERE c.id = :campaignId
				""").bind("campaignId", campaignId).bind("cooldownCutoff", cooldownCutoff)
				.map((row, metadata) -> new PreflightRecord(
						row.get("id", UUID.class), row.get("purpose", String.class),
						row.get("from_name", String.class), row.get("from_email", String.class),
						row.get("reply_to", String.class), longNumber(row, "lock_version"),
						row.get("recipient_fingerprint", String.class), bool(row, "tracking_opens_enabled"),
						bool(row, "tracking_clicks_enabled"), bool(row, "smtp_enabled"),
						row.get("smtp_test_status", String.class), bool(row, "smtp_test_current"),
						number(row, "per_minute_limit"),
						number(row, "per_hour_limit"), number(row, "per_day_limit"),
						number(row, "per_domain_hour_limit"), row.get("mailbox_id", UUID.class),
						row.get("mailbox_protocol", String.class),
						bool(row, "mailbox_enabled"), row.get("mailbox_test_status", String.class),
						bool(row, "mailbox_test_current"),
						longNumber(row, "total"), longNumber(row, "content_ready"),
						longNumber(row, "unsubscribe_present"), longNumber(row, "confidence_not_high"),
						longNumber(row, "contact_inactive"), longNumber(row, "contact_deleted"),
						longNumber(row, "syntax_invalid"), longNumber(row, "example_address"),
						longNumber(row, "author_relation_missing"), longNumber(row, "evidence_not_high"),
						longNumber(row, "evidence_unverified"), longNumber(row, "evidence_unconfirmed"),
						longNumber(row, "evidence_missing"), longNumber(row, "suppressed"),
						longNumber(row, "unsubscribed"), longNumber(row, "campaign_excluded"),
						longNumber(row, "cooldown_active"), longNumber(row, "eligible"))).one();
	}

	public Mono<StateRecord> state(UUID id) {
		return databaseClient.sql("SELECT id, status, lock_version FROM campaigns WHERE id = :id")
				.bind("id", id).map(this::mapState).one();
	}

	public Mono<StateRecord> updateDraft(
			UUID id, long expectedLockVersion, CampaignWorkflowService.CampaignUpdateCommand command, UUID actorId
	) {
		return databaseClient.sql("""
				UPDATE campaigns SET name = :name, purpose = :purpose, mailbox_account_id = :mailboxId,
				    from_name = :fromName, reply_to = :replyTo,
				    tracking_opens_enabled = :trackOpens, tracking_clicks_enabled = :trackClicks,
				    status = 'DRAFT', submitted_for_review_at = NULL, approved_at = NULL, approved_by = NULL,
				    rejected_at = NULL, rejected_by = NULL, rejection_reason = NULL,
				    scheduled_at = NULL, started_at = NULL, completed_at = NULL, canceled_at = NULL,
				    review_preflight_digest = NULL, review_preflight_at = NULL,
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				WHERE id = :id AND status IN ('DRAFT', 'REJECTED') AND lock_version = :expected
				  AND EXISTS (SELECT 1 FROM mailbox_accounts mailbox WHERE mailbox.id = :mailboxId)
				RETURNING id, status, lock_version
				""").bind("name", command.name()).bind("purpose", command.purpose())
				.bind("mailboxId", command.mailboxAccountId()).bind("fromName", command.fromName())
				.bind("replyTo", command.replyTo()).bind("trackOpens", command.trackingOpensEnabled())
				.bind("trackClicks", command.trackingClicksEnabled()).bind("actorId", actorId)
				.bind("id", id).bind("expected", expectedLockVersion).map(this::mapState).one();
	}

	public Mono<StateRecord> submitReview(
			UUID id, long expected, UUID actorId, byte[] digest, ReadinessGuard readiness
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql(guardedUpdateSql("""
				UPDATE campaigns c SET status = 'READY_FOR_REVIEW', submitted_for_review_at = now(),
				    review_preflight_digest = :digest, review_preflight_at = now(),
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				FROM readiness ready
				WHERE c.id = :id AND ready.id = c.id
				  AND c.status IN ('DRAFT') AND c.lock_version = :expected
				RETURNING c.id, c.status, c.lock_version
				""")).bind("digest", digest).bind("actorId", actorId).bind("id", id).bind("expected", expected);
		return bindReadiness(statement, readiness)
				.map(this::mapState).one();
	}

	public Mono<StateRecord> approve(
			UUID id, long expected, UUID actorId, byte[] digest, ReadinessGuard readiness
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql(guardedUpdateSql("""
				UPDATE campaigns c SET status = 'APPROVED', approved_at = now(), approved_by = :actorId,
				    rejected_at = NULL, rejected_by = NULL, rejection_reason = NULL,
				    review_preflight_digest = :digest, review_preflight_at = now(),
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				FROM readiness ready
				WHERE c.id = :id AND ready.id = c.id
				  AND c.status IN ('READY_FOR_REVIEW') AND c.lock_version = :expected
				RETURNING c.id, c.status, c.lock_version
				""")).bind("digest", digest).bind("actorId", actorId).bind("id", id).bind("expected", expected);
		return bindReadiness(statement, readiness)
				.map(this::mapState).one();
	}

	public Mono<StateRecord> reject(UUID id, long expected, UUID actorId, String reason) {
		return databaseClient.sql("""
				UPDATE campaigns SET status = 'REJECTED', rejected_at = now(), rejected_by = :actorId,
				    rejection_reason = :reason, approved_at = NULL, approved_by = NULL,
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				WHERE id = :id AND status IN ('READY_FOR_REVIEW') AND lock_version = :expected
				RETURNING id, status, lock_version
				""").bind("reason", reason).bind("actorId", actorId).bind("id", id).bind("expected", expected)
				.map(this::mapState).one();
	}

	public Mono<StateRecord> schedule(
			UUID id, long expected, UUID actorId, Instant scheduledAt, byte[] digest, ReadinessGuard readiness
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql(guardedUpdateSql("""
				UPDATE campaigns c SET status = 'SCHEDULED', scheduled_at = :scheduledAt,
				    review_preflight_digest = :digest, review_preflight_at = now(),
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				FROM readiness ready
				WHERE c.id = :id AND ready.id = c.id
				  AND c.status IN ('APPROVED') AND c.lock_version = :expected
				  AND :scheduledAt > now()
				RETURNING c.id, c.status, c.lock_version
				""")).bind("scheduledAt", scheduledAt).bind("digest", digest).bind("actorId", actorId)
				.bind("id", id).bind("expected", expected);
		return bindReadiness(statement, readiness).map(this::mapState).one();
	}

	public Mono<StateRecord> start(
			UUID id, long expected, UUID actorId, byte[] digest, ReadinessGuard readiness
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql(guardedUpdateSql("""
				UPDATE campaigns c SET status = 'RUNNING', started_at = coalesce(started_at, now()),
				    review_preflight_digest = :digest, review_preflight_at = now(),
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				FROM readiness ready
				WHERE c.id = :id AND ready.id = c.id
				  AND c.status IN ('APPROVED', 'SCHEDULED') AND c.lock_version = :expected
				RETURNING c.id, c.status, c.lock_version
				""")).bind("digest", digest).bind("actorId", actorId).bind("id", id).bind("expected", expected);
		return bindReadiness(statement, readiness)
				.map(this::mapState).one();
	}

	public Mono<StateRecord> pause(UUID id, long expected, UUID actorId) {
		return simpleTransition(id, expected, actorId, "PAUSED", "RUNNING");
	}

	public Mono<StateRecord> resume(UUID id, long expected, UUID actorId) {
		return simpleTransition(id, expected, actorId, "RUNNING", "PAUSED");
	}

	public Mono<StateRecord> cancel(UUID id, long expected, UUID actorId) {
		return databaseClient.sql("""
				UPDATE campaigns SET status = 'CANCELED', canceled_at = now(),
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				WHERE id = :id AND status IN ('SCHEDULED', 'RUNNING', 'PAUSED') AND lock_version = :expected
				RETURNING id, status, lock_version
				""").bind("actorId", actorId).bind("id", id).bind("expected", expected)
				.map(this::mapState).one();
	}

	public Mono<Void> insertDeliveryOutbox(
			UUID messageId, UUID campaignId, String action, long lockVersion,
			String traceId, String payload
	) {
		return databaseClient.sql("""
				INSERT INTO outbox_messages (
				    id, topic_name, routing_key, message_type, message_version,
				    aggregate_id, idempotency_key, payload, trace_id
				)
				VALUES (:messageId, 'camel.mail.delivery.jobs.v1', 'mail.delivery.wakeup',
				        'CAMPAIGN_DELIVERY_WAKEUP', 1, :campaignId, :idempotencyKey,
				        CAST(:payload AS jsonb), :traceId)
				""").bind("messageId", messageId).bind("campaignId", campaignId)
				.bind("idempotencyKey", "campaign-delivery:" + action + ":" + campaignId + ":" + lockVersion)
				.bind("payload", payload).bind("traceId", traceId).fetch().rowsUpdated().then();
	}

	private Mono<StateRecord> simpleTransition(
			UUID id, long expected, UUID actorId, String nextStatus, String allowedStatus
	) {
		String sql = """
				UPDATE campaigns SET status = '%s', status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				WHERE id = :id AND status IN ('%s') AND lock_version = :expected
				RETURNING id, status, lock_version
				""".formatted(nextStatus, allowedStatus);
		return databaseClient.sql(sql).bind("actorId", actorId).bind("id", id).bind("expected", expected)
				.map(this::mapState).one();
	}

	private String guardedUpdateSql(String updateSql) {
		return RECIPIENT_READINESS_CTES.formatted(":id") + """
				, readiness AS (
				    SELECT c.id
				    FROM campaigns c
				    JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				    LEFT JOIN mailbox_accounts mailbox ON mailbox.id = c.mailbox_account_id
				    CROSS JOIN tallies
				    WHERE c.id = :id
				      AND tallies.total > 0
				      AND tallies.content_ready = tallies.total
				      AND tallies.unsubscribe_present = tallies.total
				      AND tallies.eligible > 0
				      AND :senderValid
				      AND smtp.enabled
				      AND smtp.last_test_status = 'SUCCEEDED'
				      AND smtp.last_tested_at IS NOT NULL
				      AND smtp.last_tested_at >= smtp.updated_at
				      AND mailbox.id IS NOT NULL
				      AND mailbox.protocol = 'IMAP'
				      AND mailbox.enabled
				      AND mailbox.last_test_status = 'SUCCEEDED'
				      AND mailbox.last_tested_at IS NOT NULL
				      AND mailbox.last_tested_at >= mailbox.updated_at
				      AND :trackingReady
				)
				""" + updateSql;
	}

	private DatabaseClient.GenericExecuteSpec bindReadiness(
			DatabaseClient.GenericExecuteSpec statement, ReadinessGuard readiness
	) {
		return statement.bind("cooldownCutoff", readiness.cooldownCutoff())
				.bind("senderValid", readiness.senderValid())
				.bind("trackingReady", readiness.trackingReady());
	}

	private StateRecord mapState(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new StateRecord(row.get("id", UUID.class), row.get("status", String.class),
				longNumber(row, "lock_version"));
	}

	private boolean bool(io.r2dbc.spi.Row row, String name) {
		Boolean value = row.get(name, Boolean.class);
		return value != null && value;
	}

	private int number(io.r2dbc.spi.Row row, String name) {
		Number value = row.get(name, Number.class);
		return value == null ? 0 : value.intValue();
	}

	private long longNumber(io.r2dbc.spi.Row row, String name) {
		Number value = row.get(name, Number.class);
		return value == null ? 0 : value.longValue();
	}

	public record StateRecord(UUID id, String status, long lockVersion) { }

	public record ReadinessGuard(Instant cooldownCutoff, boolean senderValid, boolean trackingReady) { }

	public record PreflightRecord(
			UUID campaignId, String purpose, String fromName, String fromEmail, String replyTo,
			long campaignLockVersion, String recipientFingerprint,
			boolean trackingOpensEnabled, boolean trackingClicksEnabled,
			boolean smtpEnabled, String smtpTestStatus, boolean smtpTestCurrent,
			int perMinuteLimit, int perHourLimit,
			int perDayLimit, int perDomainHourLimit, UUID mailboxId, String mailboxProtocol, boolean mailboxEnabled,
			String mailboxTestStatus, boolean mailboxTestCurrent,
			long total, long contentReady, long unsubscribePresent,
			long confidenceNotHigh, long contactInactive, long contactDeleted, long syntaxInvalid,
			long exampleAddress, long authorRelationMissing, long evidenceNotHigh,
			long evidenceUnverified, long evidenceUnconfirmed, long evidenceMissing,
			long suppressed, long unsubscribed, long campaignExcluded, long cooldownActive, long eligible
	) { }
}
