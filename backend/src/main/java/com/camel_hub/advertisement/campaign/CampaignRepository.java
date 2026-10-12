package com.camel_hub.advertisement.campaign;


import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.MigrationReference;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.RecipientTransfer;

import com.camel_hub.advertisement.messaging.PersonalizationCommandMessage;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CampaignRepository {

	private final DatabaseClient databaseClient;
    private final CampaignSelectionInventory inventory;

	public CampaignRepository(DatabaseClient databaseClient) {
		this(databaseClient, CampaignSelectionInventory.unavailable());
	}

    public CampaignRepository(DatabaseClient databaseClient, CampaignSelectionInventory inventory) {
        this.databaseClient = databaseClient;
        this.inventory = inventory;
	}

	/** Explicit immutable version and exact manifest; no candidate selection, workflow, or outbox. */
	public Mono<UUID> createPinnedDraft(UUID actorId,
			com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationRepository.MigrationSnapshot snapshot) {
		UUID target=UUID.randomUUID(), segment=UUID.randomUUID();
		UUID[] papers=snapshot.recipients().stream().map(r->r.paperId()).distinct().sorted().toArray(UUID[]::new);
		return databaseClient.sql("INSERT INTO segments(id,name,description,created_by) VALUES (:id,:name,'Exact frozen migration paper manifest',:actor)")
				.bind("id",segment).bind("name","Template migration "+target).bind("actor",actorId).fetch().rowsUpdated()
				.then(databaseClient.sql("INSERT INTO segment_rules(segment_id,rule_order,field_name,operator,value_data) VALUES (:segment,1,'paper_id','IN',to_jsonb(CAST(:papers AS uuid[])))")
						.bind("segment",segment).bind("papers",papers).fetch().rowsUpdated())
				.then(databaseClient.sql("""
					INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,segment_id,smtp_account_id,mailbox_account_id,
					    from_name,from_email,reply_to,unsubscribe_enabled,created_by,updated_by)
					SELECT :id,:name,c.purpose,:template,:version,:segment,c.smtp_account_id,c.mailbox_account_id,
					    c.from_name,c.from_email,c.reply_to,true,:actor,:actor FROM campaigns c WHERE c.id=:source AND c.lock_version=:lock
					RETURNING id
					""").bind("id",target).bind("name","Template migration "+target).bind("template",snapshot.template().id())
						.bind("version",snapshot.template().versionId()).bind("segment",segment).bind("actor",actorId)
						.bind("source",snapshot.campaign().id()).bind("lock",snapshot.campaign().lockVersion())
						.map((r,m)->r.get("id",UUID.class)).one())
				.switchIfEmpty(Mono.error(new CampaignConflictException("Locked migration source changed")));
	}

	/** Only encrypted delivery fields/confidence are copied from the caller-locked original row. */
	public Mono<FrozenProductionRecipient> copyFrozenRecipient(UUID targetId, FrozenProductionRecipient source) {
		var t=source.target();
		var statement=databaseClient.sql("""
				INSERT INTO campaign_recipients(campaign_id,contact_id,paper_id,author_id,email_ciphertext,email_nonce,email_hmac,email_domain,
				    confidence,author_name_snapshot,paper_title_snapshot,paper_abstract_snapshot,arxiv_id_snapshot,category_snapshot,organization_snapshot,personalization_status,queued_at)
				SELECT :target,:contact,:paper,:author,r.email_ciphertext,r.email_nonce,r.email_hmac,r.email_domain,r.confidence,
				    :name,:title,:abstract,:arxiv,:category,:organization,'PENDING',now()
				FROM campaign_recipients r WHERE r.id=:source AND r.contact_id=:contact AND r.paper_id=:paper AND r.email_hmac=:hmac
				    AND r.status='QUEUED' AND r.attempt_count=0
				RETURNING id
				""").bind("target",targetId).bind("contact",source.contactId()).bind("paper",source.paperId())
				.bind("source",t.recipientId()).bind("hmac",source.emailHmac()).bind("title",t.paperTitle()).bind("abstract",t.abstractText()).bind("arxiv",t.arxivId());
		statement=bindNullable(statement,"author",source.authorId(),UUID.class);
		statement=bindNullable(statement,"name",t.authorName(),String.class);
		statement=bindNullable(statement,"category",t.primaryCategory(),String.class);
		statement=bindNullable(statement,"organization",t.organization(),String.class);
		return statement.map((r,m)->new FrozenProductionRecipient(source.contactId(),source.paperId(),source.authorId(),source.emailHmac(),"PENDING",0,true,
				new QueuedTarget(r.get("id",UUID.class),t.authorName(),t.paperTitle(),t.abstractText(),t.arxivId(),t.primaryCategory(),t.organization()))).one()
				.switchIfEmpty(Mono.error(new CampaignConflictException("Exact frozen recipient changed")));
	}

	public Flux<CampaignRecord> list(int offset, int limit) {
		return inventory.bind(databaseClient.sql(selectSql() + " ORDER BY c.updated_at DESC, c.id OFFSET :offset LIMIT :limit")
				.bind("offset", offset).bind("limit", limit)).map(this::campaign).all();
	}

	public Mono<Long> count() {
		return databaseClient.sql("SELECT count(*) AS total FROM campaigns")
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<CampaignRecord> find(UUID id) {
		return inventory.bind(databaseClient.sql(selectSql() + " WHERE c.id = :id")
				.bind("id", id)).map(this::campaign).one();
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
				SELECT c.id, c.purpose, c.segment_id, c.status, c.generation_status, c.personalization_paper_ids,
				       c.personalization_recipient_limit, c.recipient_policy, c.paper_contact_ids, c.paper_contact_recipient_count,
				       v.subject_template, v.html_content, v.text_content
				FROM campaigns c JOIN email_template_versions v ON v.id = c.template_version_id
				WHERE c.id = :id
				FOR UPDATE OF c
				""").bind("id", campaignId).map((row, metadata) -> new GenerationContext(
				row.get("id", UUID.class), row.get("purpose", String.class), row.get("segment_id", UUID.class),
				row.get("status", String.class), row.get("generation_status", String.class),
				row.get("subject_template", String.class), row.get("html_content", String.class),
				row.get("text_content", String.class), selectedPapers(row.get("personalization_paper_ids", UUID[].class)),
				row.get("personalization_recipient_limit", Integer.class), row.get("recipient_policy", String.class),
				selectedPapers(row.get("paper_contact_ids", UUID[].class)), row.get("paper_contact_recipient_count", Integer.class))).one();
	}

	/** Serialize selection plus membership writes across paper-contact campaigns. */
	public Mono<Void> lockPaperContactSelection() {
		return databaseClient.sql("SELECT pg_advisory_xact_lock(20260923, 1000)")
				.fetch().all().then();
	}

	public Mono<Boolean> preparePaperContactGeneration(UUID campaignId, UUID jobId, String provider,
			String model, List<UUID> paperIds, int recipientCount) {
		return databaseClient.sql("""
				UPDATE campaigns SET recipient_policy = 'PAPER_CONTACT', paper_contact_ids = :papers,
				  paper_contact_recipient_count = coalesce(paper_contact_recipient_count, :recipientCount),
				  generation_status = 'QUEUED', generation_provider = :provider, generation_model = :model,
				  generation_job_id = :job, generation_requested_at = now(), generation_completed_at = NULL,
				  generation_error_summary = NULL, updated_at = now()
				WHERE id = :id AND status = 'DRAFT' AND generation_status NOT IN ('QUEUED', 'RUNNING')
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_campaign_id=:id)
				  AND personalization_paper_ids IS NULL AND personalization_recipient_limit IS NULL
				""").bind("papers", paperIds.toArray(UUID[]::new)).bind("provider", provider).bind("model", model)
				.bind("job", jobId).bind("id", campaignId).bind("recipientCount", recipientCount)
				.fetch().rowsUpdated().map(rows -> rows == 1).as(work -> lockedCampaignMutation(campaignId, work));
	}

	public Mono<Boolean> prepareGeneration(UUID campaignId, UUID jobId, String provider, String model) {
		return prepareGeneration(campaignId, jobId, provider, model, null);
	}

	public Mono<Boolean> prepareGeneration(
			UUID campaignId, UUID jobId, String provider, String model, List<UUID> paperIds
	) {
		return prepareGeneration(campaignId, jobId, provider, model, paperIds, null);
	}

	public Mono<Boolean> prepareGeneration(
			UUID campaignId, UUID jobId, String provider, String model, List<UUID> paperIds, Integer recipientLimit
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				UPDATE campaigns SET generation_status = 'QUEUED', generation_provider = :provider,
				    generation_model = :model, generation_job_id = :jobId,
				    personalization_paper_ids = coalesce(personalization_paper_ids, :paperIds),
				    personalization_recipient_limit = coalesce(personalization_recipient_limit, :recipientLimit),
				    generation_requested_at = now(), generation_completed_at = NULL,
				    generation_error_summary = NULL, updated_at = now()
				WHERE id = :id AND status = 'DRAFT'
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_campaign_id=:id)
				  AND generation_status NOT IN ('QUEUED', 'RUNNING')
				""").bind("provider", provider).bind("model", model).bind("jobId", jobId).bind("id", campaignId);
		statement = bindNullable(statement, "paperIds", paperIds == null ? null : paperIds.toArray(UUID[]::new), UUID[].class);
		statement = bindNullable(statement, "recipientLimit", recipientLimit, Integer.class);
		return statement.fetch().rowsUpdated().map(rows -> rows == 1).as(work -> lockedCampaignMutation(campaignId, work));
	}

	private List<UUID> selectedPapers(UUID[] paperIds) {
		return paperIds == null ? null : List.of(paperIds);
	}

	public Mono<QueuedTarget> queueRecipient(UUID campaignId, SegmentRepository.CampaignCandidate candidate) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO campaign_recipients (
				    campaign_id, contact_id, paper_id, author_id, email_ciphertext, email_nonce,
				    email_hmac, email_domain, author_name_snapshot, paper_title_snapshot,
				    category_snapshot, organization_snapshot, confidence, personalization_status, queued_at,
				    paper_abstract_snapshot, arxiv_id_snapshot
				)
				SELECT
				    :campaignId, :contactId, :paperId, :authorId, :ciphertext, :nonce,
				    :hmac, :domain, :authorName, :paperTitle, :category, :organization,
				    :confidence, 'QUEUED', now(), :abstract, :arxivId
				WHERE NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t
				  JOIN campaign_recipients s ON s.id=t.source_recipient_id WHERE s.email_hmac=:hmac)
				ON CONFLICT (campaign_id, email_hmac) DO UPDATE SET
				    personalization_status = 'QUEUED', personalization_error_code = NULL,
				    personalization_error_message = NULL, queued_at = now()
				WHERE campaign_recipients.personalization_status IN ('PENDING', 'FAILED')
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=campaign_recipients.id)
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
		statement = bindNullable(statement, "abstract", candidate.abstractText(), String.class);
		statement = bindNullable(statement, "arxivId", candidate.arxivId(), String.class);
		return statement.map((row, metadata) -> new QueuedTarget(
				row.get("id", UUID.class), candidate.authorName(), candidate.paperTitle(),
				candidate.abstractText(), candidate.arxivId(), candidate.primaryCategory(),
				candidate.organization())).one().as(work -> lockedCampaignMutation(campaignId, work));
	}

	/** Frozen production retries reuse snapshots while rechecking live suppression and source evidence. */
	public Flux<FrozenProductionRecipient> frozenProductionRecipients(
			UUID campaignId, Instant cooldownCutoff, String[] excludedHmacs
	) {
		return databaseClient.sql("""
				SELECT r.id, r.contact_id, r.paper_id, r.author_id, r.email_hmac,
				       r.personalization_status, r.attempt_count,
				       author_name_snapshot, paper_title_snapshot, paper_abstract_snapshot, arxiv_id_snapshot,
				       category_snapshot, organization_snapshot,
				       c.id IS NOT NULL AND c.deleted_at IS NULL AND c.syntax_valid
				         AND NOT c.example_address AND c.suppression_status = 'ACTIVE'
				         AND p.id IS NOT NULL
				         AND ((campaign.recipient_policy = 'PAPER_CONTACT' AND (r.author_name_snapshot IS NULL OR (
				           relation.id IS NOT NULL AND evidence.paper_author_id = relation.id
				           AND (evidence.confidence = 'HIGH' OR (evidence.human_verified AND evidence.verification_status = 'CONFIRMED'))))) OR (
				           campaign.recipient_policy <> 'PAPER_CONTACT' AND
				           r.confidence IN ('HIGH', 'MEDIUM') AND relation.id IS NOT NULL
				           AND evidence.paper_author_id = relation.id
				           AND (evidence.confidence = 'HIGH' OR (evidence.confidence = 'MEDIUM'
				              AND evidence.human_verified AND evidence.verification_status = 'CONFIRMED'))))
				         AND evidence.verification_status <> 'REJECTED'
				         AND EXISTS (SELECT 1 FROM extraction_evidence ee
				                     WHERE ee.paper_author_contact_id = evidence.id)
				         AND NOT EXISTS (SELECT 1 FROM suppression_entries se
				             WHERE se.email_hmac = r.email_hmac
				               AND (se.expires_at IS NULL OR se.expires_at > now()))
				         AND NOT EXISTS (SELECT 1 FROM unsubscribe_records ur WHERE ur.email_hmac = r.email_hmac)
				         AND NOT EXISTS (SELECT 1 FROM campaign_exclusions ce
				             WHERE ce.campaign_id = r.campaign_id AND ce.email_hmac = r.email_hmac)
				         AND NOT EXISTS (SELECT 1 FROM recipient_delivery_cooldowns cooldown
				             WHERE cooldown.email_hmac = r.email_hmac
				               AND cooldown.last_smtp_accepted_at >= :cooldownCutoff)
				         AND NOT EXISTS (SELECT 1 FROM campaign_recipients unresolved
				             WHERE unresolved.email_hmac = r.email_hmac AND unresolved.id <> r.id
				               AND unresolved.status IN ('CONNECTING', 'OUTCOME_UNKNOWN'))
				         AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				         AND encode(r.email_hmac, 'hex') <> ALL(:excludedHmacs) AS currently_eligible
				FROM campaign_recipients r
				JOIN campaigns campaign ON campaign.id = r.campaign_id
				LEFT JOIN contacts c ON c.id = r.contact_id
				LEFT JOIN papers p ON p.id = r.paper_id AND p.deleted_at IS NULL
				LEFT JOIN paper_authors relation
				  ON relation.paper_id = r.paper_id AND relation.author_id = r.author_id
				LEFT JOIN LATERAL (
				    SELECT pac.id, pac.paper_author_id, pac.confidence,
				           pac.human_verified, pac.verification_status
				    FROM paper_author_contacts pac
				    JOIN extraction_runs er ON er.id = pac.extraction_run_id AND er.paper_id = pac.paper_id
				      AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
				    WHERE pac.contact_id = r.contact_id AND pac.paper_id = r.paper_id
				    ORDER BY er.completed_at DESC NULLS LAST, pac.created_at DESC, pac.id
				    LIMIT 1
				) evidence ON true
				WHERE r.campaign_id = :campaignId ORDER BY r.id FOR UPDATE OF r
				""").bind("campaignId", campaignId).bind("cooldownCutoff", cooldownCutoff)
				.bind("excludedHmacs", excludedHmacs).map((row, metadata) -> new FrozenProductionRecipient(
				row.get("contact_id", UUID.class), row.get("paper_id", UUID.class), row.get("author_id", UUID.class),
				row.get("email_hmac", byte[].class), row.get("personalization_status", String.class),
				row.get("attempt_count", Integer.class), Boolean.TRUE.equals(row.get("currently_eligible", Boolean.class)),
				new QueuedTarget(row.get("id", UUID.class),
				row.get("author_name_snapshot", String.class), row.get("paper_title_snapshot", String.class),
				row.get("paper_abstract_snapshot", String.class), row.get("arxiv_id_snapshot", String.class),
				row.get("category_snapshot", String.class), row.get("organization_snapshot", String.class)))).all();
	}

	public Mono<QueuedTarget> requeueFrozenRecipient(UUID campaignId, FrozenProductionRecipient recipient) {
		return databaseClient.sql("""
				UPDATE campaign_recipients SET personalization_status = 'QUEUED',
				    personalization_error_code = NULL, personalization_error_message = NULL, queued_at = now()
				WHERE campaign_id = :campaignId AND id = :id
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=:id)
				  AND personalization_status IN ('PENDING', 'FAILED') AND attempt_count = 0
				""").bind("campaignId", campaignId).bind("id", recipient.target().recipientId())
				.fetch().rowsUpdated().flatMap(rows -> rows == 1 ? Mono.just(recipient.target())
						: Mono.error(new CampaignValidationException("Frozen recipient cannot be retried")))
				.as(work -> lockedCampaignMutation(campaignId, work));
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
				       )) AS tracking_artifacts_frozen,
                transfer.migration_id AS transfer_migration_id, transfer.source_campaign_id AS transfer_source,
                transfer.target_campaign_id AS transfer_target, transfer.administrative_status AS transfer_status
                FROM campaign_recipients
                LEFT JOIN LATERAL (
                  SELECT t.migration_id,t.source_campaign_id,t.target_campaign_id,
                    CASE WHEN t.source_recipient_id=campaign_recipients.id THEN 'TRANSFERRED' ELSE 'MIGRATED_TARGET' END AS administrative_status
                  FROM campaign_recipient_transfers t
                  JOIN campaign_template_migrations m ON m.id=t.migration_id AND m.status='MIGRATED'
                    AND m.source_campaign_id=t.source_campaign_id AND m.target_campaign_id=t.target_campaign_id
                  WHERE t.source_recipient_id=campaign_recipients.id OR t.target_recipient_id=campaign_recipients.id
                  ORDER BY (t.source_recipient_id=campaign_recipients.id) DESC,t.migration_id LIMIT 1
                ) transfer ON true WHERE campaign_id = :campaignId
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
						Boolean.TRUE.equals(row.get("tracking_artifacts_frozen", Boolean.class)), CampaignSelectionInventory.transfer(row))).all();
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
				SELECT c.from_name, c.reply_to, c.generation_provider, c.generation_model
				FROM campaigns c JOIN campaign_recipients r ON r.campaign_id = c.id
				WHERE c.id = :campaignId AND r.id = :recipientId AND c.generation_job_id = :jobId
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				""").bind("campaignId", campaignId).bind("recipientId", recipientId).bind("jobId", jobId)
				.map((row, metadata) -> new ResultContext(
						row.get("from_name", String.class), row.get("reply_to", String.class),
						row.get("generation_provider", String.class), row.get("generation_model", String.class))).one();
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
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				""").bind("subject", draft.subject()).bind("html", draft.html()).bind("text", draft.text())
				.bind("rationale", draft.rationale()).bind("recipientId", recipientId)
				.bind("campaignId", campaignId).bind("jobId", jobId)
				.fetch().rowsUpdated().map(rows -> rows == 1).as(work -> lockedCampaignMutation(campaignId, work));
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
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id)
				""").bind("errorCode", errorCode).bind("errorMessage", errorMessage)
				.bind("recipientId", recipientId).bind("campaignId", campaignId).bind("jobId", jobId)
				.fetch().rowsUpdated().map(rows -> rows == 1).as(work -> lockedCampaignMutation(campaignId, work));
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
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_campaign_id=campaign.id)
				""").bind("campaignId", campaignId).bind("jobId", jobId).fetch().rowsUpdated().then()
				.as(work -> lockedCampaignMutation(campaignId, work));
	}

	/** A fresh statement after the campaign lock observes transfers committed while this writer waited. */
	private <T> Mono<T> lockedCampaignMutation(UUID campaignId, Mono<T> mutation) {
		var transactions = org.springframework.transaction.reactive.TransactionalOperator.create(
				new org.springframework.r2dbc.connection.R2dbcTransactionManager(databaseClient.getConnectionFactory()));
		return databaseClient.sql("SELECT id FROM campaigns WHERE id=:campaign FOR UPDATE")
				.bind("campaign", campaignId).fetch().all().then(mutation).as(transactions::transactional);
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
                """ + CampaignSelectionInventory.COLUMNS + """
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
				""" + inventory.join("c.id");
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
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class),
                CampaignSelectionInventory.counts(row), CampaignSelectionInventory.references(row));
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
			Instant createdAt, Instant updatedAt,
            SelectionCounts selectionCounts, List<MigrationReference> migrationReferences
	) {
        public CampaignRecord(UUID id, String name, String purpose, String status, UUID templateId, String templateName,
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
			Instant createdAt, Instant updatedAt) {
            this(id, name, purpose, status, templateId, templateName, templateVersion, segmentId, segmentName, smtpAccountId, smtpName, mailboxAccountId, fromName, fromEmail, replyTo, generationStatus, generationProvider, generationModel, generationJobId, queued, running, generated, failed, deliveryQueued, deliveryConnecting, deliverySmtpAccepted, deliveryTemporaryFailure, deliveryPermanentFailure, deliveryBounced, deliverySuppressed, deliveryUnsubscribed, deliveryCanceled, deliveryOutcomeUnknown, lockVersion, submittedForReviewAt, approvedAt, approvedBy, rejectedAt, rejectedBy, rejectionReason, scheduledAt, startedAt, completedAt, canceledAt, statusChangedAt, statusChangedBy, trackingOpensEnabled, trackingClicksEnabled, createdAt, updatedAt, null, null);
        }
    }

	public record GenerationContext(
			UUID campaignId, String purpose, UUID segmentId, String status, String generationStatus,
			String templateSubject, String templateHtml, String templateText, List<UUID> paperIds, Integer recipientLimit,
			String recipientPolicy, List<UUID> paperContactIds, Integer paperContactRecipientCount
	) {
		public GenerationContext(UUID campaignId, String purpose, UUID segmentId, String status, String generationStatus,
				String templateSubject, String templateHtml, String templateText, List<UUID> paperIds, Integer recipientLimit) {
			this(campaignId, purpose, segmentId, status, generationStatus, templateSubject, templateHtml, templateText,
					paperIds, recipientLimit, "AUTHOR_VERIFIED", null, null);
		}
		public GenerationContext(UUID campaignId, String purpose, UUID segmentId, String status, String generationStatus,
				String templateSubject, String templateHtml, String templateText, List<UUID> paperIds) {
			this(campaignId, purpose, segmentId, status, generationStatus, templateSubject, templateHtml, templateText, paperIds, null);
		}
	}

	public record FrozenProductionRecipient(UUID contactId, UUID paperId, UUID authorId, byte[] emailHmac,
			String personalizationStatus, int attemptCount, boolean currentlyEligible, QueuedTarget target) { }

	public record QueuedTarget(
			UUID recipientId, String authorName, String paperTitle, String abstractText, String arxivId,
			String primaryCategory, String organization
	) { }

	public record RecipientRecord(
			UUID id, String authorName, String paperTitle, String category, String organization,
			String personalizationStatus, String subject, String html, String text, String rationale,
			String errorCode, String errorMessage, Instant personalizedAt, Instant createdAt,
			boolean trackingArtifactsFrozen,
            RecipientTransfer transfer
	) {
        public RecipientRecord(UUID id, String authorName, String paperTitle, String category, String organization,
			String personalizationStatus, String subject, String html, String text, String rationale,
			String errorCode, String errorMessage, Instant personalizedAt, Instant createdAt,
			boolean trackingArtifactsFrozen) {
            this(id, authorName, paperTitle, category, organization, personalizationStatus, subject, html, text, rationale, errorCode, errorMessage, personalizedAt, createdAt, trackingArtifactsFrozen, null);
        }
    }

	public record ResultContext(String fromName, String replyTo, String provider, String model) { }

	public record GeneratedDraft(String subject, String html, String text, String rationale) { }
}
