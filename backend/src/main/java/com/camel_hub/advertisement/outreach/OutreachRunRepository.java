package com.camel_hub.advertisement.outreach;

import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class OutreachRunRepository {
    private final DatabaseClient db;
    public OutreachRunRepository(DatabaseClient db) { this.db = db; }

    public Mono<RunRecord> find(UUID id) { return db.sql("SELECT * FROM outreach_runs WHERE id=:id").bind("id",id).map((r,m)->map(r)).one(); }
    public Mono<RunRecord> lock(UUID id) { return db.sql("SELECT * FROM outreach_runs WHERE id=:id FOR UPDATE").bind("id",id).map((r,m)->map(r)).one(); }
    public Mono<RunRecord> lockNext() {
        return db.sql("""
                SELECT * FROM outreach_runs WHERE status IN ('ACTIVE','SENDING') AND next_step_at<=now()
                ORDER BY next_step_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).map((r,m)->map(r)).one();
    }
    public Mono<RunRecord> findByKey(UUID actor,String key) { return db.sql("SELECT * FROM outreach_runs WHERE actor_id=:actor AND idempotency_key=:key")
            .bind("actor",actor).bind("key",key).map((r,m)->map(r)).one(); }
    public Flux<RunRecord> list(int offset,int limit) { return db.sql("SELECT * FROM outreach_runs ORDER BY created_at DESC,id OFFSET :offset LIMIT :limit")
            .bind("offset",offset).bind("limit",limit).map((r,m)->map(r)).all(); }
    public Mono<Long> count() { return db.sql("SELECT count(*) AS n FROM outreach_runs").map((r,m)->r.get("n",Long.class)).one(); }

    public Mono<RunRecord> create(UUID actor, OutreachDtos.CreateRequest command, byte[] hash, String criteriaJson,
            String contextJson, ConfigSnapshot config) {
        return db.sql("""
                INSERT INTO outreach_runs(actor_id,idempotency_key,payload_hash,name,target_mode,target_count,
                  criteria,purpose,request_context,smtp_account_id,mailbox_account_id,template_id,template_version_id,
                  template_version,smtp_lock_version,mailbox_lock_version,from_email,from_name,reply_to)
                VALUES(:actor,:key,:hash,:name,:mode,:count,CAST(:criteria AS jsonb),:purpose,CAST(:context AS jsonb),
                  :smtp,:mailbox,:template,:versionId,:version,:smtpLock,:mailboxLock,:fromEmail,:fromName,:replyTo)
                ON CONFLICT(actor_id,idempotency_key) DO NOTHING RETURNING *
                """).bind("actor",actor).bind("key",command.idempotencyKey()).bind("hash",hash).bind("name",command.name())
                .bind("mode",command.targetMode()).bind("count",command.targetCount()).bind("criteria",criteriaJson)
                .bind("purpose",command.purpose()).bind("context",contextJson).bind("smtp",command.smtpAccountId())
                .bind("mailbox",config.mailboxAccountId()).bind("template",command.templateId()).bind("versionId",config.templateVersionId())
                .bind("version",config.templateVersion()).bind("smtpLock",config.smtpLockVersion()).bind("mailboxLock",config.mailboxLockVersion())
                .bind("fromEmail",config.fromEmail()).bind("fromName",config.fromName()).bind("replyTo",config.replyTo())
                .map((r,m)->map(r)).one();
    }

    public Mono<RunRecord> progress(RunRecord run, Progress p) {
        var query=db.sql("""
                UPDATE outreach_runs SET status=:status,stage=:stage,message=:message,paper_ids=:papers,paper_count=:paperCount,
                  extracted_count=:extracted,eligible_count=:eligible,selected_count=:selected,smtp_accepted=:accepted,
                  failed_count=:failed,unknown_count=:unknown,campaign_id=:campaign,import_job_id=:importJob,
                  extraction_job_id=:extractionJob,lock_version=lock_version+1,updated_at=now(),
                  next_step_at=now() + (:delay * interval '1 second')
                WHERE id=:id AND lock_version=:lockVersion RETURNING *
                """).bind("status",p.status()).bind("stage",p.stage()).bind("message",p.message())
                .bind("papers",p.paperIds().toArray(UUID[]::new)).bind("paperCount",p.paperIds().size())
                .bind("extracted",p.extractedCount()).bind("eligible",p.eligibleCount()).bind("selected",p.selectedCount())
                .bind("accepted",p.smtpAccepted()).bind("failed",p.failedCount()).bind("unknown",p.unknownCount())
                .bind("delay",p.delaySeconds()).bind("id",run.id()).bind("lockVersion",run.lockVersion());
        query=nullable(query,"campaign",p.campaignId()); query=nullable(query,"importJob",p.importJobId()); query=nullable(query,"extractionJob",p.extractionJobId());
        return query.map((r,m)->map(r)).one();
    }
    private DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec query,String key,UUID value) {
        return value==null?query.bindNull(key,UUID.class):query.bind(key,value);
    }

    public Mono<Boolean> authorized(UUID actor) {
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=:actor AND u.status='ACTIVE' AND NOT u.force_password_change
                  AND (SELECT count(DISTINCT p.code) FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id
                    JOIN permissions p ON p.id=rp.permission_id WHERE ur.user_id=u.id
                    AND p.code IN ('paper:import','campaign:create','campaign:approve','campaign:send'))=4) AS allowed
                """).bind("actor",actor).map((r,m)->Boolean.TRUE.equals(r.get("allowed",Boolean.class))).one();
    }

    public Mono<ConfigSnapshot> configuration(UUID smtp,UUID template) {
        // All configuration inputs are locked through the run's mutation transaction.
        return db.sql("""
                SELECT s.lock_version AS smtp_lock,m.lock_version AS mailbox_lock,m.id AS mailbox_id,
                  v.id AS version_id,t.current_version,s.from_email,s.default_from_name,s.reply_to
                FROM smtp_accounts s JOIN email_templates t ON t.id=:template AND t.deleted_at IS NULL AND t.status='ACTIVE'
                JOIN email_template_versions v ON v.template_id=t.id AND v.version_number=t.current_version
                JOIN mailbox_accounts m ON m.id=(SELECT mb.id FROM mailbox_accounts mb
                  WHERE mb.protocol='IMAP' AND mb.enabled AND mb.last_test_status='SUCCEEDED'
                    AND mb.last_tested_at IS NOT NULL AND mb.last_tested_at>=mb.updated_at
                    AND lower(mb.username)=lower(s.reply_to) ORDER BY mb.id LIMIT 1)
                WHERE s.id=:smtp AND s.enabled AND s.last_test_status='SUCCEEDED'
                  AND s.last_tested_at IS NOT NULL AND s.last_tested_at>=s.updated_at
                FOR SHARE OF s,t,v,m
                """).bind("smtp",smtp).bind("template",template).map((r,m)->new ConfigSnapshot(
                        r.get("version_id",UUID.class),integer(r,"current_version"),r.get("mailbox_id",UUID.class),
                        number(r,"smtp_lock"),number(r,"mailbox_lock"),r.get("from_email",String.class),
                        r.get("default_from_name",String.class),r.get("reply_to",String.class))).one();
    }
    public Flux<OutreachDtos.SenderOption> senders() {
        return db.sql("""
                SELECT s.id,s.name,s.from_email,s.reply_to,
                 (s.enabled AND s.last_test_status='SUCCEEDED' AND s.last_tested_at IS NOT NULL AND s.last_tested_at>=s.updated_at
                  AND EXISTS(SELECT 1 FROM mailbox_accounts m WHERE m.protocol='IMAP' AND m.enabled
                    AND m.last_test_status='SUCCEEDED' AND m.last_tested_at IS NOT NULL AND m.last_tested_at>=m.updated_at
                    AND lower(m.username)=lower(s.reply_to))) AS available
                FROM smtp_accounts s ORDER BY s.name,s.id
                """).map((r,m)->new OutreachDtos.SenderOption(r.get("id",UUID.class),r.get("name",String.class),r.get("from_email",String.class),
                        r.get("reply_to",String.class),Boolean.TRUE.equals(r.get("available",Boolean.class)),
                        Boolean.TRUE.equals(r.get("available",Boolean.class))?"Enabled SMTP and matching monitored IMAP are currently tested":"Enable and test SMTP plus an IMAP mailbox matching Reply-To")).all();
    }
    public Flux<OutreachDtos.TemplateOption> templates() {
        return db.sql("SELECT id,name,current_version FROM email_templates WHERE deleted_at IS NULL AND status='ACTIVE' ORDER BY name,id")
                .map((r,m)->new OutreachDtos.TemplateOption(r.get("id",UUID.class),r.get("name",String.class),integer(r,"current_version"),true,
                        "Template content is checked before automatic approval; unsupported partner links block sending")).all();
    }
    public Mono<String> jobStatus(UUID job) { return db.sql("SELECT status FROM jobs WHERE id=:id").bind("id",job).map((r,m)->r.get("status",String.class)).one(); }
    public Flux<UUID> importManifest(UUID job) {
        return db.sql("""
                SELECT p.id FROM paper_imports pi JOIN papers p ON p.id=pi.paper_id
                WHERE pi.job_id=:job AND p.deleted_at IS NULL ORDER BY pi.imported_at,p.id
                """).bind("job",job).map((r,m)->r.get("id",UUID.class)).all();
    }
    public Mono<ExtractionState> extractionState(List<UUID> papers) {
        if(papers.isEmpty()) return Mono.just(new ExtractionState(List.of(),0,0,false));
        return db.sql("""
                SELECT p.id,e.status,e.completed_at,
                  (history.id IS NOT NULL AND history.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED')
                    AND history.id IS DISTINCT FROM e.job_id AND (e.started_at IS NULL OR history.created_at>e.started_at)) AS missing_outcome,
                  EXISTS(SELECT 1 FROM job_items ji JOIN jobs j ON j.id=ji.job_id
                    WHERE ji.external_key=p.id::text AND j.type IN ('ARXIV_FETCH_AND_PARSE_SOURCE','ARXIV_REEXTRACT_CONTACTS')
                      AND j.status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED')) AS active
                FROM papers p LEFT JOIN LATERAL (SELECT er.status,er.completed_at,er.started_at,er.job_id FROM extraction_runs er WHERE er.paper_id=p.id
                   ORDER BY er.started_at DESC,er.id DESC LIMIT 1) e ON true
                LEFT JOIN LATERAL(SELECT j.id,j.status,j.created_at FROM job_items ji JOIN jobs j ON j.id=ji.job_id
                    WHERE ji.external_key=p.id::text AND j.type IN ('ARXIV_FETCH_AND_PARSE_SOURCE','ARXIV_REEXTRACT_CONTACTS')
                    ORDER BY j.created_at DESC,j.id DESC LIMIT 1) history ON true
                WHERE p.id=ANY(:papers) AND p.deleted_at IS NULL ORDER BY p.id
                """).bind("papers",papers.toArray(UUID[]::new)).map((r,m)->new ExtractionRow(r.get("id",UUID.class),r.get("status",String.class),
                        r.get("completed_at",Instant.class)!=null,Boolean.TRUE.equals(r.get("active",Boolean.class)),Boolean.TRUE.equals(r.get("missing_outcome",Boolean.class)))).all().collectList()
                .map(rows->{
                    if(rows.size()!=papers.size()) throw new OutreachValidationException("A frozen paper is missing or deleted");
                    var pending=rows.stream().filter(r->r.status()==null && !r.active() && !r.missingOutcome()).map(ExtractionRow::id).toList();
                    int completed=(int)rows.stream().filter(ExtractionRow::completed).count();
                    int failed=(int)rows.stream().filter(r->r.missingOutcome() || r.status()!=null && !"RUNNING".equals(r.status())
                            && (!r.completed() || !"SUCCEEDED".equals(r.status()))).count();
                    boolean active=rows.stream().anyMatch(r->r.active() || "RUNNING".equals(r.status()));
                    return new ExtractionState(pending,completed,failed,active);
                });
    }
    public Mono<Boolean> campaignMatches(UUID id,RunRecord run) {
        return db.sql("""
                SELECT (template_version_id=:version AND smtp_account_id=:smtp AND mailbox_account_id=:mailbox
                  AND from_email=:fromEmail AND from_name=:fromName AND reply_to=:replyTo) AS matches
                FROM campaigns WHERE id=:id FOR UPDATE
                """).bind("id",id).bind("version",run.config().templateVersionId()).bind("smtp",run.smtpAccountId())
                .bind("mailbox",run.config().mailboxAccountId()).bind("fromEmail",run.config().fromEmail())
                .bind("fromName",run.config().fromName()).bind("replyTo",run.config().replyTo())
                .map((r,m)->Boolean.TRUE.equals(r.get("matches",Boolean.class))).one().defaultIfEmpty(false);
    }
    private static int integer(Row r,String field) { return ((Number)r.get(field)).intValue(); }
    private static long number(Row r,String field) { return ((Number)r.get(field)).longValue(); }
    private RunRecord map(Row r) {
        return new RunRecord(r.get("id",UUID.class),r.get("actor_id",UUID.class),r.get("payload_hash",byte[].class),r.get("name",String.class),
                r.get("target_mode",String.class),integer(r,"target_count"),r.get("criteria",String.class),r.get("purpose",String.class),
                r.get("request_context",String.class),r.get("smtp_account_id",UUID.class),r.get("template_id",UUID.class),
                new ConfigSnapshot(r.get("template_version_id",UUID.class),integer(r,"template_version"),r.get("mailbox_account_id",UUID.class),
                        number(r,"smtp_lock_version"),number(r,"mailbox_lock_version"),r.get("from_email",String.class),r.get("from_name",String.class),r.get("reply_to",String.class)),
                new Progress(r.get("status",String.class),r.get("stage",String.class),r.get("message",String.class),
                        List.copyOf(Arrays.asList(r.get("paper_ids",UUID[].class))),integer(r,"extracted_count"),integer(r,"eligible_count"),integer(r,"selected_count"),
                        integer(r,"smtp_accepted"),integer(r,"failed_count"),integer(r,"unknown_count"),r.get("campaign_id",UUID.class),
                        r.get("import_job_id",UUID.class),r.get("extraction_job_id",UUID.class),0),number(r,"lock_version"),r.get("created_at",Instant.class),r.get("updated_at",Instant.class));
    }
    public record ConfigSnapshot(UUID templateVersionId,int templateVersion,UUID mailboxAccountId,long smtpLockVersion,long mailboxLockVersion,
            String fromEmail,String fromName,String replyTo) { }
    public record Progress(String status,String stage,String message,List<UUID> paperIds,int extractedCount,int eligibleCount,int selectedCount,
            int smtpAccepted,int failedCount,int unknownCount,UUID campaignId,UUID importJobId,UUID extractionJobId,int delaySeconds) {
        public Progress stage(String status,String stage,String message,int delay) { return new Progress(status,stage,message,paperIds,extractedCount,eligibleCount,selectedCount,smtpAccepted,failedCount,unknownCount,campaignId,importJobId,extractionJobId,delay); }
    }
    public record RunRecord(UUID id,UUID actorId,byte[] payloadHash,String name,String targetMode,int targetCount,String criteriaJson,String purpose,
            String contextJson,UUID smtpAccountId,UUID templateId,ConfigSnapshot config,Progress progress,long lockVersion,Instant createdAt,Instant updatedAt) { }
    public record ExtractionState(List<UUID> pendingPaperIds,int completedCount,int failedCount,boolean active) { }
    private record ExtractionRow(UUID id,String status,boolean completed,boolean active,boolean missingOutcome) { }
}
