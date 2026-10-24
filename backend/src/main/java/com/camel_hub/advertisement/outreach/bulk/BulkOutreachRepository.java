package com.camel_hub.advertisement.outreach.bulk;


import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.MigrationReference;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.RecipientTransfer;

import com.camel_hub.advertisement.campaign.CampaignSelectionInventory;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.outreach.OutreachRunRepository.ConfigSnapshot;
import com.camel_hub.advertisement.outreach.OutreachValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Durable parent claims, manifests and contact reservations; all mutations run in caller transaction. */
public final class BulkOutreachRepository {
    private final DatabaseClient db;
    private final ObjectMapper mapper;
    private final CampaignSelectionInventory inventory;
    public BulkOutreachRepository(DatabaseClient db,ObjectMapper mapper) { this(db,mapper,CampaignSelectionInventory.unavailable()); }
    public BulkOutreachRepository(DatabaseClient db,ObjectMapper mapper,CampaignSelectionInventory inventory) { this.db=db;this.mapper=mapper;this.inventory=inventory; }
    public boolean inventoryAvailable() { return inventory.available(); }
    public Mono<Run> find(UUID id) { return query("SELECT * FROM bulk_outreach_runs WHERE id=:id").bind("id",id).map((r,m)->map(r)).one(); }
    public Mono<Run> lock(UUID id) { return query("SELECT * FROM bulk_outreach_runs WHERE id=:id FOR UPDATE").bind("id",id).map((r,m)->map(r)).one(); }
    public Mono<Run> lockNext() { return query("SELECT * FROM bulk_outreach_runs WHERE status IN ('ACTIVE','SENDING') AND next_step_at<=now() ORDER BY next_step_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED").map((r,m)->map(r)).one(); }
    public Mono<Run> findByKey(UUID actor,String key) { return query("SELECT * FROM bulk_outreach_runs WHERE actor_id=:actor AND idempotency_key=:key").bind("actor",actor).bind("key",key).map((r,m)->map(r)).one(); }
    public Flux<Run> list(int offset,int limit) { return query("SELECT * FROM bulk_outreach_runs ORDER BY created_at DESC,id OFFSET :offset LIMIT :limit").bind("offset",offset).bind("limit",limit).map((r,m)->map(r)).all(); }
    public Mono<Long> count() { return query("SELECT count(*) AS n FROM bulk_outreach_runs").map((r,m)->r.get("n",Long.class)).one(); }
    public Mono<Run> create(UUID actor,BulkOutreachCommand command,byte[] hash,ConfigSnapshot config,AuthenticationRequestContext context,State state) {
        return query("INSERT INTO bulk_outreach_runs(actor_id,idempotency_key,payload_hash,command,configuration,request_context,state) VALUES(:actor,:key,:hash,CAST(:command AS jsonb),CAST(:config AS jsonb),CAST(:context AS jsonb),CAST(:state AS jsonb)) ON CONFLICT(actor_id,idempotency_key) DO NOTHING RETURNING *")
                .bind("actor",actor).bind("key",command.idempotencyKey()).bind("hash",hash).bind("command",json(command)).bind("config",json(config)).bind("context",json(context)).bind("state",json(state)).map((r,m)->map(r)).one();
    }
    public Mono<Run> save(Run run,String status,String stage,String message,int delay) {
        return query("UPDATE bulk_outreach_runs SET state=CAST(:state AS jsonb),status=:status,stage=:stage,message=:message,lock_version=lock_version+1,updated_at=now(),next_step_at=now()+(:delay*interval '1 second') WHERE id=:id AND lock_version=:version RETURNING *")
                .bind("state",json(run.state())).bind("status",status).bind("stage",stage).bind("message",message).bind("delay",delay).bind("id",run.id()).bind("version",run.lockVersion()).map((r,m)->map(r)).one();
    }
    public Mono<Void> recordJob(UUID run,UUID job,String kind,int ordinal,ArxivSearchCriteria criteria,int budget) {
        var q=query("INSERT INTO bulk_outreach_jobs(run_id,job_id,kind,ordinal,criteria,budget) VALUES(:run,:job,:kind,:ordinal,CAST(:criteria AS jsonb),:budget)").bind("run",run).bind("job",job).bind("kind",kind).bind("ordinal",ordinal).bind("budget",budget);
        return (criteria==null?q.bindNull("criteria",String.class):q.bind("criteria",json(criteria))).fetch().rowsUpdated().then();
    }
    /** Existing source jobs are observed, never resubmitted. Budget is this parent's bounded job manifest. */
    public Mono<Void> recordExtractionJob(UUID run,UUID job,int manifestCount) {
        if(manifestCount<1||manifestCount>100) return Mono.error(new OutreachValidationException("Source job manifest exceeds 100 papers"));
        return query("INSERT INTO bulk_outreach_jobs(run_id,job_id,kind,ordinal,budget) SELECT :run,:job,'EXTRACTION',coalesce((SELECT max(ordinal)+1 FROM bulk_outreach_jobs WHERE run_id=:run AND kind='EXTRACTION'),0),:count WHERE NOT EXISTS(SELECT 1 FROM bulk_outreach_jobs WHERE run_id=:run AND job_id=:job) ON CONFLICT DO NOTHING")
                .bind("run",run).bind("job",job).bind("count",manifestCount).fetch().rowsUpdated().then();
    }
    public Mono<Integer> freezeImport(Run run,UUID job,int remaining) {
        if(remaining<1) return Mono.just(0);
        return query("""
                INSERT INTO bulk_outreach_papers(run_id,paper_id,arxiv_id)
                SELECT :run,source.id,source.arxiv_id FROM (
                  SELECT DISTINCT ON (regexp_replace(p.arxiv_id,'v[0-9]+$','')) p.id,regexp_replace(p.arxiv_id,'v[0-9]+$','') AS arxiv_id,pi.imported_at
                  FROM paper_imports pi JOIN papers p ON p.id=pi.paper_id
                  WHERE pi.job_id=:job AND p.deleted_at IS NULL
                  AND NOT EXISTS(SELECT 1 FROM bulk_outreach_papers own WHERE own.run_id=:run AND own.arxiv_id=regexp_replace(p.arxiv_id,'v[0-9]+$',''))
                  AND (NOT :incremental OR NOT EXISTS(SELECT 1 FROM bulk_outreach_papers prior WHERE prior.run_id<>:run AND prior.arxiv_id=regexp_replace(p.arxiv_id,'v[0-9]+$','')))
                  ORDER BY regexp_replace(p.arxiv_id,'v[0-9]+$',''),pi.imported_at,p.id
                ) source ORDER BY source.imported_at,source.id LIMIT :remaining ON CONFLICT DO NOTHING
                """).bind("run",run.id()).bind("job",job).bind("incremental",run.command().incremental()).bind("remaining",remaining).fetch().rowsUpdated().map(Math::toIntExact);
    }
    public Mono<Integer> paperCount(UUID run) { return query("SELECT count(*) AS n FROM bulk_outreach_papers WHERE run_id=:run").bind("run",run).map((r,m)->Math.toIntExact(r.get("n",Long.class))).one(); }
    public Mono<Integer> importCount(UUID job) { return query("SELECT count(*) AS n FROM paper_imports WHERE job_id=:job").bind("job",job).map((r,m)->Math.toIntExact(r.get("n",Long.class))).one(); }
    public Mono<LocalDate> importOldest(UUID job) { return query("SELECT (min(p.submitted_at) AT TIME ZONE 'UTC')::date AS oldest FROM paper_imports pi JOIN papers p ON p.id=pi.paper_id WHERE pi.job_id=:job").bind("job",job).map((r,m)->Optional.ofNullable(r.get("oldest",LocalDate.class))).one().flatMap(value->Mono.justOrEmpty(value)); }
    public Flux<PaperOutcome> extractionOutcomes(UUID run) {
        return readExtractionOutcomes(run,null);
    }
    /** Match the source API's UUID lock order, for one bounded candidate batch only. */
    public Mono<Void> lockSourceCandidates(List<UUID> ids) {
        if(!validSourceCandidates(ids)) return Mono.error(new OutreachValidationException("Source candidate batch must contain 1..100 distinct papers"));
        return query("SELECT p.id FROM papers p WHERE p.id=ANY(:papers) ORDER BY p.id FOR UPDATE OF p")
                .bind("papers",ids.toArray(UUID[]::new)).map((r,m)->r.get("id",UUID.class)).all().then();
    }
    public Flux<PaperOutcome> sourceCandidateOutcomes(UUID run,List<UUID> ids) {
        if(!validSourceCandidates(ids)) return Flux.error(new OutreachValidationException("Source candidate batch must contain 1..100 distinct papers"));
        return readExtractionOutcomes(run,ids);
    }
    private boolean validSourceCandidates(List<UUID> ids) { return ids!=null&&!ids.isEmpty()&&ids.size()<=100&&!ids.contains(null)&&new HashSet<>(ids).size()==ids.size(); }
    private Flux<PaperOutcome> readExtractionOutcomes(UUID run,List<UUID> ids) {
        var q=query("""
                SELECT bp.paper_id,e.id AS extraction_id,coalesce(active.id,e.job_id,history.id) AS job_id,
                  CASE WHEN p.deleted_at IS NOT NULL THEN 'MISSING'
                    WHEN active.id IS NOT NULL THEN 'ACTIVE'
                    WHEN e.status='SUCCEEDED' AND e.started_at IS NOT NULL AND e.completed_at IS NOT NULL AND NOT coalesce(history.created_at>e.started_at AND history.id IS DISTINCT FROM e.job_id,false) THEN 'SUCCEEDED'
                    WHEN e.status IS NOT NULL THEN CASE WHEN e.status IN ('RUNNING','SUCCEEDED') THEN 'INCONSISTENT' ELSE e.status END
                    WHEN history.id IS NOT NULL THEN 'MISSING_OUTCOME' ELSE 'PENDING' END AS outcome
                FROM bulk_outreach_papers bp JOIN papers p ON p.id=bp.paper_id
                LEFT JOIN LATERAL(SELECT er.* FROM extraction_runs er WHERE er.paper_id=p.id ORDER BY er.started_at DESC,er.id DESC LIMIT 1) e ON true
                LEFT JOIN LATERAL(SELECT j.id FROM job_items ji JOIN jobs j ON j.id=ji.job_id WHERE ji.external_key=p.id::text
                  AND j.type IN ('ARXIV_FETCH_AND_PARSE_SOURCE','ARXIV_REEXTRACT_CONTACTS') AND j.status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED') ORDER BY j.created_at DESC,j.id DESC LIMIT 1) active ON true
                LEFT JOIN LATERAL(SELECT j.id,j.created_at FROM job_items ji JOIN jobs j ON j.id=ji.job_id WHERE ji.external_key=p.id::text
                  AND j.type IN ('ARXIV_FETCH_AND_PARSE_SOURCE','ARXIV_REEXTRACT_CONTACTS') ORDER BY j.created_at DESC,j.id DESC LIMIT 1) history ON true
                WHERE bp.run_id=:run
                """+(ids==null?"":" AND bp.paper_id=ANY(:papers)")+" ORDER BY bp.paper_id").bind("run",run);
        if(ids!=null) q=q.bind("papers",ids.toArray(UUID[]::new));
        return q.map((r,m)->new PaperOutcome(r.get("paper_id",UUID.class),r.get("outcome",String.class),r.get("job_id",UUID.class),r.get("extraction_id",UUID.class))).all();
    }
    public Mono<Void> saveOutcome(UUID run,PaperOutcome paper) {
        var q=query("UPDATE bulk_outreach_papers SET outcome=:outcome,extraction_job_id=coalesce(:job,extraction_job_id),extraction_run_id=:extraction,updated_at=now() WHERE run_id=:run AND paper_id=:paper").bind("run",run).bind("paper",paper.paperId()).bind("outcome",paper.outcome());
        q=paper.jobId()==null?q.bindNull("job",UUID.class):q.bind("job",paper.jobId());q=paper.extractionId()==null?q.bindNull("extraction",UUID.class):q.bind("extraction",paper.extractionId());return q.fetch().rowsUpdated().then();
    }
    public Flux<UUID> safePapers(UUID run) { return query("SELECT paper_id FROM bulk_outreach_papers WHERE run_id=:run AND outcome='SUCCEEDED' ORDER BY paper_id").bind("run",run).map((r,m)->r.get("paper_id",UUID.class)).all(); }
    public Mono<Void> excludeContent(UUID run,List<UUID> ids) {
        if(ids.isEmpty()) return Mono.empty();
        return query("UPDATE bulk_outreach_papers SET outcome='CONTENT_REJECTED',no_contact=false,updated_at=now() WHERE run_id=:run AND paper_id=ANY(:papers)")
                .bind("run",run).bind("papers",ids.toArray(UUID[]::new)).fetch().rowsUpdated().then();
    }
    public Mono<Void> associate(UUID run,UUID campaign,int selected) {
        return query("INSERT INTO bulk_outreach_children(run_id,campaign_id,selected_count) VALUES(:run,:campaign,:selected)").bind("run",run).bind("campaign",campaign).bind("selected",selected).fetch().rowsUpdated()
                .then(query("INSERT INTO bulk_outreach_reservations(email_hmac,run_id,campaign_id,recipient_id) SELECT email_hmac,:run,:campaign,id FROM campaign_recipients WHERE campaign_id=:campaign").bind("run",run).bind("campaign",campaign).fetch().rowsUpdated())
                .flatMap(count->count==selected?Mono.<Void>empty():Mono.error(new OutreachValidationException("Bulk reservation count differs from frozen selection")));
    }
    public Flux<Child> children(UUID run) { return query("SELECT * FROM bulk_outreach_children WHERE run_id=:run ORDER BY created_at,campaign_id").bind("run",run).map((r,m)->new Child(r.get("campaign_id",UUID.class),r.get("status",String.class),r.get("stage",String.class),integer(r,"selected_count"),integer(r,"smtp_accepted"),integer(r,"bounced_count"),integer(r,"failed_count"),integer(r,"unknown_count"),r.get("message",String.class))).all(); }
    /** Read-only outcomes remain live after cancellation/completion; control state never resumes.
     * The reservation outcome is an observational checkpoint, not the delivery authority. */
    public Flux<Child> liveChildren(UUID run) {
        return inventory.bind(query("""
                SELECT bc.*,delivery.accepted AS live_accepted,delivery.bounced AS live_bounced,
                  delivery.failed AS live_failed,delivery.unknown AS live_unknown
                """ + CampaignSelectionInventory.COLUMNS + """
                FROM bulk_outreach_children bc CROSS JOIN LATERAL (
                  SELECT count(*) FILTER(WHERE cr.status IN ('SMTP_ACCEPTED','BOUNCED')) AS accepted,
                    count(*) FILTER(WHERE cr.status='BOUNCED') AS bounced,
                    count(*) FILTER(WHERE cr.status IN ('PERMANENT_FAILURE','SUPPRESSED','UNSUBSCRIBED','CANCELED')) AS failed,
                    count(*) FILTER(WHERE cr.status='OUTCOME_UNKNOWN') AS unknown
                  FROM campaign_recipients cr WHERE cr.campaign_id=bc.campaign_id
                ) delivery
                """ + inventory.join("bc.campaign_id") + """
                WHERE bc.run_id=:run ORDER BY bc.created_at,bc.campaign_id
                """).bind("run",run)).map((r,m)->new Child(r.get("campaign_id",UUID.class),r.get("status",String.class),r.get("stage",String.class),integer(r,"selected_count"),integer(r,"live_accepted"),integer(r,"live_bounced"),integer(r,"live_failed"),integer(r,"live_unknown"),r.get("message",String.class),CampaignSelectionInventory.counts(r),CampaignSelectionInventory.references(r))).all();
    }
    public Mono<Void> childProgress(Child child,String status,String stage,String message,int accepted,int bounced,int failed,int unknown) {
        return query("UPDATE bulk_outreach_children SET status=:status,stage=:stage,message=:message,smtp_accepted=:accepted,bounced_count=:bounced,failed_count=:failed,unknown_count=:unknown,updated_at=now() WHERE campaign_id=:id")
                .bind("id",child.id()).bind("status",status).bind("stage",stage).bind("message",message).bind("accepted",accepted).bind("bounced",bounced).bind("failed",failed).bind("unknown",unknown).fetch().rowsUpdated()
                .then(query("UPDATE bulk_outreach_reservations br SET outcome=cr.status,updated_at=now() FROM campaign_recipients cr WHERE br.recipient_id=cr.id AND br.campaign_id=:id").bind("id",child.id()).fetch().rowsUpdated()).then();
    }
    public Mono<Boolean> campaignMatches(UUID id,Run run) {
        var c=run.config();return query("SELECT(template_version_id=:template AND smtp_account_id=:smtp AND mailbox_account_id=:mailbox AND from_email=:email AND from_name=:name AND reply_to=:reply) AS matches FROM campaigns WHERE id=:id FOR UPDATE")
                .bind("template",c.templateVersionId()).bind("smtp",run.command().smtpAccountId()).bind("mailbox",c.mailboxAccountId()).bind("email",c.fromEmail()).bind("name",c.fromName()).bind("reply",c.replyTo()).bind("id",id).map((r,m)->Boolean.TRUE.equals(r.get("matches",Boolean.class))).one().defaultIfEmpty(false);
    }
    public Mono<int[]> paperCounts(UUID run) {
        return query("SELECT count(*) AS total,count(*) FILTER(WHERE outcome='SUCCEEDED') AS extracted,count(*) FILTER(WHERE outcome NOT IN ('PENDING','ACTIVE','SUCCEEDED')) AS excluded,count(*) FILTER(WHERE no_contact) AS no_contact FROM bulk_outreach_papers WHERE run_id=:run")
                .bind("run",run).map((r,m)->new int[]{integer(r,"total"),integer(r,"extracted"),integer(r,"excluded"),integer(r,"no_contact")}).one();
    }
    public Mono<Void> markNoContact(UUID run,List<UUID> ids) {
        if(ids.isEmpty()) return Mono.empty();
        return query("UPDATE bulk_outreach_papers SET no_contact=true WHERE run_id=:run AND paper_id=ANY(:papers)").bind("run",run).bind("papers",ids.toArray(UUID[]::new)).fetch().rowsUpdated().then();
    }
    public Mono<Void> refreshNoContact(UUID run) {
        return query("UPDATE bulk_outreach_papers bp SET no_contact=(bp.outcome='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM paper_author_contacts pac WHERE pac.paper_id=bp.paper_id AND pac.extraction_run_id=bp.extraction_run_id)) WHERE bp.run_id=:run")
                .bind("run",run).fetch().rowsUpdated().then();
    }
    private DatabaseClient.GenericExecuteSpec query(String sql) { return db.sql(sql); }
    private Run map(Row r) { return new Run(r.get("id",UUID.class),r.get("actor_id",UUID.class),r.get("payload_hash",byte[].class),read(r.get("command",String.class),BulkOutreachCommand.class),read(r.get("configuration",String.class),ConfigSnapshot.class),read(r.get("request_context",String.class),AuthenticationRequestContext.class),read(r.get("state",String.class),State.class),r.get("status",String.class),r.get("stage",String.class),r.get("message",String.class),((Number)r.get("lock_version")).longValue(),r.get("created_at",Instant.class),r.get("updated_at",Instant.class)); }
    private static int integer(Row r,String name) { return ((Number)r.get(name)).intValue(); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new OutreachValidationException("Bulk configuration serialization failed");} }
    private <T>T read(String value,Class<T> type) { try{return mapper.readValue(value,type);}catch(Exception e){throw new OutreachValidationException("Persisted bulk configuration is invalid");} }
    public record Run(UUID id,UUID actorId,byte[] payloadHash,BulkOutreachCommand command,ConfigSnapshot config,AuthenticationRequestContext context,State state,String status,String stage,String message,long lockVersion,Instant createdAt,Instant updatedAt) { }
    public static final class State {
        public List<Shard> shards=new ArrayList<>(); public int shardIndex; public int rawBudget; public int extractionOrdinal;
        public UUID extractionJob; public boolean selectionComplete; public String shortfall="";
        public State() { }
    }
    public record Shard(ArxivSearchCriteria criteria,int budget,UUID jobId) { }
    public record PaperOutcome(UUID paperId,String outcome,UUID jobId,UUID extractionId) { }
    public record Child(UUID id,String status,String stage,int selectedCount,int smtpAccepted,int bouncedCount,int failedCount,int unknownCount,String message,SelectionCounts selectionCounts,List<MigrationReference> migrationReferences) {
        public Child(UUID id,String status,String stage,int selectedCount,int smtpAccepted,int bouncedCount,int failedCount,int unknownCount,String message) {
            this(id,status,stage,selectedCount,smtpAccepted,bouncedCount,failedCount,unknownCount,message,null,null);
        }
    }
}
