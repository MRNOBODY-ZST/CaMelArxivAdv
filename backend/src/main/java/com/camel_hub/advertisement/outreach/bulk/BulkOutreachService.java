package com.camel_hub.advertisement.outreach.bulk;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionService;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.arxiv.search.*;
import com.camel_hub.advertisement.audit.*;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.outreach.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.camel_hub.advertisement.outreach.bulk.BulkOutreachRepository.*;

/** Checkpointed bulk orchestration. SMTP is exclusively performed by the existing delivery worker. */
public final class BulkOutreachService {
    private static final Set<String> ACTIVE=Set.of("ACTIVE","SENDING");
    private static final Set<String> TERMINAL_JOB=Set.of("SUCCEEDED","PARTIALLY_SUCCEEDED","FAILED","CANCELED");
    private final BulkOutreachRepository repository;
    private final OutreachRunRepository support;
    private final ArxivImportService imports;
    private final SourceExtractionService extraction;
    private final OutreachTemplateCampaignService adapter;
    private final CampaignService campaigns;
    private final CampaignPreflightService preflight;
    private final CampaignWorkflowService workflow;
    private final AuditService audit;
    private final SensitiveValueHasher hasher;
    private final ObjectMapper mapper;
    private final OutreachProperties properties;
    private final TransactionalOperator tx;
    public BulkOutreachService(BulkOutreachRepository repository,OutreachRunRepository support,ArxivImportService imports,
            SourceExtractionService extraction,OutreachTemplateCampaignService adapter,CampaignService campaigns,
            CampaignPreflightService preflight,CampaignWorkflowService workflow,AuditService audit,SensitiveValueHasher hasher,
            ObjectMapper mapper,OutreachProperties properties,TransactionalOperator tx) {
        this.repository=repository;this.support=support;this.imports=imports;this.extraction=extraction;this.adapter=adapter;
        this.campaigns=campaigns;this.preflight=preflight;this.workflow=workflow;this.audit=audit;this.hasher=hasher;
        this.mapper=mapper.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.properties=properties;this.tx=tx;
    }
    public Mono<BulkOutreachView> create(UUID actor,BulkOutreachCommand raw,AuthenticationRequestContext context) {
        return Mono.defer(()->{
            var command=validate(raw);var safe=context==null?new AuthenticationRequestContext("unknown","bulk-outreach","bulk-outreach-system"):context;
            byte[] payloadHash=hash(command);
            return authorize(actor).then(repository.findByKey(actor,command.idempotencyKey()).flatMap(existing->deduplicate(existing,payloadHash))
                    .switchIfEmpty(Mono.defer(()->support.configuration(command.smtpAccountId(),command.templateId())
                            .switchIfEmpty(Mono.error(invalid("Choose an active template and currently tested SMTP with matching monitored IMAP")))
                            .flatMap(config->repository.create(actor,command,payloadHash,config,safe,initialState(command)))
                            .switchIfEmpty(repository.findByKey(actor,command.idempotencyKey()).flatMap(existing->deduplicate(existing,payloadHash)))
                            .flatMap(run->audit(run,"BULK_OUTREACH_CREATED",actor,safe,Map.of("targetPapers",command.targetPapers(),"acceptDeliverabilityRisk",command.acceptDeliverabilityRisk(),
                                    "riskReason",command.acceptDeliverabilityRisk()?"Explicit confirmation accepts historical deliverability warnings; hard gates remain mandatory":"No warning override"))
                                    .thenReturn(run)))))
                    .flatMap(this::view).as(tx::transactional);
        });
    }
    public Mono<BulkOutreachView> get(UUID id) { return repository.find(id).switchIfEmpty(notFound()).flatMap(this::view); }
    public Mono<PageResponse<BulkOutreachView>> list(int page,int pageSize) {
        if(page<1||pageSize<1||pageSize>100) return Mono.error(invalid("Page must be positive and pageSize between 1 and 100"));
        int offset;try{offset=Math.multiplyExact(page-1,pageSize);}catch(ArithmeticException e){return Mono.error(invalid("Page is too large"));}
        return Mono.zip(repository.list(offset,pageSize).concatMap(this::view).collectList(),repository.count()).map(result->PageResponse.of(result.getT1(),page,pageSize,result.getT2()));
    }
    public Mono<BulkOutreachView> cancel(UUID id,UUID actor,long expectedVersion,AuthenticationRequestContext context) {
        return repository.lock(id).switchIfEmpty(notFound()).flatMap(run->{
            if(expectedVersion<0||run.lockVersion()!=expectedVersion) return Mono.error(new OutreachConflictException("Bulk run changed; refresh before canceling"));
            if(!ACTIVE.contains(run.status())) return Mono.error(new OutreachConflictException("Bulk run is already terminal"));
            return repository.children(id).collectList().flatMap(children->Flux.fromIterable(children).concatMap(child->
                    campaigns.get(child.id()).flatMap(campaign->Set.of("SCHEDULED","RUNNING","PAUSED").contains(campaign.status())
                            ?workflow.cancel(campaign.id(),actor,context==null?run.context():context,campaign.lockVersion()).then():Mono.empty())
                    .then(campaigns.get(child.id())).flatMap(current->{
                        var counts=current.deliveryCounts();
                        return repository.childProgress(child,"CANCELED","DONE","Parent canceled; unknown/accepted outcomes retained",
                                counts.smtpAccepted()+counts.bounced(),counts.bounced(),counts.permanentFailure()+counts.suppressed()+counts.unsubscribed()+counts.canceled(),counts.outcomeUnknown());
                    })).then())
                    .then(save(run,"CANCELED",run.stage(),"Canceled; accepted, in-flight and unknown SMTP outcomes cannot be recalled or replayed",0))
                    .flatMap(saved->audit(saved,"BULK_OUTREACH_CANCELED",actor,context==null?run.context():context,Map.of("status","CANCELED")).then(view(saved)));
        }).as(tx::transactional);
    }

    /** Claim and child/outbox association share one transaction; errors roll back before being recorded. */
    public Mono<Boolean> advanceOne() {
        return Mono.defer(()->{
            var claimed=new AtomicReference<UUID>();var childClaimed=new AtomicReference<UUID>();
            return repository.lockNext().flatMap(run->{claimed.set(run.id());return step(run,childClaimed).thenReturn(true);})
                    .defaultIfEmpty(false).as(tx::transactional).onErrorResume(error->{
                        if(claimed.get()==null) return Mono.error(error);
                        return repository.lock(claimed.get()).flatMap(run->{
                            if(!ACTIVE.contains(run.status())) return Mono.just(false);
                            String message=safeFailure(error);
                            if(childClaimed.get()!=null) return repository.children(run.id()).filter(child->child.id().equals(childClaimed.get())).next()
                                    .flatMap(child->repository.childProgress(child,"BLOCKED",child.stage(),message,child.smtpAccepted(),child.bouncedCount(),child.failedCount(),child.unknownCount()))
                                    .then(save(run,"ACTIVE","CHILDREN","A child is blocked; unaffected children remain eligible for normal processing",0)).thenReturn(true);
                            return save(run,"BLOCKED",run.stage(),message,0).thenReturn(true);
                        }).defaultIfEmpty(false).as(tx::transactional);
                    });
        });
    }
    private Mono<Run> step(Run run,AtomicReference<UUID> childClaimed) {
        if("CHILDREN".equals(run.stage())) return children(run,childClaimed);
        return authorize(run.actorId()).then(Mono.defer(()->switch(run.stage()) {
            case "IMPORT" -> startImport(run);
            case "WAIT_IMPORT" -> waitImport(run);
            case "EXTRACT","WAIT_EXTRACTION" -> extract(run);
            case "PREPARE" -> prepare(run);
            default -> Mono.error(invalid("Unsupported persisted bulk stage"));
        }));
    }
    private State initialState(BulkOutreachCommand command) {
        var state=new State();int size=command.searchPlan().size();
        for(int i=0;i<size;i++) {
            int budget=command.targetPapers()/size+(i<command.targetPapers()%size?1:0);
            // Each supplied direction gets a fair initial bounded search, including tiny targets.
            state.shards.add(new Shard(command.searchPlan().get(i),Math.max(1,budget),null));
        }return state;
    }
    private Mono<Run> startImport(Run run) {
        var state=run.state();
        if(state.shardIndex>=state.shards.size()) return finishSearch(run);
        var shard=state.shards.get(state.shardIndex);
        if(state.rawBudget+shard.budget()>30000) return finishSearch(run);
        return imports.createImport(run.actorId(),new ArxivImportService.ImportCommand(List.of(),shard.criteria(),shard.budget()),run.context())
                .flatMap(job->{
                    state.shards.set(state.shardIndex,new Shard(shard.criteria(),shard.budget(),job.jobId()));state.rawBudget+=shard.budget();
                    return repository.recordJob(run.id(),job.jobId(),"IMPORT",state.shardIndex,shard.criteria(),shard.budget())
                            .then(save(run,"ACTIVE","WAIT_IMPORT","Waiting for bounded metadata shard; unique manifest is checkpointed",0));
                });
    }
    private Mono<Run> waitImport(Run run) {
        var state=run.state();var shard=state.shards.get(state.shardIndex);
        return support.jobStatus(shard.jobId()).switchIfEmpty(Mono.error(invalid("Persisted import job is missing"))).flatMap(status->{
            if(!TERMINAL_JOB.contains(status)) return save(run,"ACTIVE","WAIT_IMPORT","Waiting for existing metadata job; no duplicate submitted",3);
            return repository.paperCount(run.id()).flatMap(count->repository.freezeImport(run,shard.jobId(),run.command().targetPapers()-count))
                    .then(repository.importCount(shard.jobId())).flatMap(rawCount->{
                        state.shardIndex++;
                        return repository.paperCount(run.id()).flatMap(count->{
                            Mono<Void> residual=Mono.empty();
                            if(count<run.command().targetPapers()&&rawCount>=shard.budget()&&state.rawBudget<30000) {
                                residual=repository.importOldest(shard.jobId()).flatMap(oldest->{
                                    // Inclusive boundary avoids losing additional papers on the oldest returned day.
                                    // A repeated saturated one-day query cannot be paginated honestly: record shortfall.
                                    LocalDate from=shard.criteria().submittedFrom();LocalDate to=oldest;
                                    if(from==null||!from.isAfter(to)) {
                                        int remainingBudget=30000-state.rawBudget-state.shards.subList(state.shardIndex,state.shards.size()).stream().mapToInt(Shard::budget).sum();
                                        int budget=Math.min(Math.min(run.command().targetPapers()-count,10000),remainingBudget);
                                        var criteria=withDates(shard.criteria(),from,to);
                                        if(budget>0&&state.shards.stream().noneMatch(existing->existing.criteria().equals(criteria)))
                                            state.shards.add(new Shard(criteria,budget,null));
                                    }return Mono.empty();
                                });
                            }
                            return residual.then(Mono.defer(()->state.shardIndex<state.shards.size()&&count<run.command().targetPapers()
                                    ?save(run,"ACTIVE","IMPORT","Metadata union persisted; searching remaining real criteria shards",0):finishSearch(run)));
                        });
                    });
        });
    }
    private Mono<Run> finishSearch(Run run) {
        return repository.paperCount(run.id()).flatMap(count->{
            if(count<run.command().targetPapers()) run.state().shortfall="Metadata shortfall: requested "+run.command().targetPapers()+", found "+count+" unique papers within bounded search";
            return save(run,count==0?"COMPLETED":"ACTIVE",count==0?"DONE":"EXTRACT",run.state().shortfall.isBlank()?"Unique paper manifest frozen":run.state().shortfall,0);
        });
    }
    private Mono<Run> extract(Run run) {
        return repository.extractionOutcomes(run.id()).collectList().flatMap(outcomes->
                checkpointOutcomes(run,outcomes).then(Mono.defer(()->{
                    var pending=outcomes.stream().filter(paper->"PENDING".equals(paper.outcome())).map(PaperOutcome::paperId).limit(100).toList();
                    if(outcomes.stream().anyMatch(paper->"ACTIVE".equals(paper.outcome())))
                        return save(run,"ACTIVE","WAIT_EXTRACTION","Waiting for existing extraction; no duplicate or terminal retry submitted",3);
                    if(!pending.isEmpty()) return submitPendingSources(run,pending);
                    return repository.refreshNoContact(run.id()).then(save(run,"ACTIVE","PREPARE","Completed source reused; failed/unsafe/unavailable papers excluded individually",0));
                })));
    }
    private Mono<Void> checkpointOutcomes(Run run,List<PaperOutcome> outcomes) {
        return Flux.fromIterable(outcomes).concatMap(paper->repository.saveOutcome(run.id(),paper))
                .thenMany(Flux.fromIterable(outcomes.stream().filter(paper->paper.jobId()!=null).collect(java.util.stream.Collectors.groupingBy(PaperOutcome::jobId)).entrySet())
                        .concatMap(entry->repository.recordExtractionJob(run.id(),entry.getKey(),entry.getValue().size()))).then();
    }
    private Mono<Run> submitPendingSources(Run run,List<UUID> candidates) {
        // Hold the same ordered paper locks as the source API through the fresh read and job/outbox commit.
        // A concurrent creator may have finished since the manifest read; terminal jobs must never be replayed.
        return repository.lockSourceCandidates(candidates).thenMany(repository.sourceCandidateOutcomes(run.id(),candidates)).collectList()
                .flatMap(fresh->checkpointOutcomes(run,fresh).then(Mono.defer(()->{
                    if(fresh.stream().anyMatch(paper->"ACTIVE".equals(paper.outcome())))
                        return save(run,"ACTIVE","WAIT_EXTRACTION","Concurrent source extraction observed; waiting without duplicate or terminal retry",3);
                    var pending=fresh.stream().filter(paper->"PENDING".equals(paper.outcome())).map(PaperOutcome::paperId).toList();
                    if(pending.isEmpty()) return save(run,"ACTIVE","EXTRACT","Source state changed before submission; outcomes checkpointed without replay",0);
                    return extraction.create(run.actorId(),pending,run.context()).flatMap(job->repository.recordExtractionJob(run.id(),job.jobId(),pending.size())
                            .thenMany(Flux.fromIterable(pending).concatMap(id->repository.saveOutcome(run.id(),new PaperOutcome(id,"ACTIVE",job.jobId(),null))))
                            .then(save(run,"ACTIVE","WAIT_EXTRACTION","Bounded source extraction submitted; terminal failures remain exclusions",0)));
                })));
    }
    private Mono<Run> prepare(Run run) {
        return verifyConfiguration(run).then(repository.safePapers(run.id()).collectList()).flatMap(papers->{
            if(papers.isEmpty()) {run.state().selectionComplete=true;return save(run,"ACTIVE","CHILDREN","No successful source papers; exclusions are preserved",0);}
            return adapter.unsafeBulkPaperSources(papers).flatMap(rejected->{
                if(!rejected.isEmpty()) return repository.excludeContent(run.id(),rejected)
                        .then(save(run,"ACTIVE","PREPARE","Unsafe frozen paper fields excluded individually; unaffected papers continue",0));
                return adapter.prepareBulk(run.actorId(),run.id(),run.command().templateId(),run.command().smtpAccountId(),run.config().mailboxAccountId(),run.command().purpose(),papers)
                    .flatMap(campaign->repository.campaignMatches(campaign.id(),run).flatMap(matches->{
                        if(!matches) return Mono.error(invalid("Child differs from frozen sender/template configuration"));
                        int selected=campaign.recipientCounts().total();
                        return repository.associate(run.id(),campaign.id(),selected).then(save(run,"ACTIVE","PREPARE","Bounded child membership and durable contact reservations committed",0));
                    }))
                    .switchIfEmpty(Mono.defer(()->{run.state().selectionComplete=true;return save(run,"ACTIVE","CHILDREN","All eligible deduplicated contacts frozen into bounded children",0);}));
            });
        });
    }
    private Mono<Run> children(Run run,AtomicReference<UUID> childClaimed) {
        return repository.children(run.id()).collectList().flatMap(children->{
            Optional<Child> next=children.stream().filter(child->"ACTIVE".equals(child.status())).findFirst();
            if(next.isPresent()) {
                var child=next.get();childClaimed.set(child.id());
                return authorize(run.actorId()).then(verifyConfiguration(run)).then(repository.campaignMatches(child.id(),run)).flatMap(matches->{
                    if(!matches) return Mono.error(invalid("Child differs from frozen sender/template configuration"));
                    return adapter.review(child.id()).then(preflight.preflight(child.id()));
                }).flatMap(before->{
                    OutreachRunService.requireStrictPreflight(before,child.selectedCount());
                    if("START".equals(child.stage())&&!properties.getProductionHoldReason().isBlank()&&!run.command().acceptDeliverabilityRisk())
                        return Mono.error(invalid("Production sending hold: "+properties.getProductionHoldReason()));
                    return campaigns.get(child.id()).flatMap(campaign->{
                        Mono<CampaignService.CampaignView> mutation=switch(child.stage()) {
                            case "REVIEW" -> workflow.submitReview(child.id(),run.actorId(),run.context(),campaign.lockVersion());
                            case "APPROVE" -> workflow.approve(child.id(),run.actorId(),run.context(),campaign.lockVersion());
                            case "START" -> workflow.start(child.id(),run.actorId(),run.context(),campaign.lockVersion());
                            default -> Mono.error(invalid("Unsupported child campaign stage"));
                        };
                        return mutation.then(preflight.preflight(child.id())).flatMap(after->{
                            OutreachRunService.requireStrictPreflight(after,child.selectedCount());
                            return repository.campaignMatches(child.id(),run).flatMap(matches->{
                                if(!matches) return Mono.error(invalid("Child configuration changed during workflow action"));
                                String nextStage=switch(child.stage()){case "REVIEW"->"APPROVE";case "APPROVE"->"START";default->"SENDING";};
                                return repository.childProgress(child,"SENDING".equals(nextStage)?"SENDING":"ACTIVE",nextStage,"Normal campaign workflow "+child.stage()+" completed",0,0,0,0)
                                        .then(save(run,"ACTIVE","CHILDREN","Normal child review/count/configuration gates verified",0));
                            });
                        });
                    });
                });
            }
            return Flux.fromIterable(children).filter(child->"SENDING".equals(child.status())).concatMap(this::deliveryProgress).then(repository.children(run.id()).collectList())
                    .flatMap(updated->{
                        boolean sending=updated.stream().anyMatch(child->"SENDING".equals(child.status()));
                        boolean blocked=updated.stream().anyMatch(child->"BLOCKED".equals(child.status()));
                        boolean failed=updated.stream().anyMatch(child->Set.of("FAILED","CANCELED").contains(child.status()));
                        String status=sending?"SENDING":blocked?"BLOCKED":failed?"FAILED":"COMPLETED";
                        String message=sending?"Sending through normal delivery; unknown outcomes are never replayed":blocked?"One or more children blocked; exact manifests remain inspectable":failed?"Parent failed because a child failed or was canceled; inspect child attempt diagnostics; unavailable preparation detail is ROOT_CAUSE_UNKNOWN":"Children terminal; SMTP acceptance is not inbox delivery";
                        if(!run.state().shortfall.isBlank()) message=message+"; supplementary search information: "+run.state().shortfall;
                        return save(run,status,sending?"CHILDREN":"DONE",message,sending?3:0);
                    });
        });
    }
    private Mono<Void> deliveryProgress(Child child) {
        return campaigns.get(child.id()).flatMap(campaign->{
            var counts=campaign.deliveryCounts();int accepted=counts.smtpAccepted()+counts.bounced();int failed=counts.permanentFailure()+counts.suppressed()+counts.unsubscribed()+counts.canceled();
            String status="RUNNING".equals(campaign.status())?"SENDING":"COMPLETED".equals(campaign.status())?
                    counts.outcomeUnknown()>0||failed>0?"FAILED":"COMPLETED":"CANCELED".equals(campaign.status())?"CANCELED":"BLOCKED";
            return repository.childProgress(child,status,"SENDING".equals(status)?"SENDING":"DONE","SMTP acceptance is not inbox delivery; no automatic retry of unknown outcomes",accepted,counts.bounced(),failed,counts.outcomeUnknown());
        });
    }
    private Mono<Run> save(Run run,String status,String stage,String message,int delay) {
        return repository.save(run,status,stage,message,delay).switchIfEmpty(Mono.error(new OutreachConflictException("Bulk parent changed")))
                .flatMap(saved->audit(saved,"BULK_OUTREACH_"+stage,run.actorId(),run.context(),Map.of("status",status,"stage",stage)).thenReturn(saved));
    }
    private Mono<Void> verifyConfiguration(Run run) {return support.configuration(run.command().smtpAccountId(),run.command().templateId()).filter(config->config.equals(run.config())).switchIfEmpty(Mono.error(invalid("Frozen template version or tested sender/mailbox configuration changed"))).then();}
    private Mono<Void> authorize(UUID actor) {
        if(actor==null) return Mono.error(invalid("Actor is required"));
        return support.authorized(actor).flatMap(allowed->allowed?Mono.empty():Mono.error(invalid("Actor no longer has all required outreach permissions")));
    }
    private Mono<Void> audit(Run run,String action,UUID actor,AuthenticationRequestContext context,Map<String,Object> after) { return audit.record(new AuditEvent(actor,action,"BULK_OUTREACH_RUN",run.id().toString(),hasher.hash(context.ipAddress()),context.userAgentSummary(),context.traceId(),Map.of(),after,AuditResult.SUCCESS,null)); }
    private Mono<Run> deduplicate(Run run,byte[] hash) { return MessageDigest.isEqual(run.payloadHash(),hash)?Mono.just(run):Mono.error(new OutreachConflictException("Idempotency key already has a different request payload")); }
    private Mono<BulkOutreachView> view(Run run) {
        // Creation/cancellation also read this view inside a single transactional connection.
        // Do not interleave result streams on that connection.
        return repository.paperCounts(run.id()).flatMap(p->repository.liveChildren(run.id()).collectList().map(children->{
            var c=run.command();
            // Only actual associated children contribute. Referenced standalone targets are not run children.
            var selectionCounts=repository.inventoryAvailable() && children.stream().allMatch(child->child.selectionCounts()!=null)
                ? new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts(
                    children.stream().mapToLong(child->child.selectionCounts().historicalSelected()).sum(),
                    children.stream().mapToLong(child->child.selectionCounts().actionableQueued()).sum(),
                    children.stream().mapToLong(child->child.selectionCounts().transferred()).sum(),
                    children.stream().mapToLong(child->child.selectionCounts().migrationBlocked()).sum()) : null;
            var references=selectionCounts==null?null:children.stream().flatMap(child->child.migrationReferences().stream()).distinct()
                .sorted(java.util.Comparator.comparing(ref->ref.migrationId().toString())).toList();
            return new BulkOutreachView(run.id(),c.name(),c.targetPapers(),run.status(),run.stage(),run.message(),p[0],p[1],p[2],p[3],
                    children.stream().mapToInt(Child::selectedCount).sum(),children.stream().mapToInt(Child::smtpAccepted).sum(),children.stream().mapToInt(Child::bouncedCount).sum(),
                    children.stream().mapToInt(Child::failedCount).sum(),children.stream().mapToInt(Child::unknownCount).sum(),c.smtpAccountId(),c.templateId(),c.incremental(),c.acceptDeliverabilityRisk(),run.lockVersion(),run.createdAt(),run.updatedAt(),
                    ACTIVE.contains(run.status())?List.of("CANCEL"):List.of(),children.stream().map(child->new BulkOutreachView.Child(child.id(),child.status(),child.selectedCount(),child.smtpAccepted(),child.bouncedCount(),child.failedCount(),child.unknownCount(),child.selectionCounts(),child.migrationReferences())).toList(),selectionCounts,references);
        }));
    }
    private BulkOutreachCommand validate(BulkOutreachCommand raw) {
        if(raw==null||!"AUTO_OUTREACH_SEND".equals(raw.confirmation())) throw invalid("Explicit AUTO_OUTREACH_SEND confirmation is required");
        if(raw.targetPapers()<1||raw.targetPapers()>10000||raw.smtpAccountId()==null||raw.templateId()==null) throw invalid("Target must be 1–10000 papers with sender and template");
        if(raw.searchPlan()==null||raw.searchPlan().isEmpty()||raw.searchPlan().size()>12||raw.searchPlan().stream().anyMatch(Objects::isNull)) throw invalid("Search plan requires 1–12 distinct queries");
        if(raw.idempotencyKey()==null||!raw.idempotencyKey().matches("[A-Za-z0-9._:-]{8,128}")) throw invalid("Idempotency key requires 8–128 safe characters");
        var normalizer=new ArxivQueryNormalizer(mapper,100);var plan=new ArrayList<ArxivSearchCriteria>();
        try {
            for(var criteria:raw.searchPlan()) {
                var normalized=normalizer.normalize(new ArxivSearchCriteria(criteria.categoryIds(),criteria.categoryMode(),criteria.submittedFrom(),criteria.submittedTo(),criteria.updatedFrom(),criteria.updatedTo(),criteria.titleKeywords(),criteria.abstractKeywords(),criteria.authorKeywords(),criteria.hasDoi(),criteria.hasJournalReference(),criteria.sourceAvailable(),ArxivSearchCriteria.SortBy.SUBMITTED_DATE,ArxivSearchCriteria.SortOrder.DESCENDING,1,100),criteria.categoryIds()==null?Set.of():new HashSet<>(criteria.categoryIds())).criteria();
                if(plan.contains(normalized)) throw invalid("Search plan queries must be distinct after normalization");plan.add(normalized);
            }
        }catch(IllegalArgumentException e){throw invalid("Search plan is invalid: "+e.getMessage());}
        return new BulkOutreachCommand(text(raw.name(),200),text(raw.purpose(),2000),raw.targetPapers(),raw.smtpAccountId(),raw.templateId(),List.copyOf(plan),raw.incremental(),raw.acceptDeliverabilityRisk(),raw.confirmation(),raw.idempotencyKey());
    }
    private ArxivSearchCriteria withDates(ArxivSearchCriteria c,LocalDate from,LocalDate to) {return new ArxivSearchCriteria(c.categoryIds(),c.categoryMode(),from,to,c.updatedFrom(),c.updatedTo(),c.titleKeywords(),c.abstractKeywords(),c.authorKeywords(),c.hasDoi(),c.hasJournalReference(),c.sourceAvailable(),c.sortBy(),c.sortOrder(),1,100);}
    private String text(String value,int max) {if(value==null||value.isBlank()||value.strip().length()>max||value.codePoints().anyMatch(Character::isISOControl))throw invalid("Name or purpose is missing or invalid");return value.strip();}
    private byte[] hash(Object value) {try{return MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw invalid("Bulk request serialization failed");}}
    private OutreachValidationException invalid(String message) {return new OutreachValidationException(message);}
    private <T>Mono<T> notFound() {return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Bulk outreach run was not found"));}
    private String safeFailure(Throwable error) {String reason=error instanceof OutreachValidationException e?e.getReason():error instanceof CampaignValidationException?error.getMessage():"Bulk step blocked ("+error.getClass().getSimpleName()+"); manual reconciliation required";if(reason==null)return "Bulk step blocked";String safe=reason.replaceAll("[\\p{Cc}\\p{Cf}]"," ").strip();return safe.substring(0,Math.min(500,safe.length()));}
}
