package com.camel_hub.advertisement.outreach.schedule;
import com.camel_hub.advertisement.outreach.bulk.BulkOutreachCommand;
import java.time.LocalDate;
import java.util.List;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.outreach.OutreachRunRepository;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.common.api.PageResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static com.camel_hub.advertisement.outreach.schedule.WeeklyScheduleRepository.*;
public final class WeeklyScheduleService {
    @FunctionalInterface public interface BulkCreator {Mono<UUID> create(UUID actor,BulkOutreachCommand command,AuthenticationRequestContext context);}
    private final WeeklyScheduleRepository repository;private final OutreachRunRepository safety;private final TrendDiscovery trends;private final BulkCreator bulk;private final AuditService audit;private final SensitiveValueHasher hasher;private final ObjectMapper mapper;private final TransactionalOperator tx;private final Clock clock;
    public WeeklyScheduleService(WeeklyScheduleRepository repository,OutreachRunRepository safety,TrendDiscovery trends,BulkCreator bulk,AuditService audit,SensitiveValueHasher hasher,ObjectMapper mapper,TransactionalOperator tx,Clock clock) {
        this.repository=repository;this.safety=safety;this.trends=trends;this.bulk=bulk;this.audit=audit;this.hasher=hasher;this.mapper=mapper.copy().enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.tx=tx;this.clock=clock;
    }
    public Mono<WeeklyScheduleView> create(UUID actor,WeeklyScheduleCommand raw,AuthenticationRequestContext context){return Mono.defer(()->{
        WeeklyScheduleCommand command;try{command=validate(raw);}catch(IllegalArgumentException e){return Mono.error(bad(e.getMessage()));}
        byte[] hash=hash(json(command));
        var creation=repository.byKey(actor,command.idempotencyKey()).flatMap(s->deduplicate(s,hash))
            .switchIfEmpty(Mono.defer(()->safety.configuration(command.smtpAccountId(),command.templateId()).switchIfEmpty(Mono.error(bad("A tested SMTP account, monitored Reply-To and active template are required")))
                .flatMap(config->repository.create(actor,command,hash,json(command.directions()),json(safeContext(context)),json(config),WeeklySchedulePolicy.next(clock.instant())))
                .switchIfEmpty(repository.byKey(actor,command.idempotencyKey()).flatMap(s->deduplicate(s,hash)))
                .flatMap(s->audit(s,actor,safeContext(context),"WEEKLY_OUTREACH_CREATED",Map.of("maxPapers",s.maxPapers(),"acceptDeliverabilityRisk",s.risk(),"riskReason",s.risk()?"Actor explicitly accepted historical deliverability warnings":"No historical deliverability warning override","confirmation","AUTO_OUTREACH_SEND")).thenReturn(s))));
        return tx.transactional(authorize(actor).then(creation)).map(this::view);
    });}
    private Mono<Schedule> deduplicate(Schedule schedule,byte[] hash){return Arrays.equals(schedule.payloadHash(),hash)?Mono.just(schedule):Mono.error(conflict("Idempotency key was used with a different weekly schedule payload"));}
    public Mono<WeeklyScheduleView> get(UUID id){return repository.find(id).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Weekly schedule was not found"))).map(this::view);}
    public Mono<PageResponse<WeeklyScheduleView>> list(int page,int pageSize){if(page<1||page>1000000||pageSize<1||pageSize>100)return Mono.error(bad("Invalid weekly schedule page"));return Mono.zip(repository.list((page-1)*pageSize,pageSize).map(this::view).collectList(),repository.count()).map(v->PageResponse.of(v.getT1(),page,pageSize,v.getT2()));}
    public Mono<WeeklyScheduleView> changeEnabled(UUID id,UUID actor,long version,boolean enabled,AuthenticationRequestContext context){
        if(version<0)return Mono.error(bad("Expected version must not be negative"));
        return tx.transactional(repository.lock(id).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Weekly schedule was not found"))).flatMap(s->{
            if(s.lockVersion()!=version)return Mono.error(conflict("Weekly schedule changed; reload before updating"));
            Mono<String> config=enabled?authorize(s.actorId()).then(authorize(actor)).then(safety.configuration(s.smtpAccountId(),s.templateId()).switchIfEmpty(Mono.error(bad("Sender, mailbox or template configuration is not currently usable"))).map(this::json)):Mono.just(s.configJson());
            return config.flatMap(snapshot->repository.enabled(id,version,enabled,snapshot,enabled?WeeklySchedulePolicy.next(clock.instant()):s.nextRunAt())).switchIfEmpty(Mono.error(conflict("Weekly schedule changed")))
                .flatMap(saved->audit(saved,actor,safeContext(context),enabled?"WEEKLY_OUTREACH_RESUMED":"WEEKLY_OUTREACH_PAUSED",Map.of("enabled",enabled,"acceptDeliverabilityRisk",saved.risk())).thenReturn(saved));
        })).map(this::view);
    }
    private record Claim(Schedule schedule,Occurrence occurrence) {}
    public Mono<Boolean> advanceOne(){return Mono.defer(()->{
        Instant now=clock.instant();
        return tx.transactional(repository.lockNext(now).flatMap(s->repository.reserve(s,now).map(o->new Claim(s,o))))
            .flatMap(claim->prepare(claim,now).flatMap(o->launch(claim.schedule(),o)).thenReturn(true)
                .onErrorResume(error->tx.transactional(repository.lock(claim.schedule().id()).flatMap(s->repository.block(s,claim.occurrence(),"Weekly occurrence blocked: actor permissions, frozen configuration or parent creation could not be verified; manual review required"))).thenReturn(true)))
            .defaultIfEmpty(false);
    });}
    private Mono<Occurrence> prepare(Claim claim,Instant now){
        var s=claim.schedule();var o=claim.occurrence();
        return verify(s).then(Mono.defer(()->{
            if("READY".equals(o.status()))return Mono.just(o);
            return tx.transactional(repository.reserveDiscoveryAttempt(o)).flatMap(attempt->trends.discover(directions(s),now)
                .map(result->attempt.discoveryAttempts()>1?TrendDiscovery.incompleteUsage(result):result))
                .switchIfEmpty(Mono.fromSupplier(()->TrendDiscovery.fallback(directions(s),"DISCOVERY_ATTEMPT_BUDGET_EXHAUSTED_USAGE_UNKNOWN",0,"unknown","unknown",-1,-1,-1)))
                .flatMap(discovery->{
                var command=bulkCommand(command(s,o.key()),discovery,o.from(),o.to(),o.key());
                return repository.ready(o,json(discovery),json(command));
            });
        }));
    }
    private Mono<Void> launch(Schedule claimed,Occurrence claimedOccurrence){
        // Evidence/provider HTTP has finished before this short transaction begins.
        return tx.transactional(repository.lock(claimed.id()).flatMap(s->{
            if(!s.enabled())return Mono.empty();
            return repository.lockOccurrence(claimedOccurrence).flatMap(o->verify(s).then(Mono.defer(()->{
                var command=read(o.commandJson(),BulkOutreachCommand.class);
                // The exact persisted occurrence command, including its key, is reused after a crash.
                return bulk.create(s.actorId(),command,read(s.contextJson(),AuthenticationRequestContext.class)).flatMap(parent->repository.launched(s,o,parent,o.to().atStartOfDay(WeeklySchedulePolicy.ZONE).toInstant())
                    .then(audit(s,s.actorId(),read(s.contextJson(),AuthenticationRequestContext.class),"WEEKLY_OUTREACH_LAUNCHED",Map.of("parentRunId",parent.toString(),"occurrenceKey",o.key(),"incremental",true,"acceptDeliverabilityRisk",s.risk()))));
            })));
        }));
    }
    private Mono<Void> authorize(UUID actor){return safety.authorized(actor).flatMap(allowed->allowed?Mono.empty():Mono.error(bad("Actor no longer has the required outreach permissions")));}
    private Mono<Void> verify(Schedule s){return authorize(s.actorId()).then(safety.configuration(s.smtpAccountId(),s.templateId()).filter(c->c.equals(read(s.configJson(),OutreachRunRepository.ConfigSnapshot.class))).switchIfEmpty(Mono.error(bad("Frozen SMTP, monitored mailbox or template configuration changed"))).then());}
    private WeeklyScheduleCommand command(Schedule s,String key){return new WeeklyScheduleCommand(s.name(),s.purpose(),s.maxPapers(),s.smtpAccountId(),s.templateId(),directions(s),s.risk(),"AUTO_OUTREACH_SEND",key);}
    private List<String> directions(Schedule s){return List.of(read(s.directionsJson(),String[].class));}
    private WeeklyScheduleView view(Schedule s){return new WeeklyScheduleView(s.id(),s.name(),s.purpose(),s.maxPapers(),s.smtpAccountId(),s.templateId(),directions(s),s.enabled(),"Asia/Shanghai","MONDAY","09:00",s.nextRunAt(),s.lastRunId(),s.message(),s.risk(),s.lockVersion(),s.createdAt(),s.updatedAt(),List.of(s.enabled()?"PAUSE":"RESUME"));}
    private Mono<Void> audit(Schedule s,UUID actor,AuthenticationRequestContext context,String action,Map<String,Object> after){return audit.record(new AuditEvent(actor,action,"OUTREACH_SCHEDULE",s.id().toString(),hasher.hash(context.ipAddress()),context.userAgentSummary(),context.traceId(),Map.of(),after,AuditResult.SUCCESS,null));}
    private AuthenticationRequestContext safeContext(AuthenticationRequestContext context){return context==null?new AuthenticationRequestContext("unknown","weekly-outreach","weekly-system"):context;}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw bad("Weekly schedule could not be serialized safely");}}
    private <T>T read(String v,Class<T> type){try{return mapper.readValue(v,type);}catch(Exception e){throw bad("Frozen weekly schedule data is invalid");}}
    private byte[] hash(String v){try{return java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}}
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    static WeeklyScheduleCommand validate(WeeklyScheduleCommand c){
        if(c==null||c.maxPapers()<1||c.maxPapers()>5000||c.smtpAccountId()==null||c.templateId()==null||!"AUTO_OUTREACH_SEND".equals(c.confirmation())||c.idempotencyKey()==null||!c.idempotencyKey().matches("[A-Za-z0-9._:-]{8,128}"))throw new IllegalArgumentException("Weekly schedule requires accounts, maxPapers 1–5000, a safe idempotency key and AUTO_OUTREACH_SEND confirmation");
        return new WeeklyScheduleCommand(text(c.name(),200),text(c.purpose(),2000),c.maxPapers(),c.smtpAccountId(),c.templateId(),WeeklySchedulePolicy.directions(c.directions()),c.acceptDeliverabilityRisk(),c.confirmation(),c.idempotencyKey());
    }
    private static String text(String s,int max){if(s==null||s.isBlank()||s.strip().length()>max||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Weekly schedule text is missing or invalid");return s.strip();}
    static BulkOutreachCommand bulkCommand(WeeklyScheduleCommand c,TrendDiscovery.Result result,LocalDate from,LocalDate to,String key){
        c=validate(c);
        var criteria=result.topics().stream().map(topic->new ArxivSearchCriteria(topic.categoryIds(),ArxivSearchCriteria.CategoryMode.ANY,from,to,null,null,null,WeeklySchedulePolicy.directions(List.of(topic.phrase())).getFirst(),null,null,null,null,ArxivSearchCriteria.SortBy.SUBMITTED_DATE,ArxivSearchCriteria.SortOrder.DESCENDING,1,100)).distinct().toList();
        if(criteria.isEmpty()||criteria.size()>12)throw new IllegalArgumentException("Weekly search plan is empty or exceeds 12 queries");
        return new BulkOutreachCommand(c.name(),c.purpose(),c.maxPapers(),c.smtpAccountId(),c.templateId(),List.copyOf(criteria),true,c.acceptDeliverabilityRisk(),"AUTO_OUTREACH_SEND",key);
    }
}
