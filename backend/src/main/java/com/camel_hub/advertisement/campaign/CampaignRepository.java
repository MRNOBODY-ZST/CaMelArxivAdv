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
				    id, exchange_name, routing_key, message_type, message_version,
				    aggregate_id, idempotency_key, payload, trace_id
				)
				VALUES (:messageId, 'mail.jobs', 'mail.personalization.generate', :type, :version,
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
				       personalization_error_code, personalization_error_message, personalized_at, created_at
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
						row.get("personalized_at", Instant.class), row.get("created_at", Instant.class))).all();
	}

	public Mono<Long> recipientCount(UUID campaignId) {
		return databaseClient.sql("SELECT count(*) AS total FROM campaign_recipients WHERE campaign_id = :id")
				.bind("id", campaignId).map((row, metadata) -> row.get("total", Long.class)).one();
	}

	private String selectSql() {
		return """
				SELECT c.id, c.name, c.purpose, c.status, c.template_id, template.name AS template_name,
				       version.version_number, c.segment_id, segment.name AS segment_name,
				       c.smtp_account_id, smtp.name AS smtp_name, c.from_name, c.from_email, c.reply_to,
				       c.generation_status, c.generation_provider, c.generation_model, c.generation_job_id,
				       c.created_at, c.updated_at,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = c.id AND r.personalization_status = 'QUEUED') AS queued_count,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = c.id AND r.personalization_status = 'RUNNING') AS running_count,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = c.id AND r.personalization_status = 'GENERATED') AS generated_count,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = c.id AND r.personalization_status = 'FAILED') AS failed_count
				FROM campaigns c
				JOIN email_templates template ON template.id = c.template_id
				JOIN email_template_versions version ON version.id = c.template_version_id
				LEFT JOIN segments segment ON segment.id = c.segment_id
				JOIN smtp_accounts smtp ON smtp.id = c.smtp_account_id
				""";
	}

	private CampaignRecord campaign(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new CampaignRecord(
				row.get("id", UUID.class), row.get("name", String.class), row.get("purpose", String.class),
				row.get("status", String.class), row.get("template_id", UUID.class),
				row.get("template_name", String.class), number(row, "version_number"),
				row.get("segment_id", UUID.class), row.get("segment_name", String.class),
				row.get("smtp_account_id", UUID.class), row.get("smtp_name", String.class),
				row.get("from_name", String.class), row.get("from_email", String.class),
				row.get("reply_to", String.class), row.get("generation_status", String.class),
				row.get("generation_provider", String.class), row.get("generation_model", String.class),
				row.get("generation_job_id", UUID.class), number(row, "queued_count"), number(row, "running_count"),
				number(row, "generated_count"), number(row, "failed_count"),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}

	private int number(io.r2dbc.spi.Row row, String field) {
		Number value = row.get(field, Number.class);
		return value == null ? 0 : value.intValue();
	}

	private <T> DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, T value, Class<T> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	public record CampaignRecord(
			UUID id, String name, String purpose, String status, UUID templateId, String templateName,
			int templateVersion, UUID segmentId, String segmentName, UUID smtpAccountId, String smtpName,
			String fromName, String fromEmail, String replyTo, String generationStatus,
			String generationProvider, String generationModel, UUID generationJobId,
			int queued, int running, int generated, int failed, Instant createdAt, Instant updatedAt
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
			String errorCode, String errorMessage, Instant personalizedAt, Instant createdAt
	) { }
}
