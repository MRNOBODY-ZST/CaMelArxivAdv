package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Database boundary for production campaign capabilities and engagement events. */
public final class CampaignTrackingRepository {

	private final DatabaseClient database;

	public CampaignTrackingRepository(DatabaseClient database) {
		this.database = database;
	}

	public Mono<PreparationState> lockPreparation(
			CampaignDeliveryRepository.ProductionClaim claim, byte[] leaseHash, Instant now
	) {
		return database.sql("""
				SELECT r.id, r.campaign_id, c.template_version_id,
				       r.rendered_subject, r.rendered_html, r.rendered_text,
				       c.tracking_opens_enabled, c.tracking_clicks_enabled, c.unsubscribe_enabled,
				       a.attempt_number, r.delivery_lease_expires_at,
				       previous.status AS previous_status,
				       previous.retryable AS previous_retryable,
				       previous.smtp_response_code AS previous_response_code,
				       previous.failure_category AS previous_failure_category
				FROM campaign_recipients r
				JOIN campaigns c ON c.id = r.campaign_id
				JOIN delivery_attempts a ON a.id = :attempt AND a.campaign_recipient_id = r.id
				LEFT JOIN delivery_attempts previous
				  ON previous.campaign_recipient_id = r.id
				 AND previous.attempt_number = a.attempt_number - 1
				WHERE r.id = :recipient AND r.campaign_id = :campaign
				  AND r.status = 'CONNECTING' AND r.delivery_lease_hash = :lease
				  AND r.delivery_lease_expires_at > :now
				  AND a.status = 'CONNECTING' AND a.attempt_number = :attemptNumber
				FOR UPDATE OF r
				""").bind("attempt", claim.attemptId()).bind("recipient", claim.recipientId())
				.bind("campaign", claim.campaignId()).bind("lease", leaseHash).bind("now", now)
				.bind("attemptNumber", claim.attemptNumber())
				.map((row, metadata) -> new PreparationState(
						row.get("id", UUID.class), row.get("campaign_id", UUID.class),
						row.get("template_version_id", UUID.class), row.get("rendered_subject", String.class),
						row.get("rendered_html", String.class), row.get("rendered_text", String.class),
						Boolean.TRUE.equals(row.get("tracking_opens_enabled", Boolean.class)),
						Boolean.TRUE.equals(row.get("tracking_clicks_enabled", Boolean.class)),
						Boolean.TRUE.equals(row.get("unsubscribe_enabled", Boolean.class)),
						number(row, "attempt_number"), row.get("delivery_lease_expires_at", Instant.class),
						row.get("previous_status", String.class),
						row.get("previous_retryable", Boolean.class),
						row.get("previous_response_code", Integer.class),
						row.get("previous_failure_category", String.class)))
				.one();
	}

	public Mono<PersistedLink> ensureLink(
			UUID campaignId, UUID templateVersionId, String targetUrl, String label, Instant now
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				INSERT INTO campaign_links (
				    id, campaign_id, template_version_id, target_url, target_url_hash, label, created_at
				) VALUES (:id, :campaign, :version, :target, :hash, :label, :now)
				ON CONFLICT (campaign_id, target_url_hash)
				DO UPDATE SET target_url = campaign_links.target_url
				RETURNING id, target_url
				""").bind("id", UUID.randomUUID()).bind("campaign", campaignId)
				.bind("version", templateVersionId).bind("target", targetUrl)
				.bind("hash", CampaignTrackingSigner.sha256(targetUrl)).bind("now", now);
		statement = label == null ? statement.bindNull("label", String.class) : statement.bind("label", label);
		return statement.map((row, metadata) -> new PersistedLink(
				row.get("id", UUID.class), row.get("target_url", String.class))).one()
				.flatMap(link -> targetUrl.equals(link.targetUrl()) ? Mono.just(link)
						: Mono.error(new IllegalStateException("Campaign link digest collision")));
	}

	public Mono<Void> insertToken(
			UUID recipientId, UUID linkId, String type, byte[] tokenHash, Instant expiresAt, Instant now
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				INSERT INTO tracking_tokens (
				    id, campaign_recipient_id, campaign_link_id, token_type, token_hash, expires_at, created_at
				) VALUES (:id, :recipient, :link, :type, :hash, :expires, :now)
				""").bind("id", UUID.randomUUID()).bind("recipient", recipientId)
				.bind("type", type).bind("hash", tokenHash).bind("expires", expiresAt).bind("now", now);
		statement = linkId == null ? statement.bindNull("link", UUID.class) : statement.bind("link", linkId);
		return statement.fetch().rowsUpdated().flatMap(rows -> rows.longValue() == 1L ? Mono.empty()
				: Mono.error(new IllegalStateException("Campaign tracking token was not persisted")));
	}

	public Mono<Void> deleteFrozenTokens(UUID recipientId, int expectedCount) {
		return database.sql("DELETE FROM tracking_tokens WHERE campaign_recipient_id = :recipient")
				.bind("recipient", recipientId).fetch().rowsUpdated()
				.flatMap(rows -> rows.longValue() == expectedCount ? Mono.empty()
						: Mono.error(new IllegalStateException("Frozen campaign tracking tokens changed concurrently")));
	}

	public Mono<Void> persistPreparedBodies(
			CampaignDeliveryRepository.ProductionClaim claim, byte[] leaseHash,
			String subject, String html, String text, Instant now
	) {
		return database.sql("""
				UPDATE campaign_recipients
				SET rendered_subject = :subject, rendered_html = :html, rendered_text = :text
				WHERE id = :recipient AND campaign_id = :campaign AND status = 'CONNECTING'
				  AND delivery_lease_hash = :lease AND delivery_lease_expires_at > :now
				  AND EXISTS (SELECT 1 FROM delivery_attempts a
				              WHERE a.id = :attempt AND a.campaign_recipient_id = :recipient
				                AND a.status = 'CONNECTING' AND a.attempt_number = :attemptNumber)
				""").bind("subject", subject).bind("html", html).bind("text", text)
				.bind("recipient", claim.recipientId()).bind("campaign", claim.campaignId())
				.bind("lease", leaseHash).bind("now", now).bind("attempt", claim.attemptId())
				.bind("attemptNumber", claim.attemptNumber())
				.fetch().rowsUpdated().flatMap(rows -> rows.longValue() == 1L ? Mono.empty()
						: Mono.error(new IllegalStateException("Campaign preparation lease is no longer active")));
	}

	public Flux<FrozenArtifact> frozenArtifacts(UUID recipientId) {
		return database.sql("""
				SELECT t.token_type, t.campaign_link_id, t.token_hash, t.expires_at, l.target_url
				FROM tracking_tokens t
				JOIN campaign_recipients r ON r.id = t.campaign_recipient_id
				LEFT JOIN campaign_links l ON l.id = t.campaign_link_id AND l.campaign_id = r.campaign_id
				WHERE t.campaign_recipient_id = :recipient
				ORDER BY t.token_type, t.campaign_link_id NULLS FIRST, t.id
				""").bind("recipient", recipientId)
				.map((row, metadata) -> new FrozenArtifact(
						row.get("token_type", String.class), row.get("campaign_link_id", UUID.class),
						copy(row.get("token_hash", byte[].class)), row.get("expires_at", Instant.class),
						row.get("target_url", String.class)))
				.all();
	}

	public Mono<Boolean> tokenExists(
			UUID recipientId, UUID linkId, String type, byte[] tokenHash, Instant expiresAt
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				SELECT count(*)::int AS total FROM tracking_tokens
				WHERE campaign_recipient_id = :recipient AND token_type = :type
				  AND token_hash = :hash AND expires_at = :expires
				  AND ((:hasLink AND campaign_link_id = :link)
				       OR (NOT :hasLink AND campaign_link_id IS NULL))
				""").bind("recipient", recipientId).bind("type", type).bind("hash", tokenHash)
				.bind("expires", expiresAt).bind("hasLink", linkId != null);
		statement = linkId == null ? statement.bindNull("link", UUID.class) : statement.bind("link", linkId);
		return statement.map((row, metadata) -> number(row, "total") == 1).one();
	}

	public Mono<Boolean> observeOpen(
			CampaignTrackingSigner.VerifiedOpen verified, byte[] tokenHash, Observation observation, Instant now
	) {
		return lockCallbackRecipient(verified.recipientId(), null, "OPEN", tokenHash, verified.expiresAt(), now)
				.flatMap(recipient -> insertEvent(recipient, null, "OPEN", observation, now)
						.flatMap(inserted -> inserted ? updateFirst(recipient.id(), "first_open_at", now).thenReturn(true)
								: Mono.just(true)))
				.defaultIfEmpty(false);
	}

	public Mono<ResolvedClick> resolveClick(
			CampaignTrackingSigner.VerifiedClick verified, byte[] tokenHash, Instant now
	) {
		return database.sql("""
				SELECT r.id AS recipient_id, r.campaign_id, l.id AS link_id, l.target_url
				FROM tracking_tokens t
				JOIN campaign_recipients r ON r.id = t.campaign_recipient_id
				JOIN campaign_links l ON l.id = t.campaign_link_id AND l.campaign_id = r.campaign_id
				WHERE r.id = :recipient AND l.id = :link AND t.token_type = 'CLICK'
				  AND t.token_hash = :hash AND t.expires_at = :expires AND t.expires_at > :now
				  AND (
				      r.status IN ('SMTP_ACCEPTED', 'OUTCOME_UNKNOWN', 'BOUNCED')
				      OR (r.status = 'CONNECTING' AND r.delivery_lease_hash IS NOT NULL
				          AND r.delivery_lease_expires_at > :now
				          AND EXISTS (
				              SELECT 1 FROM delivery_attempts active_attempt
				              WHERE active_attempt.campaign_recipient_id = r.id
				                AND active_attempt.attempt_number = r.attempt_count
				                AND active_attempt.status = 'CONNECTING'
				          ))
				  )
				""").bind("recipient", verified.recipientId()).bind("link", verified.linkId())
				.bind("hash", tokenHash).bind("expires", verified.expiresAt()).bind("now", now)
				.map((row, metadata) -> new ResolvedClick(
						row.get("recipient_id", UUID.class), row.get("campaign_id", UUID.class),
						row.get("link_id", UUID.class), row.get("target_url", String.class),
						verified.expiresAt(), copy(tokenHash)))
				.one();
	}

	public Mono<Boolean> observeClick(ResolvedClick resolved, Observation observation, Instant now) {
		return lockCallbackRecipient(
				resolved.recipientId(), resolved.linkId(), "CLICK", resolved.tokenHash(), resolved.expiresAt(), now)
				.flatMap(recipient -> insertEvent(recipient, resolved.linkId(), "CLICK", observation, now)
						.flatMap(inserted -> inserted ? updateFirst(recipient.id(), "first_click_at", now).thenReturn(true)
								: Mono.just(true)))
				.defaultIfEmpty(false);
	}

	public Mono<UnsubscribeTarget> resolveUnsubscribe(
			CampaignTrackingSigner.VerifiedUnsubscribe verified, byte[] tokenHash, Instant now
	) {
		return database.sql("""
				SELECT r.id, r.campaign_id, r.email_hmac, r.email_domain
				FROM tracking_tokens t
				JOIN campaign_recipients r ON r.id = t.campaign_recipient_id
				WHERE r.id = :recipient AND t.token_type = 'UNSUBSCRIBE'
				  AND t.campaign_link_id IS NULL AND t.token_hash = :hash
				  AND t.expires_at = :expires AND t.expires_at > :now
				""").bind("recipient", verified.recipientId()).bind("hash", tokenHash)
				.bind("expires", verified.expiresAt()).bind("now", now)
				.map((row, metadata) -> new UnsubscribeTarget(
						row.get("id", UUID.class), row.get("campaign_id", UUID.class),
						copy(row.get("email_hmac", byte[].class)), row.get("email_domain", String.class)))
				.one();
	}

	public Mono<Void> serializeUnsubscribe(byte[] emailHmac) {
		long lockKey = ByteBuffer.wrap(Arrays.copyOf(emailHmac, Long.BYTES)).getLong();
		return database.sql("SELECT pg_advisory_xact_lock(:lockKey)")
				.bind("lockKey", lockKey).map((row, metadata) -> Boolean.TRUE).one().then();
	}

	public Mono<UnsubscribeTarget> lockUnsubscribe(
			CampaignTrackingSigner.VerifiedUnsubscribe verified, byte[] tokenHash,
			byte[] expectedEmailHmac, Instant now
	) {
		return database.sql("""
				SELECT r.id, r.campaign_id, r.email_hmac, r.email_domain
				FROM tracking_tokens t
				JOIN campaign_recipients r ON r.id = t.campaign_recipient_id
				WHERE r.id = :recipient AND r.email_hmac = :emailHmac
				  AND t.token_type = 'UNSUBSCRIBE' AND t.campaign_link_id IS NULL
				  AND t.token_hash = :hash AND t.expires_at = :expires AND t.expires_at > :now
				FOR UPDATE OF r
				""").bind("recipient", verified.recipientId()).bind("emailHmac", expectedEmailHmac)
				.bind("hash", tokenHash).bind("expires", verified.expiresAt()).bind("now", now)
				.map((row, metadata) -> new UnsubscribeTarget(
						row.get("id", UUID.class), row.get("campaign_id", UUID.class),
						copy(row.get("email_hmac", byte[].class)), row.get("email_domain", String.class)))
				.one();
	}

	public Mono<Boolean> applyUnsubscribe(
			UnsubscribeTarget target, byte[] tokenHash, byte[] requestFingerprint,
			Instant now, String traceId
	) {
		return insertUnsubscribe(target, tokenHash, requestFingerprint, now)
				.flatMap(inserted -> upsertSuppression(target, now)
						.flatMap(suppressionChanged -> markUnsentUnsubscribed(target.emailHmac(), now)
								.flatMap(recipientsChanged -> inserted || suppressionChanged || recipientsChanged > 0
										? insertUnsubscribeAudit(target, requestFingerprint, traceId,
												inserted, suppressionChanged, recipientsChanged)
										: Mono.empty()))
						.thenReturn(true));
	}

	private Mono<CallbackRecipient> lockCallbackRecipient(
			UUID recipientId, UUID linkId, String type, byte[] tokenHash, Instant expiresAt, Instant now
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				SELECT r.id, r.campaign_id
				FROM tracking_tokens t
				JOIN campaign_recipients r ON r.id = t.campaign_recipient_id
				WHERE r.id = :recipient AND t.token_type = :type
				  AND t.token_hash = :hash AND t.expires_at = :expires AND t.expires_at > :now
				  AND (
				      r.status IN ('SMTP_ACCEPTED', 'OUTCOME_UNKNOWN', 'BOUNCED')
				      OR (r.status = 'CONNECTING' AND r.delivery_lease_hash IS NOT NULL
				          AND r.delivery_lease_expires_at > :now
				          AND EXISTS (
				              SELECT 1 FROM delivery_attempts active_attempt
				              WHERE active_attempt.campaign_recipient_id = r.id
				                AND active_attempt.attempt_number = r.attempt_count
				                AND active_attempt.status = 'CONNECTING'
				          ))
				  )
				  AND ((:hasLink AND t.campaign_link_id = :link)
				       OR (NOT :hasLink AND t.campaign_link_id IS NULL))
				FOR UPDATE OF r
				""").bind("recipient", recipientId).bind("type", type).bind("hash", tokenHash)
				.bind("expires", expiresAt).bind("now", now).bind("hasLink", linkId != null);
		statement = linkId == null ? statement.bindNull("link", UUID.class) : statement.bind("link", linkId);
		return statement.map((row, metadata) -> new CallbackRecipient(
				row.get("id", UUID.class), row.get("campaign_id", UUID.class))).one();
	}

	private Mono<Boolean> insertEvent(
			CallbackRecipient recipient, UUID linkId, String type, Observation observation, Instant occurredAt
	) {
		Instant minuteStart = Instant.ofEpochSecond(Math.floorDiv(occurredAt.getEpochSecond(), 60) * 60);
		Instant minuteEnd = minuteStart.plusSeconds(60);
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				INSERT INTO tracking_events (
				    campaign_id, campaign_recipient_id, campaign_link_id, event_type,
				    occurred_at, ip_hash, user_agent_summary, classification, classification_reason
				)
				SELECT :campaign, :recipient, :link, :type, :occurred, :fingerprint,
				       NULL, :classification, :reason
				WHERE NOT EXISTS (
				    SELECT 1 FROM tracking_events existing
				    WHERE existing.campaign_recipient_id = :recipient
				      AND existing.event_type = :type
				      AND existing.ip_hash = :fingerprint
				      AND existing.occurred_at >= :minuteStart AND existing.occurred_at < :minuteEnd
				      AND ((:hasLink AND existing.campaign_link_id = :link)
				           OR (NOT :hasLink AND existing.campaign_link_id IS NULL))
				)
				""").bind("campaign", recipient.campaignId()).bind("recipient", recipient.id())
				.bind("type", type).bind("occurred", occurredAt).bind("fingerprint", observation.fingerprintHash())
				.bind("classification", observation.classification()).bind("reason", observation.reason())
				.bind("minuteStart", minuteStart).bind("minuteEnd", minuteEnd).bind("hasLink", linkId != null);
		statement = linkId == null ? statement.bindNull("link", UUID.class) : statement.bind("link", linkId);
		return statement.fetch().rowsUpdated().map(rows -> rows.longValue() == 1L);
	}

	private Mono<Void> updateFirst(UUID recipientId, String column, Instant now) {
		if (!column.equals("first_open_at") && !column.equals("first_click_at")) {
			return Mono.error(new IllegalArgumentException("Unsupported campaign engagement column"));
		}
		return database.sql("UPDATE campaign_recipients SET " + column
				+ " = COALESCE(" + column + ", :now) WHERE id = :recipient")
				.bind("now", now).bind("recipient", recipientId).fetch().rowsUpdated().then();
	}

	private Mono<Boolean> insertUnsubscribe(
			UnsubscribeTarget target, byte[] tokenHash, byte[] requestFingerprint, Instant now
	) {
		return database.sql("""
				INSERT INTO unsubscribe_records (
				    id, email_hmac, campaign_id, campaign_recipient_id, token_hash,
				    requested_at, ip_hash, user_agent_summary
				) VALUES (:id, :hmac, :campaign, :recipient, :token, :now, :fingerprint, NULL)
				ON CONFLICT (email_hmac) DO NOTHING
				""").bind("id", UUID.randomUUID()).bind("hmac", target.emailHmac())
				.bind("campaign", target.campaignId()).bind("recipient", target.recipientId())
				.bind("token", tokenHash).bind("now", now).bind("fingerprint", requestFingerprint)
				.fetch().rowsUpdated().map(rows -> rows.longValue() == 1L);
	}

	private Mono<Boolean> upsertSuppression(UnsubscribeTarget target, Instant now) {
		return database.sql("""
				INSERT INTO suppression_entries (
				    id, email_hmac, email_domain, reason, source, notes, created_by, created_at, expires_at
				) VALUES (
				    :id, :hmac, :domain, 'UNSUBSCRIBED', 'PUBLIC_UNSUBSCRIBE', NULL, NULL, :now, NULL
				)
				ON CONFLICT (email_hmac) DO UPDATE SET
				    email_domain = EXCLUDED.email_domain,
				    reason = CASE WHEN suppression_entries.expires_at IS NOT NULL
				                        AND suppression_entries.expires_at <= :now
				                  THEN 'UNSUBSCRIBED' ELSE suppression_entries.reason END,
				    source = CASE WHEN suppression_entries.expires_at IS NOT NULL
				                        AND suppression_entries.expires_at <= :now
				                  THEN 'PUBLIC_UNSUBSCRIBE' ELSE suppression_entries.source END,
				    notes = CASE WHEN suppression_entries.expires_at IS NOT NULL
				                       AND suppression_entries.expires_at <= :now
				                 THEN NULL ELSE suppression_entries.notes END,
				    created_by = NULL,
				    created_at = CASE WHEN suppression_entries.expires_at IS NOT NULL
				                            AND suppression_entries.expires_at <= :now
				                      THEN :now ELSE suppression_entries.created_at END,
				    expires_at = NULL
				WHERE suppression_entries.expires_at IS NOT NULL
				  AND suppression_entries.expires_at <= :now
				""").bind("id", UUID.randomUUID()).bind("hmac", target.emailHmac())
				.bind("domain", target.emailDomain()).bind("now", now).fetch().rowsUpdated()
				.map(rows -> rows.longValue() == 1L);
	}

	private Mono<Long> markUnsentUnsubscribed(byte[] emailHmac, Instant now) {
		return database.sql("""
				UPDATE campaign_recipients
				SET status = 'UNSUBSCRIBED', exclusion_reason = 'UNSUBSCRIBED', final_failure_at = :now,
				    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL
				WHERE email_hmac = :hmac AND status IN ('QUEUED', 'TEMPORARY_FAILURE')
				""").bind("now", now).bind("hmac", emailHmac).fetch().rowsUpdated()
				.map(Number::longValue);
	}

	private Mono<Void> insertUnsubscribeAudit(
			UnsubscribeTarget target, byte[] requestFingerprint, String traceId,
			boolean unsubscribeRecorded, boolean suppressionChanged, long recipientsChanged
	) {
		return database.sql("""
				INSERT INTO audit_logs (
				    actor_user_id, action, resource_type, resource_id, ip_hash, user_agent_summary,
				    trace_id, before_summary, after_summary, result, error_type
				) VALUES (
				    NULL, 'CAMPAIGN_UNSUBSCRIBE', 'CAMPAIGN_UNSUBSCRIBE', :recipient, :fingerprint, NULL,
				    :trace, '{}'::jsonb,
				    jsonb_build_object(
				        'unsubscribeRecorded', :unsubscribeRecorded,
				        'globalSuppressionActive', true,
				        'globalSuppressionChanged', :suppressionChanged,
				        'unsentRecipientsSuppressed', :recipientsChanged
				    ), :result, NULL
				)
				""").bind("recipient", target.recipientId().toString()).bind("fingerprint", requestFingerprint)
				.bind("trace", safeTraceId(traceId)).bind("unsubscribeRecorded", unsubscribeRecorded)
				.bind("suppressionChanged", suppressionChanged).bind("recipientsChanged", recipientsChanged)
				.bind("result", AuditResult.SUCCESS.name())
				.fetch().rowsUpdated().then();
	}

	private String safeTraceId(String value) {
		if (value != null && value.matches("[A-Za-z0-9_-]{8,64}")) return value;
		return "campaign-unsubscribe";
	}

	private static int number(Row row, String field) {
		Number value = row.get(field, Number.class);
		return value == null ? 0 : value.intValue();
	}

	private static byte[] copy(byte[] value) {
		return value == null ? null : Arrays.copyOf(value, value.length);
	}

	public record PreparationState(
			UUID recipientId, UUID campaignId, UUID templateVersionId,
			String subject, String html, String text,
			boolean trackingOpens, boolean trackingClicks, boolean unsubscribeEnabled,
			int attemptNumber, Instant deliveryLeaseExpiresAt, String previousAttemptStatus,
			Boolean previousAttemptRetryable, Integer previousResponseCode,
			String previousFailureCategory
	) { }

	public record PersistedLink(UUID id, String targetUrl) { }

	public record FrozenArtifact(
			String type, UUID linkId, byte[] tokenHash, Instant expiresAt, String targetUrl
	) {
		public FrozenArtifact { tokenHash = copy(tokenHash); }
		@Override public byte[] tokenHash() { return copy(tokenHash); }
	}

	public record Observation(String classification, String reason, byte[] fingerprintHash) {
		public Observation {
			fingerprintHash = copy(fingerprintHash);
		}
		@Override public byte[] fingerprintHash() { return copy(fingerprintHash); }
	}

	public record ResolvedClick(
			UUID recipientId, UUID campaignId, UUID linkId, String targetUrl,
			Instant expiresAt, byte[] tokenHash
	) {
		public ResolvedClick { tokenHash = copy(tokenHash); }
		@Override public byte[] tokenHash() { return copy(tokenHash); }
	}

	public record UnsubscribeTarget(
			UUID recipientId, UUID campaignId, byte[] emailHmac, String emailDomain
	) {
		public UnsubscribeTarget { emailHmac = copy(emailHmac); }
		@Override public byte[] emailHmac() { return copy(emailHmac); }
	}

	private record CallbackRecipient(UUID id, UUID campaignId) { }
}
