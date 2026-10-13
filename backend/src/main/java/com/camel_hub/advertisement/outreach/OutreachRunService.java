package com.camel_hub.advertisement.outreach;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionService;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.camel_hub.advertisement.outreach.OutreachRunRepository.*;

/** Durable, one-transaction-per-step orchestration. SMTP is never called here. */
public final class OutreachRunService {
    private static final Set<String> JOB_TERMINAL=Set.of("SUCCEEDED","PARTIALLY_SUCCEEDED","FAILED","CANCELED");
    private static final Set<String> ACTIVE=Set.of("ACTIVE","SENDING");
    private final OutreachRunRepository repository;
    private final ArxivImportService imports;
    private final SourceExtractionService extractions;
    private final OutreachTemplateCampaignService adapter;
    private final CampaignService campaigns;
    private final CampaignPreflightService preflight;
    private final CampaignWorkflowService workflow;
    private final AuditService audit;
    private final SensitiveValueHasher hasher;
    private final ObjectMapper mapper;
    private final OutreachProperties properties;
    private final TransactionalOperator tx;

    public OutreachRunService(OutreachRunRepository repository,ArxivImportService imports,SourceExtractionService extractions,
            OutreachTemplateCampaignService adapter,CampaignService campaigns,CampaignPreflightService preflight,
            CampaignWorkflowService workflow,AuditService audit,SensitiveValueHasher hasher,ObjectMapper mapper,
            OutreachProperties properties,TransactionalOperator tx) {
        this.repository=repository;this.imports=imports;this.extractions=extractions;this.adapter=adapter;this.campaigns=campaigns;
        this.preflight=preflight;this.workflow=workflow;this.audit=audit;this.hasher=hasher;
        this.mapper=mapper.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.properties=properties;this.tx=tx;
    }

    public Mono<OutreachDtos.RunView> create(UUID actor,OutreachDtos.CreateRequest raw,AuthenticationRequestContext context) {
        return Mono.defer(()->{
            var command=validate(raw);
            byte[] hash=hash(json(Map.of("name",command.name(),"targetMode",command.targetMode(),"targetCount",command.targetCount(),
                    "smtpAccountId",command.smtpAccountId(),"templateId",command.templateId(),"purpose",command.purpose(),
                    "criteria",command.criteria(),"confirmation",command.confirmation())));
            return repository.findByKey(actor,command.idempotencyKey()).flatMap(existing->deduplicate(existing,hash))
                    .switchIfEmpty(Mono.defer(()->authorize(actor).then(repository.configuration(command.smtpAccountId(),command.templateId()))
                            .switchIfEmpty(Mono.error(new OutreachValidationException("Choose an active template and enabled, currently tested SMTP with matching monitored IMAP")))
                            .flatMap(config->repository.create(actor,command,hash,json(command.criteria()),json(safeContext(context)),config))
                            .switchIfEmpty(repository.findByKey(actor,command.idempotencyKey()).flatMap(existing->deduplicate(existing,hash)))
                            .flatMap(run->audit(run,"OUTREACH_RUN_CREATED",safeContext(context),Map.of("targetMode",run.targetMode(),"targetCount",run.targetCount()))
                                    .thenReturn(run))))
                    .map(this::view).as(tx::transactional);
        });
    }
    private Mono<RunRecord> deduplicate(RunRecord run,byte[] hash) {
        return MessageDigest.isEqual(run.payloadHash(),hash)?Mono.just(run):Mono.error(new OutreachConflictException("Idempotency key already has a different request payload"));
    }
    public Mono<OutreachDtos.RunView> get(UUID id) { return repository.find(id).switchIfEmpty(notFound()).map(this::view); }
    public Mono<PageResponse<OutreachDtos.RunView>> list(int page,int pageSize) {
        if(page<1||pageSize<1||pageSize>100) return Mono.error(new OutreachValidationException("Page must be positive and pageSize between 1 and 100"));
        int offset;
        try { offset=Math.multiplyExact(page-1,pageSize); } catch(ArithmeticException e) { return Mono.error(new OutreachValidationException("Page is too large")); }
        return Mono.zip(repository.list(offset,pageSize).map(this::view).collectList(),repository.count())
                .map(result->PageResponse.of(result.getT1(),page,pageSize,result.getT2()));
    }
    public Mono<OutreachDtos.Options> options() { return Mono.zip(repository.senders().collectList(),repository.templates().collectList())
            .map(result->new OutreachDtos.Options(result.getT1(),result.getT2(),new OutreachDtos.Limits(120,100,100),
                    properties.getProductionHoldReason().isBlank()?null:properties.getProductionHoldReason())); }

    public Mono<OutreachDtos.RunView> cancel(UUID id,UUID actor,long expectedVersion,AuthenticationRequestContext context) {
        return repository.lock(id).switchIfEmpty(notFound()).flatMap(run->{
            if(run.lockVersion()!=expectedVersion) return Mono.error(new OutreachConflictException("Outreach run changed; refresh before canceling"));
            if(!ACTIVE.contains(run.progress().status())) return Mono.error(new OutreachConflictException("Outreach run is already terminal"));
            Mono<Void> cancelCampaign=run.progress().campaignId()==null?Mono.empty():campaigns.get(run.progress().campaignId()).flatMap(campaign->
                    Set.of("SCHEDULED","RUNNING","PAUSED").contains(campaign.status())
                            ?workflow.cancel(campaign.id(),actor,safeContext(context),campaign.lockVersion()).then():Mono.empty());
            return cancelCampaign.then(save(run,run.progress().stage("CANCELED",run.progress().stage(),"Canceled; previously accepted or in-flight SMTP cannot be recalled",0),actor,safeContext(context)))
                    .map(this::view);
        }).as(tx::transactional);
    }

    /** The run lock, child jobs/campaign, outbox writes, and stage transition commit together.
     * Any error rolls the whole step back before a separate transaction makes it terminal. */
    public Mono<Boolean> advanceOne() {
        return Mono.defer(()->{
            var claimed=new AtomicReference<UUID>();
            return repository.lockNext().flatMap(run->{claimed.set(run.id());return step(run).thenReturn(true);})
                    .defaultIfEmpty(false).as(tx::transactional)
                    .onErrorResume(error->{
                        UUID id=claimed.get();
                        if(id==null) return Mono.error(error);
                        return repository.lock(id).flatMap(run->{
                            if(!ACTIVE.contains(run.progress().status())) return Mono.just(false);
                            String message=safeFailure(error);
                            return save(run,run.progress().stage("BLOCKED",run.progress().stage(),message,0)).thenReturn(true);
                        }).defaultIfEmpty(false).as(tx::transactional);
                    });
        });
    }
    private Mono<RunRecord> step(RunRecord run) {
        // Reading delivery progress does not mutate the campaign or replay unknown outcomes.
        if("SENDING".equals(run.progress().stage())) return deliveryProgress(run);
        return authorize(run.actorId()).then(Mono.defer(()->switch(run.progress().stage()) {
            case "IMPORT" -> startImport(run);
            case "WAIT_IMPORT" -> waitImport(run);
            case "EXTRACT" -> startExtraction(run);
            case "WAIT_EXTRACTION" -> waitExtraction(run);
            case "PREPARE" -> prepare(run);
            case "REVIEW" -> advanceCampaign(run,"REVIEW");
            case "APPROVE" -> advanceCampaign(run,"APPROVE");
            case "START" -> advanceCampaign(run,"START");
            default -> Mono.error(new OutreachValidationException("Unsupported persisted outreach stage"));
        }));
    }
    private Mono<RunRecord> startImport(RunRecord run) {
        int budget="PAPERS".equals(run.targetMode())?run.targetCount():100;
        // The existing import service intentionally reuses an actor-scoped identical criteria job.
        return imports.createImport(run.actorId(),new ArxivImportService.ImportCommand(List.of(),read(run.criteriaJson(),ArxivSearchCriteria.class),budget),context(run))
                .flatMap(job->{var p=run.progress(); return save(run,new Progress("ACTIVE","WAIT_IMPORT","Waiting for bounded metadata import",
                        p.paperIds(),p.extractedCount(),p.eligibleCount(),p.selectedCount(),p.smtpAccepted(),p.failedCount(),p.unknownCount(),
                        p.campaignId(),job.jobId(),p.extractionJobId(),0));});
    }
    private Mono<RunRecord> waitImport(RunRecord run) {
        return repository.jobStatus(run.progress().importJobId()).switchIfEmpty(Mono.error(new OutreachValidationException("Import job is missing")))
                .flatMap(status->{
                    if(!JOB_TERMINAL.contains(status)) return save(run,run.progress().stage("ACTIVE","WAIT_IMPORT","Waiting for metadata import",3));
                    if(!"SUCCEEDED".equals(status)) return Mono.error(new OutreachValidationException("Metadata import did not succeed: "+status));
                    return repository.importManifest(run.progress().importJobId()).collectList().flatMap(papers->{
                        if(papers.isEmpty()||papers.size()>100) return Mono.error(new OutreachValidationException("Import manifest must contain 1–100 active papers"));
                        if("PAPERS".equals(run.targetMode())&&papers.size()!=run.targetCount()) return Mono.error(new OutreachValidationException("Imported paper count does not match requested target"));
                        var p=run.progress();return save(run,new Progress("ACTIVE","EXTRACT","Imported manifest frozen",papers,0,0,0,0,0,0,null,p.importJobId(),null,0));
                    });
                });
    }
    private Mono<RunRecord> startExtraction(RunRecord run) {
        return repository.extractionState(run.progress().paperIds()).flatMap(state->{
            var p=run.progress();
            if(state.failedCount()>0) return save(run,new Progress("BLOCKED","EXTRACT","Extraction contains failed, partial, unsafe, unavailable or inconsistent completion metadata; manual reconciliation required",
                    p.paperIds(),state.completedCount(),0,0,0,0,0,null,p.importJobId(),p.extractionJobId(),0));
            if(state.active()) return Mono.error(new OutreachValidationException("A manifest paper has an active extraction; no duplicate extraction submitted"));
            if(state.pendingPaperIds().isEmpty()) return save(run,new Progress("ACTIVE","PREPARE","Completed extraction reused",p.paperIds(),state.completedCount(),0,0,0,0,0,null,p.importJobId(),p.extractionJobId(),0));
            return extractions.create(run.actorId(),state.pendingPaperIds(),context(run)).flatMap(job->save(run,new Progress("ACTIVE","WAIT_EXTRACTION","Waiting for source extraction",
                    p.paperIds(),state.completedCount(),0,0,0,0,0,null,p.importJobId(),job.jobId(),0)));
        });
    }
    private Mono<RunRecord> waitExtraction(RunRecord run) {
        return repository.jobStatus(run.progress().extractionJobId()).switchIfEmpty(Mono.error(new OutreachValidationException("Extraction job is missing")))
                .flatMap(status->repository.extractionState(run.progress().paperIds()).flatMap(state->{
                    var p=run.progress();
                    if(!JOB_TERMINAL.contains(status)) return save(run,new Progress("ACTIVE","WAIT_EXTRACTION","Waiting for source extraction",p.paperIds(),state.completedCount(),0,0,0,0,0,null,p.importJobId(),p.extractionJobId(),3));
                    if(!"SUCCEEDED".equals(status)||state.failedCount()>0||state.active()||!state.pendingPaperIds().isEmpty()||state.completedCount()!=p.paperIds().size())
                        return save(run,new Progress("BLOCKED","WAIT_EXTRACTION","Extraction did not completely succeed; no automatic retry or no-email substitution",p.paperIds(),state.completedCount(),0,0,0,0,0,null,p.importJobId(),p.extractionJobId(),0));
                    return save(run,new Progress("ACTIVE","PREPARE","All manifest papers extracted",p.paperIds(),state.completedCount(),0,0,0,0,0,null,p.importJobId(),p.extractionJobId(),0));
                }));
    }
    private Mono<RunRecord> prepare(RunRecord run) {
        return verifyConfiguration(run).then(adapter.eligibleCount(run.progress().paperIds())).flatMap(eligible->{
            var p=run.progress();
            if("EMAILS".equals(run.targetMode())&&eligible<run.targetCount()) return save(run,new Progress("BLOCKED","PREPARE","Not enough eligible unique addresses within the 100-paper search budget",
                    p.paperIds(),p.extractedCount(),eligible,0,0,0,0,null,p.importJobId(),p.extractionJobId(),0));
            if("PAPERS".equals(run.targetMode())&&eligible>120) return save(run,new Progress("BLOCKED","PREPARE","Paper manifest exceeds 120 eligible unique addresses; no silent truncation",
                    p.paperIds(),p.extractedCount(),eligible,0,0,0,0,null,p.importJobId(),p.extractionJobId(),0));
            if(eligible==0) return save(run,new Progress("COMPLETED","DONE","Requested papers extracted; no eligible contacts to send",p.paperIds(),p.extractedCount(),0,0,0,0,0,null,p.importJobId(),p.extractionJobId(),0));
            Integer exact="EMAILS".equals(run.targetMode())?run.targetCount():null;
            int selected=exact==null?eligible:exact;
            return adapter.prepare(run.actorId(),run.id(),run.templateId(),run.smtpAccountId(),run.config().mailboxAccountId(),run.purpose(),p.paperIds(),exact)
                    .flatMap(campaign->repository.campaignMatches(campaign.id(),run).flatMap(matches->{
                        // A changed template or sender must roll back prepare, not freeze a different version.
                        if(!matches) return Mono.error(new OutreachValidationException("Prepared campaign does not use the frozen template and sender configuration"));
                        return save(run,new Progress("ACTIVE","REVIEW","Exact membership and deterministic template content frozen",p.paperIds(),p.extractedCount(),eligible,selected,0,0,0,campaign.id(),p.importJobId(),p.extractionJobId(),0));
                    }));
        });
    }
    private Mono<RunRecord> advanceCampaign(RunRecord run,String action) {
        UUID id=run.progress().campaignId();
        return verifyConfiguration(run).then(repository.campaignMatches(id,run)).flatMap(matches->{
            if(!matches) return Mono.error(new OutreachValidationException("Campaign differs from frozen template or sender configuration"));
            return adapter.review(id).then(preflight.preflight(id));
        }).flatMap(result->{
            requireStrictPreflight(result,run.progress().selectedCount());
            if("START".equals(action)&&!properties.getProductionHoldReason().isBlank())
                return Mono.error(new OutreachValidationException("Production sending hold: "+properties.getProductionHoldReason()));
            return campaigns.get(id).flatMap(campaign->{
                Mono<CampaignService.CampaignView> mutation=switch(action) {
                    case "REVIEW" -> workflow.submitReview(id,run.actorId(),context(run),campaign.lockVersion());
                    case "APPROVE" -> workflow.approve(id,run.actorId(),context(run),campaign.lockVersion());
                    case "START" -> workflow.start(id,run.actorId(),context(run),campaign.lockVersion());
                    default -> Mono.error(new OutreachValidationException("Unsupported campaign stage"));
                };
                // The normal workflow accepts a positive eligible subset. Automatic outreach
                // promises the exact frozen count, so recheck after that mutation too, while
                // its status and START outbox are still rollbackable in this outer transaction.
                return mutation.then(preflight.preflight(id)).flatMap(after->{
                    requireStrictPreflight(after,run.progress().selectedCount());
                    return save(run,run.progress().stage("START".equals(action)?"SENDING":"ACTIVE",
                            switch(action){case "REVIEW"->"APPROVE";case "APPROVE"->"START";default->"SENDING";},
                            "START".equals(action)?"Sending through normal campaign delivery and unchanged quotas":"Normal campaign "+action.toLowerCase()+" completed",0));
                });
            });
        });
    }
    public static void requireStrictPreflight(CampaignPreflightService.PreflightView view,int frozenCount) {
        if(frozenCount<1||frozenCount>120||!view.ready()||view.counts().getOrDefault("TOTAL",-1L)!=frozenCount
                ||view.counts().getOrDefault("ELIGIBLE",-1L)!=frozenCount)
            throw new OutreachValidationException("Strict preflight requires TOTAL = ELIGIBLE = frozen selected count");
    }
    private Mono<RunRecord> deliveryProgress(RunRecord run) {
        return campaigns.get(run.progress().campaignId()).flatMap(campaign->{
            var p=run.progress();var counts=campaign.deliveryCounts();
            int accepted=counts.smtpAccepted()+counts.bounced();
            int failed=counts.permanentFailure()+counts.bounced()+counts.suppressed()+counts.unsubscribed()+counts.canceled();
            int unknown=counts.outcomeUnknown();
            String status="SENDING",stage="SENDING",message="Sending; quota waits remain active";
            if("CANCELED".equals(campaign.status())) { status="CANCELED";stage="DONE";message="Campaign canceled; accepted mail cannot be recalled"; }
            else if("COMPLETED".equals(campaign.status())) { status=unknown>0||failed>0?"FAILED":"COMPLETED";stage="DONE";message="Campaign completed; SMTP acceptance is not inbox delivery"; }
            else if(!"RUNNING".equals(campaign.status())) { status="BLOCKED";message="Campaign no longer running; manual reconciliation required"; }
            return save(run,new Progress(status,stage,message,p.paperIds(),p.extractedCount(),p.eligibleCount(),p.selectedCount(),accepted,failed,unknown,p.campaignId(),p.importJobId(),p.extractionJobId(),3));
        });
    }
    private Mono<Void> verifyConfiguration(RunRecord run) {
        return repository.configuration(run.smtpAccountId(),run.templateId())
                .filter(config->config.equals(run.config()))
                .switchIfEmpty(Mono.error(new OutreachValidationException("Frozen template version or tested sender/mailbox configuration changed"))).then();
    }
    private Mono<Void> authorize(UUID actor) { return repository.authorized(actor).flatMap(allowed->allowed?Mono.empty():
            Mono.error(new OutreachValidationException("Actor no longer has paper:import, campaign:create, campaign:approve and campaign:send permissions"))); }
    private Mono<RunRecord> save(RunRecord run,Progress p) { return save(run,p,run.actorId(),context(run)); }
    private Mono<RunRecord> save(RunRecord run,Progress p,UUID actor,AuthenticationRequestContext context) {
        return repository.progress(run,p).switchIfEmpty(Mono.error(new OutreachConflictException("Outreach run changed")))
                .flatMap(saved->audit(saved,"OUTREACH_RUN_"+p.stage(),actor,context,Map.of("status",p.status(),"stage",p.stage(),"selectedCount",p.selectedCount())).thenReturn(saved));
    }
    private Mono<Void> audit(RunRecord run,String action,AuthenticationRequestContext context,Map<String,Object> after) { return audit(run,action,run.actorId(),context,after); }
    private Mono<Void> audit(RunRecord run,String action,UUID actor,AuthenticationRequestContext context,Map<String,Object> after) {
        return audit.record(new AuditEvent(actor,action,"OUTREACH_RUN",run.id().toString(),hasher.hash(context.ipAddress()),context.userAgentSummary(),context.traceId(),Map.of(),after,AuditResult.SUCCESS,null));
    }
    private OutreachDtos.RunView view(RunRecord run) { var p=run.progress();return new OutreachDtos.RunView(run.id(),run.name(),run.targetMode(),run.targetCount(),p.status(),p.stage(),p.message(),p.paperIds().size(),
            p.extractedCount(),p.eligibleCount(),p.selectedCount(),p.smtpAccepted(),p.failedCount(),p.unknownCount(),p.campaignId(),p.importJobId(),p.extractionJobId(),run.smtpAccountId(),run.templateId(),run.lockVersion(),run.createdAt(),run.updatedAt(),ACTIVE.contains(p.status())?List.of("CANCEL"):List.of()); }
    private OutreachDtos.CreateRequest validate(OutreachDtos.CreateRequest value) {
        if(value==null||!"AUTO_OUTREACH_SEND".equals(value.confirmation())) throw new OutreachValidationException("Explicit AUTO_OUTREACH_SEND confirmation is required");
        if(!Set.of("EMAILS","PAPERS").contains(value.targetMode()==null?"":value.targetMode())||value.targetCount()<1
                ||value.targetCount()>("EMAILS".equals(value.targetMode())?120:100)) throw new OutreachValidationException("Target must be EMAILS 1–120 or PAPERS 1–100");
        if(value.smtpAccountId()==null||value.templateId()==null||value.criteria()==null) throw new OutreachValidationException("Search criteria, SMTP account and template are required");
        String key=value.idempotencyKey()==null?"":value.idempotencyKey();
        if(!key.matches("[A-Za-z0-9._:-]{8,128}")) throw new OutreachValidationException("Idempotency key must contain 8–128 safe characters");
        return new OutreachDtos.CreateRequest(text(value.name(),200,"Name"),value.targetMode(),value.targetCount(),value.smtpAccountId(),value.templateId(),text(value.purpose(),2000,"Purpose"),value.criteria(),value.confirmation(),key);
    }
    private String text(String value,int max,String field) { if(value==null||value.isBlank()||value.strip().length()>max||value.chars().anyMatch(Character::isISOControl)) throw new OutreachValidationException(field+" is missing or invalid");return value.strip(); }
    private AuthenticationRequestContext safeContext(AuthenticationRequestContext value) { return value==null?new AuthenticationRequestContext("unknown","outreach","outreach-system"):value; }
    private AuthenticationRequestContext context(RunRecord run) { return read(run.contextJson(),AuthenticationRequestContext.class); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new OutreachValidationException("Outreach configuration could not be serialized");} }
    private <T> T read(String value,Class<T> type) { try{return mapper.readValue(value,type);}catch(Exception e){throw new OutreachValidationException("Persisted outreach configuration is invalid");} }
    private byte[] hash(String value) { try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);} }
    private <T> Mono<T> notFound() { return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Outreach run was not found")); }
    private String safeFailure(Throwable error) {
        // Never persist raw transport/database exception details or credentials.
        String reason=error instanceof OutreachValidationException e?e.getReason()
                :error instanceof CampaignValidationException?error.getMessage()
                :"Outreach step could not be completed safely ("+error.getClass().getSimpleName()+"); manual reconciliation required";
        if(reason==null) return "Outreach step blocked";
        // Campaign validation reasons are vetted static domain-policy messages, never transport details.
        String safe=reason.replaceAll("[\\p{Cc}\\p{Cf}]"," ").strip();
        return safe.substring(0,Math.min(safe.length(),500));
    }
}
