package com.camel_hub.advertisement.campaign;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class CampaignRecipientContentRepository {
	private static final String PRISTINE_RECIPIENT = """
			%s.personalization_status = 'GENERATED'
			  AND %s.status = 'QUEUED'
			  AND %s.attempt_count = 0
			  AND %s.delivery_lease_hash IS NULL
			  AND %s.delivery_lease_expires_at IS NULL
			  AND %s.rfc_message_id IS NULL
			  AND %s.first_attempt_at IS NULL
			  AND %s.smtp_accepted_at IS NULL
			  AND %s.final_failure_at IS NULL
			  AND %s.first_open_at IS NULL
			  AND %s.first_click_at IS NULL
			  AND %s.replied_at IS NULL
			  AND %s.outcome_unknown_at IS NULL
			  AND %s.outcome_unknown_reason IS NULL
			  AND NOT EXISTS (SELECT 1 FROM delivery_attempts attempt
			      WHERE attempt.campaign_recipient_id = %s.id)
			  AND NOT EXISTS (SELECT 1 FROM tracking_tokens token
			      WHERE token.campaign_recipient_id = %s.id)
			  AND NOT EXISTS (SELECT 1 FROM tracking_events event
			      WHERE event.campaign_recipient_id = %s.id)
			  AND NOT EXISTS (SELECT 1 FROM unsubscribe_records unsubscribe
			      WHERE unsubscribe.campaign_recipient_id = %s.id)
			  AND NOT EXISTS (SELECT 1 FROM campaign_safety_messages safety
			      WHERE safety.campaign_recipient_id = %s.id)
			  AND NOT EXISTS (SELECT 1 FROM mailbox_inbound_events inbound
			      WHERE inbound.campaign_recipient_id = %s.id)
			""".formatted(java.util.Collections.nCopies(20, "r").toArray());

	private final DatabaseClient databaseClient;

	public CampaignRecipientContentRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Mono<CampaignLock> lockCampaign(UUID campaignId) {
		return databaseClient.sql("""
				SELECT id, status, generation_status, lock_version, from_name, reply_to
				FROM campaigns WHERE id = :campaignId
				FOR UPDATE
				""").bind("campaignId", campaignId).map((row, metadata) -> new CampaignLock(
				row.get("id", UUID.class), row.get("status", String.class),
				row.get("generation_status", String.class), number(row.get("lock_version")),
				row.get("from_name", String.class), row.get("reply_to", String.class))).one();
	}

	public Mono<RecipientLock> lockRecipient(UUID campaignId, UUID recipientId) {
		return databaseClient.sql("""
				SELECT r.id, r.rendered_subject, r.rendered_html, r.rendered_text,
				       r.personalization_rationale,
				       (%s) AS pristine
				FROM campaign_recipients r
				WHERE r.id = :recipientId AND r.campaign_id = :campaignId
				FOR UPDATE OF r
				""".formatted(PRISTINE_RECIPIENT)).bind("recipientId", recipientId).bind("campaignId", campaignId)
				.map((row, metadata) -> new RecipientLock(
						row.get("id", UUID.class), row.get("rendered_subject", String.class),
						row.get("rendered_html", String.class), row.get("rendered_text", String.class),
						row.get("personalization_rationale", String.class),
						Boolean.TRUE.equals(row.get("pristine", Boolean.class)))).one();
	}

	public Mono<EditedRecipient> updateRecipient(
			UUID campaignId, UUID recipientId, String subject, String html, String text, String rationale
	) {
		return databaseClient.sql("""
				UPDATE campaign_recipients r SET
				    rendered_subject = :subject, rendered_html = :html, rendered_text = :text,
				    personalization_rationale = :rationale
				WHERE r.id = :recipientId AND r.campaign_id = :campaignId
				  AND (%s)
				RETURNING r.id, r.rendered_subject, r.rendered_html, r.rendered_text,
				          r.personalization_rationale
				""".formatted(PRISTINE_RECIPIENT)).bind("subject", subject).bind("html", html).bind("text", text)
				.bind("rationale", rationale).bind("recipientId", recipientId).bind("campaignId", campaignId)
				.map((row, metadata) -> new EditedRecipient(
						row.get("id", UUID.class), row.get("rendered_subject", String.class),
						row.get("rendered_html", String.class), row.get("rendered_text", String.class),
						row.get("personalization_rationale", String.class))).one();
	}

	public Mono<CampaignEdit> invalidateReview(
			UUID campaignId, long expectedLockVersion, UUID actorId
	) {
		return databaseClient.sql("""
				UPDATE campaigns SET
				    submitted_for_review_at = NULL, approved_at = NULL, approved_by = NULL,
				    rejected_at = NULL, rejected_by = NULL, rejection_reason = NULL,
				    scheduled_at = NULL, started_at = NULL, completed_at = NULL, canceled_at = NULL,
				    review_preflight_digest = NULL, review_preflight_at = NULL,
				    status_changed_at = now(), status_changed_by = :actorId,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				WHERE id = :campaignId AND status = 'DRAFT' AND lock_version = :expectedLockVersion
				  AND generation_status IN ('COMPLETED', 'PARTIALLY_FAILED', 'FAILED')
				RETURNING id, lock_version, updated_at
				""").bind("actorId", actorId).bind("campaignId", campaignId)
				.bind("expectedLockVersion", expectedLockVersion)
				.map((row, metadata) -> new CampaignEdit(
						row.get("id", UUID.class), number(row.get("lock_version")),
						row.get("updated_at", Instant.class))).one();
	}

	private long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

	public record CampaignLock(
			UUID id, String status, String generationStatus, long lockVersion, String fromName, String replyTo
	) { }

	public record RecipientLock(
			UUID id, String subject, String html, String text, String rationale, boolean pristine
	) { }

	public record EditedRecipient(UUID id, String subject, String html, String text, String rationale) { }

	public record CampaignEdit(UUID id, long lockVersion, Instant updatedAt) { }
}
