package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.messaging.PersonalizationCommandMessage;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class CampaignRepository {

	private final DatabaseClient databaseClient;

	public CampaignRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<CampaignRecord> list(int offset, int limit) {
		return databaseClient.sql(selectSql() + " ORDER BY c.updated_at DESC, c.id OFFSET :offset LIMIT :limit")
				.bind("offset", offset).bind("limit", limit).map(this::campaign).all();
	}

	public Mono<Long> count() {
		return databaseClient.sql("SELECT count(*) AS total FROM campaigns")
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<CampaignRecord> find(UUID id) {
		return databaseClient.sql(selectSql() + " WHERE c.id = :id")
				.bind("id", id).map(this::campaign).one();
	}

	public Mono<UUID> create(CampaignService.CampaignCommand command, UUID actorId) {
		return databaseClient.sql("""
				INSERT INTO campaigns (
				    name, purpose, template_id, template_version_id, segment_id, smtp_account_id,
				    from_name, from_email, reply_to, unsubscribe_enabled, created_by, updated_by
				)
				SELECT :name, :purpose, template.id, version.id, segment.id, smtp.id,
				       smtp.default_from_name, smtp.from_email, smtp.reply_to, true, :actorId, :actorId
				FROM email_templates template
				JOIN email_template_versions version
				  ON version.template_id = template.id AND version.version_number = template.current_version
				JOIN segments segment ON segment.id = :segmentId
				JOIN smtp_accounts smtp ON smtp.id = :smtpId
				WHERE template.id = :templateId AND template.deleted_at IS NULL
				  AND template.status = 'ACTIVE' AND smtp.enabled = true
				RETURNING id
				""").bind("name", command.name()).bind("purpose", command.purpose())
				.bind("templateId", command.templateId()).bind("segmentId", command.segmentId())
				.bind("smtpId", command.smtpAccountId()).bind("actorId", actorId)
				.map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	public Mono<GenerationContext> generationContext(UUID campaignId) {
		return databaseClient.sql("""
				SELECT c.id, c.purpose, c.segment_id, c.status, c.generation_status,
				       v.subject_template, v.html_content, v.text_content
				FROM campaigns c JOIN email_template_versions v ON v.id = c.template_version_id
				WHERE c.id = :id
				""").bind("id", campaignId).map((row, metadata) -> new GenerationContext(
				row.get("id", UUID.class), row.get("purpose", String.class), row.get("segment_id", UUID.class),
				row.get("status", String.class), row.get("generation_status", String.class),
				row.get("subject_template", String.class), row.get("html_content", String.class),
				row.get("text_content", String.class))).one();
	}

	public Mono<Boolean> prepareGeneration(UUID campaignId, UUID jobId, String provider, String model) {
		return databaseClient.sql("""
				UPDATE campaigns SET generation_status = 'QUEUED', generation_provider = :provider,
				    generation_model = :model, generation_job_id = :jobId,
				    generation_requested_at = now(), generation_completed_at = NULL,
				    generation_error_summary = NULL, updated_at = now()
				WHERE id = :id AND status = 'DRAFT'
				  AND generation_status NOT IN ('QUEUED', 'RUNNING')
				""").bind("provider", provider).bind("model", model).bind("jobId", jobId)
				.bind("id", campaignId).fetch().rowsUpdated().map(rows -> rows == 1);
	}

	public Mono<QueuedTarget> queueRecipient(UUID campaignId, SegmentRepository.CampaignCandidate candidate) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO campaign_recipients (
				    campaign_id, contact_id, paper_id, author_id, email_ciphertext, email_nonce,
				    email_hmac, email_domain, author_name_snapshot, paper_title_snapshot,
				    category_snapshot, organization_snapshot, confidence, personalization_status, queued_at
				)
				VALUES (
				    :campaignId, :contactId, :paperId, :authorId, :ciphertext, :nonce,
				    :hmac, :domain, :authorName, :paperTitle, :category, :organization,
				    :confidence, 'QUEUED', now()
				)
				ON CONFLICT (campaign_id, email_hmac) DO UPDATE SET
				    personalization_status = 'QUEUED', personalization_error_code = NULL,
				    personalization_error_message = NULL, queued_at = now()
				WHERE campaign_recipients.personalization_status IN ('PENDING', 'FAILED')
				RETURNING id
				""").bind("campaignId", campaignId).bind("contactId", candidate.contactId())
				.bind("paperId", candidate.paperId()).bind("ciphertext", candidate.emailCiphertext())
				.bind("nonce", candidate.emailNonce()).bind("hmac", candidate.emailHmac())
				.bind("domain", candidate.emailDomain()).bind("paperTitle", candidate.paperTitle())
				.bind("confidence", candidate.confidence());
		statement = bindNullable(statement, "authorId", candidate.authorId(), UUID.class);
		statement = bindNullable(statement, "authorName", candidate.authorName(), String.class);
		statement = bindNullable(statement, "category", candidate.primaryCategory(), String.class);
		statement = bindNullable(statement, "organization", candidate.organization(), String.class);
		return statement.map((row, metadata) -> new QueuedTarget(
				row.get("id", UUID.class), candidate.authorName(), candidate.paperTitle(),
				candidate.abstractText(), candidate.arxivId(), candidate.primaryCategory(),
				candidate.organization())).one();
	}

	public Mono<Void> insertOutbox(PersonalizationCommandMessage message, String payload) {
		return databaseClient.sql("""
				INSERT INTO outbox_messages (
				    id, topic_name, routing_key, message_type, message_version,
				    aggregate_id, idempotency_key, payload, trace_id
				)
				VALUES (:messageId, 'camel.mail.personalization.jobs.v1', 'mail.personalization.generate', :type, :version,
				        :campaignId, :idempotencyKey, CAST(:payload AS jsonb), :traceId)
				""").bind("messageId", message.messageId()).bind("type", message.type())
				.bind("version", message.version()).bind("campaignId", message.campaignId())
				.bind("idempotencyKey", message.idempotencyKey()).bind("payload", payload)
				.bind("traceId", message.traceId()).fetch().rowsUpdated().then();
	}

	public Flux<RecipientRecord> recipients(UUID campaignId, int offset, int limit) {
		return databaseClient.sql("""
				SELECT id, author_name_snapshot, paper_title_snapshot, category_snapshot,
				       organization_snapshot, personalization_status, rendered_subject,
				       rendered_html, rendered_text, personalization_rationale,
				       personalization_error_code, personalization_error_message, personalized_at, created_at,
				       (attempt_count > 0 OR EXISTS (
				           SELECT 1 FROM tracking_tokens token
				           WHERE token.campaign_recipient_id = campaign_recipients.id
				       )) AS tracking_artifacts_frozen
				FROM campaign_recipients WHERE campaign_id = :campaignId
				ORDER BY created_at, id OFFSET :offset LIMIT :limit
				""").bind("campaignId", campaignId).bind("offset", offset).bind("limit", limit)
				.map((row, metadata) -> new RecipientRecord(
						row.get("id", UUID.class), row.get("author_name_snapshot", String.class),
						row.get("paper_title_snapshot", String.class), row.get("category_snapshot", String.class),
						row.get("organization_snapshot", String.class), row.get("personalization_status", String.class),
						row.get("rendered_subject", String.class), row.get("rendered_html", String.class),
						row.get("rendered_text", String.class), row.get("personalization_rationale", String.class),
						row.get("personalization_error_code", String.class),
						row.get("personalization_error_message", String.class),
						row.get("personalized_at", Instant.class), row.get("created_at", Instant.class),
						Boolean.TRUE.equals(row.get("tracking_artifacts_frozen", Boolean.class)))).all();
	}

	public Mono<Long> recipientCount(UUID campaignId) {
		return databaseClient.sql("SELECT count(*) AS total FROM campaign_recipients WHERE campaign_id = :id")
				.bind("id", campaignId).map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<Boolean> markPersonalizationResultProcessed(UUID messageId, String idempotencyKey) {
		return databaseClient.sql("""
				INSERT INTO processed_messages (message_id, consumer_name, idempotency_key, result)
				VALUES (:messageId, 'personalization-result', :idempotencyKey, 'SUCCEEDED')
				ON CONFLICT DO NOTHING RETURNING message_id
				""").bind("messageId", messageId).bind("idempotencyKey", idempotencyKey)
				.map((row, metadata) -> true).one().defaultIfEmpty(false);
	}

	public Mono<ResultContext> resultContext(UUID campaignId, UUID recipientId, UUID jobId) {
		return databaseClient.sql("""
				SELECT c.from_name, c.reply_to
				FROM campaigns c JOIN campaign_recipients r ON r.campaign_id = c.id
				WHERE c.id = :campaignId AND r.id = :recipientId AND c.generation_job_id = :jobId
				""").bind("campaignId", campaignId).bind("recipientId", recipientId).bind("jobId", jobId)
				.map((row, metadata) -> new ResultContext(
						row.get("from_name", String.class), row.get("reply_to", String.class))).one();
	}

	public Mono<Boolean> storeGenerated(
			UUID campaignId, UUID recipientId, UUID jobId, GeneratedDraft draft
	) {
		return databaseClient.sql("""
				UPDATE campaign_recipients r SET
				    personalization_status = 'GENERATED', rendered_subject = :subject,
				    rendered_html = :html, rendered_text = :text,
				    personalization_rationale = :rationale,
				    personalization_error_code = NULL, personalization_error_message = NULL,
				    personalization_attempts = personalization_attempts + 1, personalized_at = now()
				FROM campaigns c
				WHERE r.id = :recipientId AND r.campaign_id = :campaignId
				  AND c.id = r.campaign_id AND c.generation_job_id = :jobId
				  AND r.personalization_status <> 'GENERATED'
				""").bind("subject", draft.subject()).bind("html", draft.html()).bind("text", draft.text())
				.bind("rationale", draft.rationale()).bind("recipientId", recipientId)
				.bind("campaignId", campaignId).bind("jobId", jobId)
				.fetch().rowsUpdated().map(rows -> rows == 1);
	}

	public Mono<Boolean> storeFailed(
			UUID campaignId, UUID recipientId, UUID jobId, String errorCode, String errorMessage
	) {
		return databaseClient.sql("""
				UPDATE campaign_recipients r SET
				    personalization_status = 'FAILED', personalization_error_code = :errorCode,
				    personalization_error_message = :errorMessage,
				    personalization_attempts = personalization_attempts + 1, personalized_at = NULL
				FROM campaigns c
				WHERE r.id = :recipientId AND r.campaign_id = :campaignId
				  AND c.id = r.campaign_id AND c.generation_job_id = :jobId
				  AND r.personalization_status <> 'GENERATED'
				""").bind("errorCode", errorCode).bind("errorMessage", errorMessage)
				.bind("recipientId", recipientId).bind("campaignId", campaignId).bind("jobId", jobId)
				.fetch().rowsUpdated().map(rows -> rows == 1);
	}

	public Mono<Void> refreshGenerationState(UUID campaignId, UUID jobId) {
		return databaseClient.sql("""
				UPDATE campaigns campaign SET
				    generation_status = CASE
				      WHEN state.pending_count > 0 THEN 'RUNNING'
				      WHEN state.failed_count = 0 THEN 'COMPLETED'
				      WHEN state.generated_count = 0 THEN 'FAILED'
				      ELSE 'PARTIALLY_FAILED'
				    END,
				    generation_completed_at = CASE WHEN state.pending_count = 0 THEN now() ELSE NULL END,
				    generation_error_summary = CASE
				      WHEN state.failed_count > 0 THEN state.failed_count || ' recipient personalization(s) failed'
				      ELSE NULL
				    END,
				    updated_at = now()
				FROM (
				  SELECT count(*) FILTER (WHERE personalization_status IN ('PENDING', 'QUEUED', 'RUNNING')) AS pending_count,
				         count(*) FILTER (WHERE personalization_status = 'GENERATED') AS generated_count,
				         count(*) FILTER (WHERE personalization_status = 'FAILED') AS failed_count
				  FROM campaign_recipients WHERE campaign_id = :campaignId
				) state
				WHERE campaign.id = :campaignId AND campaign.generation_job_id = :jobId
				""").bind("campaignId", campaignId).bind("jobId", jobId).fetch().rowsUpdated().then();
	}

	private String selectSql() {
		return """
				SELECT c.id, c.name, c.purpose, c.status, c.template_id, template.name AS template_name,
				       version.version_number, c.segment_id, segment.name AS segment_name,
				       c.smtp_account_id, smtp.name AS smtp_name, c.mailbox_account_id,
				       c.from_name, c.from_email, c.reply_to,
				       c.tracking_opens_enabled, c.tracking_clicks_enabled, c.lock_version,
				       c.submitted_for_review_at, c.approved_at, c.approved_by,
				       c.rejected_at, c.rejected_by, c.rejection_reason, c.scheduled_at,
				       c.started_at, c.completed_at, c.canceled_at, c.status_changed_at, c.status_changed_by,
				       c.generation_status, c.generation_provider, c.generation_model, c.generation_job_id,
				       c.created_at, c.updated_at,
				       recipient_counts.queued_count, recipient_counts.running_count,
				       recipient_counts.generated_count, recipient_counts.failed_count,
				       recipient_counts.delivery_queued, recipient_counts.delivery_connecting,
				       recipient_counts.delivery_smtp_accepted, recipient_counts.delivery_temporary_failure,
				       recipient_counts.delivery_permanent_failure, recipient_counts.delivery_bounced,
				       recipient_counts.delivery_suppressed, recipient_counts.delivery_unsubscribed,
				       recipient_counts.delivery_canceled, recipient_counts.delivery_outcome_unknown
				FROM campaigns c
				JOIN email_templates template ON template.id = c.template_id
				JOIN email_template_versions version ON version.id = c.template_version_id
				LEFT JOIN segments segment ON segment.id = c.segment_id
				JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				LEFT JOIN LATERAL (
				  SELECT count(*) FILTER (WHERE r.personalization_status = 'QUEUED') AS queued_count,
				         count(*) FILTER (WHERE r.personalization_status = 'RUNNING') AS running_count,
				         count(*) FILTER (WHERE r.personalization_status = 'GENERATED') AS generated_count,
				         count(*) FILTER (WHERE r.personalization_status = 'FAILED') AS failed_count,
				         count(*) FILTER (WHERE r.status = 'QUEUED') AS delivery_queued,
				         count(*) FILTER (WHERE r.status = 'CONNECTING') AS delivery_connecting,
				         count(*) FILTER (WHERE r.status = 'SMTP_ACCEPTED') AS delivery_smtp_accepted,
				         count(*) FILTER (WHERE r.status = 'TEMPORARY_FAILURE') AS delivery_temporary_failure,
				         count(*) FILTER (WHERE r.status = 'PERMANENT_FAILURE') AS delivery_permanent_failure,
				         count(*) FILTER (WHERE r.status = 'BOUNCED') AS delivery_bounced,
				         count(*) FILTER (WHERE r.status = 'SUPPRESSED') AS delivery_suppressed,
				         count(*) FILTER (WHERE r.status = 'UNSUBSCRIBED') AS delivery_unsubscribed,
				         count(*) FILTER (WHERE r.status = 'CANCELED') AS delivery_canceled,
				         count(*) FILTER (WHERE r.status = 'OUTCOME_UNKNOWN') AS delivery_outcome_unknown
				  FROM campaign_recipients r WHERE r.campaign_id = c.id
				) recipient_counts ON true
				""";
	}

	private CampaignRecord campaign(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new CampaignRecord(
				row.get("id", UUID.class), row.get("name", String.class), row.get("purpose", String.class),
				row.get("status", String.class), row.get("template_id", UUID.class),
				row.get("template_name", String.class), number(row, "version_number"),
				row.get("segment_id", UUID.class), row.get("segment_name", String.class),
				row.get("smtp_account_id", UUID.class), row.get("smtp_name", String.class),
				row.get("mailbox_account_id", UUID.class), row.get("from_name", String.class), row.get("from_email", String.class),
				row.get("reply_to", String.class), row.get("generation_status", String.class),
				row.get("generation_provider", String.class), row.get("generation_model", String.class),
				row.get("generation_job_id", UUID.class), number(row, "queued_count"), number(row, "running_count"),
				number(row, "generated_count"), number(row, "failed_count"), number(row, "delivery_queued"),
				number(row, "delivery_connecting"), number(row, "delivery_smtp_accepted"),
				number(row, "delivery_temporary_failure"), number(row, "delivery_permanent_failure"),
				number(row, "delivery_bounced"), number(row, "delivery_suppressed"),
				number(row, "delivery_unsubscribed"), number(row, "delivery_canceled"),
				number(row, "delivery_outcome_unknown"), longNumber(row, "lock_version"),
				row.get("submitted_for_review_at", Instant.class), row.get("approved_at", Instant.class),
				row.get("approved_by", UUID.class), row.get("rejected_at", Instant.class),
				row.get("rejected_by", UUID.class), row.get("rejection_reason", String.class),
				row.get("scheduled_at", Instant.class), row.get("started_at", Instant.class),
				row.get("completed_at", Instant.class), row.get("canceled_at", Instant.class),
				row.get("status_changed_at", Instant.class), row.get("status_changed_by", UUID.class),
				bool(row, "tracking_opens_enabled"), bool(row, "tracking_clicks_enabled"),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}

	private int number(io.r2dbc.spi.Row row, String field) {
		Number value = row.get(field, Number.class);
		return value == null ? 0 : value.intValue();
	}

	private long longNumber(io.r2dbc.spi.Row row, String field) {
		Number value = row.get(field, Number.class);
		return value == null ? 0 : value.longValue();
	}

	private boolean bool(io.r2dbc.spi.Row row, String field) {
		Boolean value = row.get(field, Boolean.class);
		return value != null && value;
	}

	private <T> DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, T value, Class<T> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	public record CampaignRecord(
			UUID id, String name, String purpose, String status, UUID templateId, String templateName,
			int templateVersion, UUID segmentId, String segmentName, UUID smtpAccountId, String smtpName,
			UUID mailboxAccountId, String fromName, String fromEmail, String replyTo, String generationStatus,
			String generationProvider, String generationModel, UUID generationJobId,
			int queued, int running, int generated, int failed, int deliveryQueued, int deliveryConnecting,
			int deliverySmtpAccepted, int deliveryTemporaryFailure, int deliveryPermanentFailure,
			int deliveryBounced, int deliverySuppressed, int deliveryUnsubscribed, int deliveryCanceled,
			int deliveryOutcomeUnknown, long lockVersion, Instant submittedForReviewAt, Instant approvedAt,
			UUID approvedBy, Instant rejectedAt, UUID rejectedBy, String rejectionReason, Instant scheduledAt,
			Instant startedAt, Instant completedAt, Instant canceledAt, Instant statusChangedAt,
			UUID statusChangedBy, boolean trackingOpensEnabled, boolean trackingClicksEnabled,
			Instant createdAt, Instant updatedAt
	) { }

	public record GenerationContext(
			UUID campaignId, String purpose, UUID segmentId, String status, String generationStatus,
			String templateSubject, String templateHtml, String templateText
	) { }

	public record QueuedTarget(
			UUID recipientId, String authorName, String paperTitle, String abstractText, String arxivId,
			String primaryCategory, String organization
	) { }

	public record RecipientRecord(
			UUID id, String authorName, String paperTitle, String category, String organization,
			String personalizationStatus, String subject, String html, String text, String rationale,
			String errorCode, String errorMessage, Instant personalizedAt, Instant createdAt,
			boolean trackingArtifactsFrozen
	) { }

	public record ResultContext(String fromName, String replyTo) { }

	public record GeneratedDraft(String subject, String html, String text, String rationale) { }
}
