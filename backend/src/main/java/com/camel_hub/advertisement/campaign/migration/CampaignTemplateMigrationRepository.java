package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.CampaignConflictException;
import com.camel_hub.advertisement.campaign.CampaignNotFoundException;
import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Exact selection, locked evidence and transactional ledger. No delivery/workflow side effects. */
public final class CampaignTemplateMigrationRepository {
    private final DatabaseClient db;
    private final Duration cooldown;
    private final String[] excludedHmacs;
    private final TransactionalOperator previewTransactions;
    private final CampaignTemplateMigrationPolicy policy = new CampaignTemplateMigrationPolicy();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Inputs must match the production-selection cooldown and contact-key safety-destination HMACs. */
    public CampaignTemplateMigrationRepository(DatabaseClient db, Duration cooldown, Set<String> excludedHmacs) {
        this.db = Objects.requireNonNull(db);
        if (cooldown == null || cooldown.isNegative() || excludedHmacs == null
                || excludedHmacs.stream().anyMatch(h -> h == null || !h.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Migration selection safety configuration is invalid");
        this.cooldown = cooldown;
        this.excludedHmacs = excludedHmacs.stream().sorted().toArray(String[]::new);
        var definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setReadOnly(true);
        previewTransactions = TransactionalOperator.create(new R2dbcTransactionManager(db.getConnectionFactory()), definition);
    }

    public Mono<MigrationSnapshot> readSnapshot(UUID sourceId, PreviewCommand command) {
        return Mono.defer(() -> snapshot(sourceId, command)).as(previewTransactions::transactional);
    }

    /** Caller owns the transaction: these locks MUST remain held through subsequent ledger/target writes. */
    public Mono<MigrationSnapshot> lockSnapshot(UUID sourceId, MigrationCommand command) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .onErrorMap(NoTransactionException.class, e -> new IllegalStateException("Migration snapshot locks require a caller-owned transaction", e))
                .flatMap(manager -> {
                    if (!manager.isActualTransactionActive()) return Mono.error(new IllegalStateException("Migration snapshot locks require a caller-owned transaction"));
                    var ids = policy.canonicalRecipients(command.recipientIds()).toArray(UUID[]::new);
                    var preview = new PreviewCommand(command.targetTemplateId(), command.targetTemplateVersionId(), command.recipientIds());
                    return lockRows(sourceId, preview, ids).then(snapshot(sourceId, preview)).flatMap(snapshot -> {
                        if (snapshot.campaign().lockVersion() != command.expectedLockVersion()
                                || snapshot.template().lockVersion() != command.expectedTemplateLockVersion())
                            return Mono.error(new CampaignConflictException("Migration source or target template changed; refresh and retry"));
                        return Mono.just(snapshot);
                    });
                });
    }

    public Mono<MigrationResult> findByKey(UUID actorId, String key) {
        return Mono.defer(() -> {
            policy.validateKey(key);
            return db.sql("SELECT * FROM campaign_template_migrations WHERE actor_id=:actor AND idempotency_key=:key")
                    .bind("actor", actorId).bind("key", key).map((r,m) -> new MigrationResult(r.get("id", UUID.class),
                            r.get("source_campaign_id", UUID.class), r.get("target_campaign_id", UUID.class), r.get("target_template_id", UUID.class),
                            r.get("target_template_version_id", UUID.class), r.get("selected_count", Integer.class), r.get("migrated_count", Integer.class),
                            r.get("status", String.class), reasons(r.get("reasons", String.class)))).one()
                    .switchIfEmpty(Mono.error(new CampaignNotFoundException("Migration was not found")));
        });
    }

    /** Serialize actor/key before checking original proof; key reuse never relies on current source state. */
    public Mono<Void> lockKey(UUID actor, String key) {
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,20261012))")
                .bind("key",actor+":"+key).fetch().all().then();
    }

    public Mono<StoredProof> storedProof(UUID actor,String key) {
        return db.sql("SELECT source_campaign_id,encode(request_digest,'hex') AS request,encode(membership_digest,'hex') AS membership FROM campaign_template_migrations WHERE actor_id=:actor AND idempotency_key=:key")
                .bind("actor",actor).bind("key",key).map((r,m)->new StoredProof(r.get("source_campaign_id",UUID.class),r.get("request",String.class),r.get("membership",String.class))).one();
    }
    public record StoredProof(UUID sourceId,String requestDigest,String membershipDigest) { }

    public Mono<MigrationResult> find(UUID source,UUID id,UUID actor) {
        return db.sql("SELECT idempotency_key FROM campaign_template_migrations WHERE id=:id AND source_campaign_id=:source AND actor_id=:actor")
                .bind("id",id).bind("source",source).bind("actor",actor).map((r,m)->r.get("idempotency_key",String.class)).one()
                .flatMap(key->findByKey(actor,key)).switchIfEmpty(Mono.error(new CampaignNotFoundException("Migration was not found")));
    }

    /** nonEmpty denotes a mutation/creation authority check, including an empty config update. */
    public Mono<Boolean> authorized(UUID actor, boolean nonEmpty) {
        return db.sql("SELECT id FROM users WHERE id=:actor FOR SHARE").bind("actor",actor).fetch().all().then()
                .then(db.sql("SELECT r.id FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=:actor ORDER BY r.id FOR SHARE OF r,ur").bind("actor",actor).fetch().all().then())
                .then(db.sql("SELECT rp.role_id FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id WHERE rp.role_id IN(SELECT role_id FROM user_roles WHERE user_id=:actor) ORDER BY rp.role_id,rp.permission_id FOR SHARE OF rp,p")
                        .bind("actor",actor).fetch().all().then()).then(readAuthorized(actor,nonEmpty));
    }
    public Mono<Boolean> readAuthorized(UUID actor,boolean write) {
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=:actor AND u.status='ACTIVE' AND NOT u.force_password_change
                    AND EXISTS(SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id JOIN permissions p ON p.id=rp.permission_id WHERE ur.user_id=u.id AND p.code='campaign:read')
                    AND (NOT :write OR EXISTS(SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id JOIN permissions p ON p.id=rp.permission_id WHERE ur.user_id=u.id AND p.code='campaign:create'))) AS allowed
                """).bind("actor",actor).bind("write",write).map((r,m)->yes(r,"allowed")).one();
    }

    public Mono<Void> saveResult(UUID actor,MigrationCommand command,String requestDigest,MigrationResult result) {
        return Mono.defer(()-> {
            var q=db.sql("""
                    INSERT INTO campaign_template_migrations(id,actor_id,idempotency_key,request_digest,membership_digest,source_campaign_id,target_campaign_id,
                        target_template_id,target_template_version_id,source_lock_version,target_template_lock_version,selected_count,migrated_count,status,reasons)
                    VALUES (:id,:actor,:key,decode(:request,'hex'),decode(:membership,'hex'),:source,:target,:template,:version,:sourceLock,:templateLock,:selected,:migrated,:status,CAST(:reasons AS jsonb))
                    """).bind("id",result.id()).bind("actor",actor).bind("key",command.idempotencyKey()).bind("request",requestDigest)
                    .bind("membership",command.expectedMembershipDigest()).bind("source",result.sourceCampaignId()).bind("template",result.targetTemplateId())
                    .bind("version",result.targetTemplateVersionId()).bind("sourceLock",command.expectedLockVersion()).bind("templateLock",command.expectedTemplateLockVersion())
                    .bind("selected",result.selectedCount()).bind("migrated",result.migratedCount()).bind("status",result.status()).bind("reasons",json(result.reasons()));
            q=result.targetCampaignId()==null?q.bindNull("target",UUID.class):q.bind("target",result.targetCampaignId());
            return q.fetch().rowsUpdated().then();
        });
    }
    public Mono<Void> insertTransfer(UUID migrationId,UUID sourceRecipientId,UUID targetRecipientId,byte[] sourceHash,byte[] contentHash) {
        if(sourceHash==null||sourceHash.length!=32||contentHash==null||contentHash.length!=32) return Mono.error(new IllegalArgumentException("Verified transfer hashes are required"));
        return db.sql("""
                INSERT INTO campaign_recipient_transfers(migration_id,source_campaign_id,source_recipient_id,target_campaign_id,target_recipient_id,source_hash,content_hash)
                SELECT id,source_campaign_id,:source,target_campaign_id,:target,:sourceHash,:contentHash FROM campaign_template_migrations WHERE id=:migration AND status='MIGRATED'
                """).bind("migration",migrationId).bind("source",sourceRecipientId).bind("target",targetRecipientId).bind("sourceHash",sourceHash).bind("contentHash",contentHash)
                .fetch().rowsUpdated().flatMap(n->n==1?Mono.empty():Mono.error(new CampaignConflictException("Migration transfer owner missing")));
    }
    public Mono<Void> saveProvenance(UUID migrationId,FrozenRecipient recipient) {
        var fields=new ArrayList<String>();
        if(blank(recipient.frozenTarget().paperAbstract()))fields.add("paperAbstract");
        if(blank(recipient.frozenTarget().arxivId()))fields.add("arxivId");
        String proof=json(Map.of("paperId",recipient.paperId(),"evidenceId",recipient.source().evidenceId(),"hydratedFields",fields,"capturedAt",recipient.source().capturedAt().toString()));
        return db.sql("UPDATE campaign_recipient_transfers SET provenance=CAST(:proof AS jsonb) WHERE migration_id=:migration AND source_recipient_id=:recipient")
                .bind("proof",proof).bind("migration",migrationId).bind("recipient",recipient.id()).fetch().rowsUpdated().then();
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Migration proof serialization failed",e);}}
    private static boolean blank(String value){return value==null||value.isBlank();}

    public Mono<Void> advanceSource(UUID source,long version) {
        return db.sql("UPDATE campaigns SET lock_version=lock_version+1 WHERE id=:source AND lock_version=:version")
                .bind("source",source).bind("version",version).fetch().rowsUpdated().flatMap(n->n==1?Mono.empty():Mono.error(new CampaignConflictException("Source changed")));
    }
    public Mono<Boolean> emptyPristineDraft(UUID source) {
        return db.sql("""
                SELECT c.status='DRAFT' AND c.generation_status='NOT_REQUESTED' AND c.generation_job_id IS NULL
                    AND c.generation_requested_at IS NULL AND c.generation_completed_at IS NULL AND c.generation_error_summary IS NULL
                    AND c.submitted_for_review_at IS NULL AND c.approved_at IS NULL AND c.rejected_at IS NULL AND c.scheduled_at IS NULL AND c.started_at IS NULL AND c.completed_at IS NULL AND c.canceled_at IS NULL
                    AND NOT EXISTS(SELECT 1 FROM campaign_recipients WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM campaign_safety_runs WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM campaign_template_migrations WHERE source_campaign_id=c.id OR target_campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM outreach_runs WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM bulk_outreach_children WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM audit_logs WHERE resource_id=c.id::text)
                    AND NOT EXISTS(SELECT 1 FROM campaign_links WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM campaign_exclusions WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM unsubscribe_records WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM tracking_events WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM campaign_hourly_stats WHERE campaign_id=c.id)
                    AND NOT EXISTS(SELECT 1 FROM outbox_messages WHERE aggregate_id=c.id) AS pristine
                FROM campaigns c WHERE c.id=:source
                """).bind("source",source).map((r,m)->yes(r,"pristine")).one();
    }
    public Mono<Void> updateEmptyConfiguration(UUID source,MigrationCommand command) {
        return db.sql("UPDATE campaigns SET template_id=:template,template_version_id=:version,lock_version=lock_version+1 WHERE id=:source AND lock_version=:lock")
                .bind("template",command.targetTemplateId()).bind("version",command.targetTemplateVersionId()).bind("source",source).bind("lock",command.expectedLockVersion())
                .fetch().rowsUpdated().flatMap(n->n==1?Mono.empty():Mono.error(new CampaignConflictException("Source changed")));
    }

    private List<MigrationReason> reasons(String json) {
        try { return List.copyOf(mapper.readValue(json, new TypeReference<List<MigrationReason>>() { })); }
        catch (Exception e) { throw new IllegalStateException("Stored migration reasons are invalid", e); }
    }

    private Mono<MigrationSnapshot> snapshot(UUID source, PreviewCommand command) {
        if (source == null || command == null || command.targetTemplateId() == null || command.targetTemplateVersionId() == null)
            return Mono.error(new CampaignValidationException("Source and target template/version are required"));
        UUID[] ids = policy.canonicalRecipients(command.recipientIds()).toArray(UUID[]::new);
        return campaign(source).flatMap(campaign -> parents(source,ids).map(parents -> {
            var fields=new ArrayList<Object>();fields.add(campaign.evidenceDigest());fields.add(parents.size());
            for(var parent:parents)Collections.addAll(fields,parent.kind(),parent.id(),parent.campaignId(),parent.status(),parent.lockVersion(),parent.associationDigest());
            return new CampaignEvidence(campaign.id(),campaign.lockVersion(),campaign.status(),campaign.templateId(),campaign.templateVersionId(),campaign.generationStatus(),CampaignTemplateMigrationPolicy.digest(fields),campaign.automaticOwnership(),campaign.parentRunnable());
        })).flatMap(campaign -> template(command).flatMap(template -> sender(source).flatMap(sender ->
                db.sql(recipientSql()).bind("source", source).bind("ids", ids).bind("cutoff", Instant.now().minus(cooldown))
                        .bind("excluded", excludedHmacs).map((r,m) -> recipient(r)).all().collectList().flatMap(recipients -> {
                            if (recipients.size() != ids.length) return Mono.error(new CampaignValidationException("Exact recipient list contains missing or foreign IDs"));
                            var snapshot = new MigrationSnapshot(campaign, template, sender, recipients);
                            policy.membershipDigest(snapshot);
                            return Mono.just(snapshot);
                        }))));
    }

    private Mono<CampaignEvidence> campaign(UUID source) {
        return db.sql("""
                SELECT c.*, jsonb_build_array(to_jsonb(c),
                    (SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM outreach_runs o WHERE o.campaign_id=c.id),
                    (SELECT jsonb_agg(jsonb_build_array(to_jsonb(b),to_jsonb(p)) ORDER BY p.id) FROM bulk_outreach_children b JOIN bulk_outreach_runs p ON p.id=b.run_id WHERE b.campaign_id=c.id))::text AS evidence,
                    EXISTS(SELECT 1 FROM outreach_runs o WHERE o.campaign_id=c.id AND o.status IN ('ACTIVE','SENDING'))
                    OR EXISTS(SELECT 1 FROM bulk_outreach_children b JOIN bulk_outreach_runs p ON p.id=b.run_id WHERE b.campaign_id=c.id AND p.status IN ('ACTIVE','SENDING')) AS parent_runnable,
                    EXISTS(SELECT 1 FROM outreach_runs o WHERE o.campaign_id=c.id)
                    OR EXISTS(SELECT 1 FROM bulk_outreach_children b WHERE b.campaign_id=c.id) AS automatic_ownership
                FROM campaigns c WHERE c.id=:source
                """).bind("source", source).map((r,m) -> new CampaignEvidence(r.get("id", UUID.class), r.get("lock_version", Long.class),
                        r.get("status", String.class), r.get("template_id", UUID.class), r.get("template_version_id", UUID.class),
                        r.get("generation_status", String.class), hash(r.get("evidence", String.class)), yes(r,"automatic_ownership"),yes(r,"parent_runnable"))).one()
                .switchIfEmpty(Mono.error(new CampaignNotFoundException("Source campaign was not found")));
    }

    private Mono<TemplateEvidence> template(PreviewCommand command) {
        return db.sql("""
                SELECT t.id,t.lock_version,t.status,t.deleted_at IS NULL AND t.current_version=v.version_number AS current_version,
                    v.id AS version_id,to_jsonb(v)::text AS content
                FROM email_templates t JOIN email_template_versions v ON v.template_id=t.id
                WHERE t.id=:template AND v.id=:version
                """).bind("template", command.targetTemplateId()).bind("version", command.targetTemplateVersionId())
                .map((r,m) -> new TemplateEvidence(r.get("id", UUID.class), r.get("version_id", UUID.class), r.get("lock_version", Long.class),
                        r.get("status", String.class), yes(r,"current_version"), hash(r.get("content", String.class)))).one()
                .switchIfEmpty(Mono.error(new CampaignValidationException("Target immutable version does not belong to the template")));
    }

    private Mono<SenderEvidence> sender(UUID source) {
        return db.sql("""
                SELECT smtp.id AS smtp_id,smtp.lock_version AS smtp_version,mailbox.id AS mailbox_id,mailbox.lock_version AS mailbox_version,
                    jsonb_build_array(to_jsonb(smtp)-'password_ciphertext'-'password_nonce',to_jsonb(mailbox)-'password_ciphertext'-'password_nonce')::text AS config,
                    smtp.enabled AND smtp.last_test_status='SUCCEEDED' AND smtp.last_tested_at>=smtp.updated_at
                    AND smtp.from_email=c.from_email AND smtp.reply_to=c.reply_to
                    AND mailbox.enabled AND mailbox.protocol='IMAP' AND mailbox.last_test_status='SUCCEEDED'
                    AND mailbox.last_tested_at>=mailbox.updated_at AND lower(trim(mailbox.username))=lower(trim(c.reply_to)) AS ready
                FROM campaigns c JOIN smtp_accounts smtp ON smtp.id=c.smtp_account_id
                LEFT JOIN mailbox_accounts mailbox ON mailbox.id=c.mailbox_account_id WHERE c.id=:source
                """).bind("source", source).map((r,m) -> new SenderEvidence(r.get("smtp_id", UUID.class), r.get("smtp_version", Long.class),
                        r.get("mailbox_id", UUID.class), r.get("mailbox_version", Long.class), hash(r.get("config", String.class)), yes(r,"ready"))).one();
    }

    private FrozenRecipient recipient(Row r) {
        var target = new FrozenTarget(r.get("ciphertext", String.class), r.get("nonce", String.class), r.get("email_domain", String.class), r.get("author_name_snapshot", String.class),
                r.get("paper_title_snapshot", String.class), r.get("paper_abstract_snapshot", String.class), r.get("arxiv_id_snapshot", String.class),
                r.get("category_snapshot", String.class), r.get("organization_snapshot", String.class));
        var reservation = r.get("reservation_run", UUID.class) == null ? null : new Reservation(r.get("reservation_run", UUID.class),
                r.get("reservation_campaign", UUID.class), r.get("reservation_recipient", UUID.class), r.get("reservation_outcome", String.class));
        return new FrozenRecipient(r.get("id", UUID.class), r.get("campaign_id", UUID.class), r.get("contact_id", UUID.class),
                r.get("paper_id", UUID.class), r.get("author_id", UUID.class), r.get("hmac", String.class), target,
                hash(r.get("frozen_target", String.class)), hash(r.get("body", String.class)), r.get("status", String.class), r.get("attempt_count", Integer.class),
                r.get("personalization_status", String.class), yes(r,"formal_side_effects"), yes(r,"safety_active_or_unknown"), yes(r,"transferred"),
                yes(r,"source_available"), yes(r,"eligible"), hash(r.get("formal_evidence", String.class)), hash(r.get("safety_evidence", String.class)),
                hash(r.get("source_evidence", String.class)), reservation,
                new SourceEvidence(r.get("evidence_id",UUID.class),r.get("paper_author_id",UUID.class),r.get("mapped_author_id",UUID.class),
                        r.get("source_confidence",String.class),yes(r,"human_verified"),r.get("verification_status",String.class),yes(r,"identity_trusted"),
                        r.get("source_author_name",String.class),r.get("source_organization",String.class),r.get("source_paper_title",String.class),
                        r.get("source_abstract",String.class),r.get("source_arxiv_id",String.class),r.get("captured_at",Instant.class)));
    }

    private String recipientSql() {
        return """
                SELECT r.*,statement_timestamp() AS captured_at,encode(r.email_hmac,'hex') AS hmac,encode(r.email_ciphertext,'hex') AS ciphertext,encode(r.email_nonce,'hex') AS nonce,
                    evidence.id AS evidence_id,evidence.paper_author_id,relation.author_id AS mapped_author_id,evidence.confidence AS source_confidence,
                    evidence.human_verified,evidence.verification_status,relation.raw_name AS source_author_name,relation.affiliation_text AS source_organization,
                    p.title AS source_paper_title,p.abstract_text AS source_abstract,p.arxiv_id AS source_arxiv_id,
                    relation.author_id IS NOT NULL AND length(trim(coalesce(relation.raw_name,''))) BETWEEN 1 AND 300
                        AND length(relation.raw_name)<=300 AND length(coalesce(relation.affiliation_text,''))<=500
                        AND (evidence.confidence='HIGH' OR (evidence.human_verified AND evidence.verification_status='CONFIRMED')) AS identity_trusted,
                    jsonb_build_array(r.contact_id,r.paper_id,r.author_id,encode(r.email_hmac,'hex'),encode(r.email_ciphertext,'hex'),encode(r.email_nonce,'hex'),
                        r.author_name_snapshot,r.paper_title_snapshot,r.paper_abstract_snapshot,r.arxiv_id_snapshot,r.category_snapshot,r.organization_snapshot,r.confidence)::text AS frozen_target,
                    jsonb_build_array(r.rendered_subject,r.rendered_html,r.rendered_text,r.personalization_rationale,r.personalization_error_code,r.personalization_error_message,r.personalized_at)::text AS body,
                    EXISTS(SELECT 1 FROM delivery_attempts a WHERE a.campaign_recipient_id=r.id)
                        OR r.first_attempt_at IS NOT NULL OR r.smtp_accepted_at IS NOT NULL OR r.final_failure_at IS NOT NULL
                        OR r.delivery_lease_hash IS NOT NULL OR r.delivery_lease_expires_at IS NOT NULL OR r.rfc_message_id IS NOT NULL
                        OR r.outcome_unknown_at IS NOT NULL OR r.outcome_unknown_reason IS NOT NULL OR r.replied_at IS NOT NULL
                        OR r.first_open_at IS NOT NULL OR r.first_click_at IS NOT NULL
                        OR EXISTS(SELECT 1 FROM tracking_tokens tok WHERE tok.campaign_recipient_id=r.id)
                        OR EXISTS(SELECT 1 FROM tracking_events evt WHERE evt.campaign_recipient_id=r.id)
                        OR EXISTS(SELECT 1 FROM mailbox_inbound_events evt WHERE evt.campaign_recipient_id=r.id)
                        OR r.partner_invite_hold_reason IS NOT NULL AS formal_side_effects,
                    EXISTS(SELECT 1 FROM campaign_safety_runs sr WHERE sr.campaign_id=r.campaign_id AND sr.status IN ('QUEUED','RUNNING'))
                        OR EXISTS(SELECT 1 FROM campaign_safety_messages sm JOIN campaign_safety_runs sr ON sr.id=sm.run_id WHERE sr.campaign_id=r.campaign_id
                            AND (sm.status IN ('QUEUED','CONNECTING','TEMPORARY_FAILURE','OUTCOME_UNKNOWN')
                                OR sm.delivery_lease_hash IS NOT NULL OR sm.outcome_unknown_at IS NOT NULL))
                        OR EXISTS(SELECT 1 FROM campaign_safety_attempts sa JOIN campaign_safety_messages sm ON sm.id=sa.safety_message_id JOIN campaign_safety_runs sr ON sr.id=sm.run_id
                            WHERE sr.campaign_id=r.campaign_id AND (sa.status IN ('CONNECTING','OUTCOME_UNKNOWN') OR sa.outcome_unknown_reason IS NOT NULL)) AS safety_active_or_unknown,
                    EXISTS(SELECT 1 FROM campaign_recipient_transfers tr WHERE tr.source_recipient_id=r.id) AS transferred,
                    p.id IS NOT NULL AND evidence.id IS NOT NULL AND evidence.verification_status<>'REJECTED'
                        AND EXISTS(SELECT 1 FROM extraction_evidence ee WHERE ee.paper_author_contact_id=evidence.id)
                        AND length(trim(public_inputs.title)) BETWEEN 1 AND 2000 AND length(public_inputs.title)<=2000
                        AND length(trim(coalesce(public_inputs.abstract_text,''))) BETWEEN 1 AND 20000 AND length(public_inputs.abstract_text)<=20000
                        AND public_inputs.arxiv_id ~ '^([0-9]{4}[.][0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$' AS source_available,
                    c.id IS NOT NULL AND c.deleted_at IS NULL AND c.syntax_valid AND NOT c.example_address AND c.suppression_status='ACTIVE'
                        AND c.email_hmac=r.email_hmac AND encode(r.email_hmac,'hex')<>ALL(:excluded)
                        AND NOT EXISTS(SELECT 1 FROM suppression_entries se WHERE se.email_hmac=r.email_hmac AND (se.expires_at IS NULL OR se.expires_at>now()))
                        AND NOT EXISTS(SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac=r.email_hmac)
                        AND NOT EXISTS(SELECT 1 FROM campaign_exclusions ce WHERE ce.campaign_id=r.campaign_id AND ce.email_hmac=r.email_hmac)
                        AND NOT EXISTS(SELECT 1 FROM recipient_delivery_cooldowns cd WHERE cd.email_hmac=r.email_hmac AND cd.last_smtp_accepted_at>=:cutoff)
                        AND NOT EXISTS(SELECT 1 FROM campaign_recipients other JOIN campaigns oc ON oc.id=other.campaign_id
                            WHERE other.email_hmac=r.email_hmac AND other.id<>r.id
                                AND (other.status IN ('CONNECTING','OUTCOME_UNKNOWN') OR (oc.recipient_policy='PAPER_CONTACT'
                                    AND oc.status IN ('DRAFT','READY_FOR_REVIEW','APPROVED','REJECTED','SCHEDULED','RUNNING','PAUSED')
                                    AND other.status IN ('QUEUED','TEMPORARY_FAILURE')))) AS eligible,
                    jsonb_build_array((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM delivery_attempts a WHERE a.campaign_recipient_id=r.id),
                        (SELECT jsonb_agg(to_jsonb(tok) ORDER BY tok.id) FROM tracking_tokens tok WHERE tok.campaign_recipient_id=r.id),
                        (SELECT jsonb_agg(to_jsonb(evt) ORDER BY evt.id) FROM tracking_events evt WHERE evt.campaign_recipient_id=r.id),
                        (SELECT jsonb_agg(to_jsonb(evt) ORDER BY evt.id) FROM mailbox_inbound_events evt WHERE evt.campaign_recipient_id=r.id),
                        r.first_attempt_at,r.smtp_accepted_at,r.final_failure_at,r.delivery_lease_hash,r.delivery_lease_expires_at,r.rfc_message_id,r.outcome_unknown_at,r.outcome_unknown_reason)::text AS formal_evidence,
                    jsonb_build_array((SELECT jsonb_agg(to_jsonb(sr) ORDER BY sr.id) FROM campaign_safety_runs sr WHERE sr.campaign_id=r.campaign_id),
                        (SELECT jsonb_agg(to_jsonb(sm) ORDER BY sm.id) FROM campaign_safety_messages sm JOIN campaign_safety_runs sr ON sr.id=sm.run_id WHERE sr.campaign_id=r.campaign_id),
                        (SELECT jsonb_agg(to_jsonb(sa) ORDER BY sa.id) FROM campaign_safety_attempts sa JOIN campaign_safety_messages sm ON sm.id=sa.safety_message_id JOIN campaign_safety_runs sr ON sr.id=sm.run_id WHERE sr.campaign_id=r.campaign_id))::text AS safety_evidence,
                    jsonb_build_array(to_jsonb(evidence),
                        (SELECT jsonb_agg(to_jsonb(ee) ORDER BY ee.id) FROM extraction_evidence ee WHERE ee.paper_author_contact_id=evidence.id),
                        to_jsonb(relation),c.syntax_valid,c.example_address,c.suppression_status,c.deleted_at,encode(c.email_hmac,'hex'),
                        p.id,p.title,p.abstract_text,p.arxiv_id,p.deleted_at,
                        (SELECT jsonb_agg(to_jsonb(se) ORDER BY se.id) FROM suppression_entries se WHERE se.email_hmac=r.email_hmac),
                        (SELECT jsonb_agg(to_jsonb(u) ORDER BY u.id) FROM unsubscribe_records u WHERE u.email_hmac=r.email_hmac),
                        (SELECT jsonb_agg(to_jsonb(ce) ORDER BY ce.id) FROM campaign_exclusions ce WHERE ce.campaign_id=r.campaign_id AND ce.email_hmac=r.email_hmac),
                        (SELECT to_jsonb(cd) FROM recipient_delivery_cooldowns cd WHERE cd.email_hmac=r.email_hmac),
                        (SELECT jsonb_agg(jsonb_build_array(other.id,other.status) ORDER BY other.id) FROM campaign_recipients other WHERE other.email_hmac=r.email_hmac AND other.id<>r.id),
                        (SELECT to_jsonb(pi) FROM partner_invites pi WHERE pi.email_hmac=r.email_hmac))::text AS source_evidence,
                    reservation.run_id AS reservation_run,reservation.campaign_id AS reservation_campaign,
                    reservation.recipient_id AS reservation_recipient,reservation.outcome AS reservation_outcome
                FROM campaign_recipients r
                LEFT JOIN contacts c ON c.id=r.contact_id
                LEFT JOIN papers p ON p.id=r.paper_id AND p.deleted_at IS NULL
                CROSS JOIN LATERAL (SELECT
                    r.paper_title_snapshot AS title,
                    CASE WHEN length(trim(coalesce(r.paper_abstract_snapshot,'')))=0 THEN p.abstract_text ELSE r.paper_abstract_snapshot END AS abstract_text,
                    CASE WHEN length(trim(coalesce(r.arxiv_id_snapshot,'')))=0 THEN p.arxiv_id ELSE r.arxiv_id_snapshot END AS arxiv_id
                ) public_inputs
                LEFT JOIN LATERAL (
                    SELECT pac.*,to_jsonb(er) AS extraction_run_evidence FROM paper_author_contacts pac JOIN extraction_runs er ON er.id=pac.extraction_run_id AND er.paper_id=pac.paper_id
                        AND er.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED')
                    WHERE pac.contact_id=r.contact_id AND pac.paper_id=r.paper_id
                    ORDER BY er.completed_at DESC NULLS LAST,pac.created_at DESC,pac.id LIMIT 1
                ) evidence ON true
                LEFT JOIN paper_authors relation ON relation.id=evidence.paper_author_id AND relation.paper_id=r.paper_id
                LEFT JOIN bulk_outreach_reservations reservation ON reservation.email_hmac=r.email_hmac
                WHERE r.campaign_id=:source AND r.id=ANY(:ids) ORDER BY r.id
                """;
    }

    /** Related outreach/bulk roots -> global advisory -> campaigns -> recipients -> safety -> templates/accounts -> HMAC rows. */
    private Mono<Void> lockRows(UUID source, PreviewCommand command, UUID[] ids) {
        String related = "SELECT r.email_hmac FROM campaign_recipients r WHERE r.campaign_id=:source AND r.id=ANY(:ids)";
        return parents(source,ids).flatMap(initial -> lockParents(initial).then(db.sql("SELECT pg_advisory_xact_lock(20260923,1000)").fetch().rowsUpdated().then())
                .then(lock("SELECT id FROM campaigns WHERE id=:source OR id IN (SELECT campaign_id FROM campaign_recipients WHERE email_hmac IN (" + related + ")) ORDER BY id FOR UPDATE", source, ids))
                .then(parents(source,ids).flatMap(current->current.equals(initial)?Mono.empty():Mono.error(new CampaignConflictException("Automatic ownership changed; refresh and retry"))))
                .then(lock("SELECT id FROM campaign_recipients WHERE campaign_id=:source OR email_hmac IN (" + related + ") ORDER BY id FOR UPDATE", source, ids))
                .then(lock("SELECT id FROM campaign_safety_runs WHERE campaign_id=:source ORDER BY id FOR UPDATE", source, null))
                .then(lock("SELECT sm.id FROM campaign_safety_messages sm JOIN campaign_safety_runs sr ON sr.id=sm.run_id WHERE sr.campaign_id=:source ORDER BY sm.id FOR UPDATE OF sm", source, null))
                .then(lock("SELECT sa.id FROM campaign_safety_attempts sa JOIN campaign_safety_messages sm ON sm.id=sa.safety_message_id JOIN campaign_safety_runs sr ON sr.id=sm.run_id WHERE sr.campaign_id=:source ORDER BY sa.id FOR UPDATE OF sa", source, null))
                .then(db.sql("SELECT id FROM email_templates WHERE id=:target OR id=(SELECT template_id FROM campaigns WHERE id=:source) ORDER BY id FOR UPDATE")
                        .bind("target",command.targetTemplateId()).bind("source",source).fetch().all().then())
                .then(db.sql("SELECT id FROM email_template_versions WHERE id=:version OR id=(SELECT template_version_id FROM campaigns WHERE id=:source) ORDER BY id FOR SHARE")
                        .bind("version",command.targetTemplateVersionId()).bind("source",source).fetch().all().then())
                .then(lock("SELECT id FROM smtp_accounts WHERE id=(SELECT smtp_account_id FROM campaigns WHERE id=:source) ORDER BY id FOR SHARE", source, null))
                .then(lock("SELECT id FROM mailbox_accounts WHERE id=(SELECT mailbox_account_id FROM campaigns WHERE id=:source) ORDER BY id FOR SHARE", source, null))
                .then(lockHmacRows(source, ids)));
    }

    private Mono<List<Parent>> parents(UUID source,UUID[] ids) {
        return db.sql("""
                WITH related AS (SELECT :source::uuid AS id UNION SELECT campaign_id FROM campaign_recipients WHERE email_hmac IN
                    (SELECT email_hmac FROM campaign_recipients WHERE campaign_id=:source AND id=ANY(:ids)))
                SELECT 'outreach_runs' AS kind,o.id,o.campaign_id,o.status,o.lock_version,to_jsonb(o)::text AS association
                    FROM outreach_runs o WHERE o.campaign_id IN(SELECT id FROM related)
                UNION ALL SELECT 'bulk_outreach_runs',p.id,b.campaign_id,p.status,p.lock_version,to_jsonb(b)::text
                    FROM bulk_outreach_children b JOIN bulk_outreach_runs p ON p.id=b.run_id WHERE b.campaign_id IN(SELECT id FROM related)
                ORDER BY kind DESC,id,campaign_id
                """).bind("source",source).bind("ids",ids).map((r,m)->new Parent(r.get("kind",String.class),r.get("id",UUID.class),r.get("campaign_id",UUID.class),
                        r.get("status",String.class),r.get("lock_version",Long.class),hash(r.get("association",String.class)))).all().collectList();
    }
    private Mono<Void> lockParents(List<Parent> parents) {
        return reactor.core.publisher.Flux.fromIterable(parents).concatMap(p->db.sql("SELECT id FROM "+p.kind()+" WHERE id=:id FOR UPDATE").bind("id",p.id()).fetch().all()).then();
    }
    private record Parent(String kind,UUID id,UUID campaignId,String status,long lockVersion,String associationDigest) { }

    private Mono<Void> lockHmacRows(UUID source, UUID[] ids) {
        return db.sql("SELECT DISTINCT email_hmac FROM campaign_recipients WHERE campaign_id=:source AND id=ANY(:ids) ORDER BY email_hmac")
                .bind("source",source).bind("ids",ids).map((r,m) -> HexFormat.of().formatHex(r.get("email_hmac",byte[].class))).all()
                .concatMap(hmac -> {
                    Mono<Void> locks=Mono.empty();
                    for (String table : List.of("contacts","suppression_entries","unsubscribe_records","recipient_delivery_cooldowns","bulk_outreach_reservations","partner_invites")) {
                        locks=locks.then(db.sql("SELECT email_hmac FROM " + table + " WHERE email_hmac=decode(:hmac,'hex') FOR UPDATE")
                                .bind("hmac",hmac).fetch().all().then());
                    }
                    return locks;
                }).then();
    }
    private Mono<Void> lock(String sql, UUID source, UUID[] ids) {
        var statement = db.sql(sql).bind("source",source);
        if (ids != null) statement = statement.bind("ids",ids);
        return statement.fetch().all().then();
    }
    private static boolean yes(Row r, String column) { return Boolean.TRUE.equals(r.get(column,Boolean.class)); }
    private static String hash(String value) { return CampaignTemplateMigrationPolicy.digest(Collections.singletonList(value)); }

    public record MigrationSnapshot(CampaignEvidence campaign, TemplateEvidence template, SenderEvidence sender, List<FrozenRecipient> recipients) {
        public MigrationSnapshot { Objects.requireNonNull(campaign); Objects.requireNonNull(template); Objects.requireNonNull(sender); recipients=List.copyOf(recipients); }
    }
    public record CampaignEvidence(UUID id,long lockVersion,String status,UUID templateId,UUID templateVersionId,String generationStatus,String evidenceDigest,boolean automaticOwnership,boolean parentRunnable) {
        public CampaignEvidence(UUID id,long lockVersion,String status,UUID templateId,UUID templateVersionId,String generationStatus,String evidenceDigest,boolean automaticOwnership){this(id,lockVersion,status,templateId,templateVersionId,generationStatus,evidenceDigest,automaticOwnership,false);}
    }
    public record TemplateEvidence(UUID id,UUID versionId,long lockVersion,String status,boolean currentVersion,String contentDigest) { }
    public record SenderEvidence(UUID smtpId,long smtpLockVersion,UUID mailboxId,Long mailboxLockVersion,String configurationDigest,boolean ready) { }
    /** Internal encrypted/frozen inputs only; never serialize this snapshot as an API response. */
    public record FrozenTarget(String emailCiphertextHex,String emailNonceHex,String emailDomain,String authorName,String paperTitle,String paperAbstract,String arxivId,String category,String organization) { }
    public record FrozenRecipient(UUID id,UUID campaignId,UUID contactId,UUID paperId,UUID authorId,String emailHmac,FrozenTarget frozenTarget,
            String frozenTargetDigest,String bodyDigest,String status,int attemptCount,String personalizationStatus,
            boolean formalSideEffects,boolean safetyActiveOrUnknown,boolean transferred,boolean sourceAvailable,boolean eligible,
            String formalEvidenceDigest,String safetyEvidenceDigest,String sourceEvidenceDigest,Reservation reservation,SourceEvidence source) { }
    public record Reservation(UUID runId,UUID campaignId,UUID recipientId,String outcome) { }
    /** Current same-paper provenance for later hydration; low/unmapped confidence permits only neutral identity. */
    public record SourceEvidence(UUID evidenceId,UUID paperAuthorId,UUID mappedAuthorId,String confidence,boolean humanVerified,String verificationStatus,
            boolean identityTrusted,String authorName,String organization,String paperTitle,String paperAbstract,String arxivId,Instant capturedAt) {
        public SourceEvidence(UUID evidenceId,UUID paperAuthorId,UUID mappedAuthorId,String confidence,boolean humanVerified,String verificationStatus,boolean identityTrusted,String authorName,String organization,String paperTitle,String paperAbstract,String arxivId){this(evidenceId,paperAuthorId,mappedAuthorId,confidence,humanVerified,verificationStatus,identityTrusted,authorName,organization,paperTitle,paperAbstract,arxivId,null);}
    }
}
