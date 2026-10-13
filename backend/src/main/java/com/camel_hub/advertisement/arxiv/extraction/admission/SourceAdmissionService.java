package com.camel_hub.advertisement.arxiv.extraction.admission;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.NoTransactionException;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionDtos.*;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceCommandIdentity.*;

/** DB job lock → authority lock → bounded Redis CAS → confirmed owned DB commit. */
public final class SourceAdmissionService {
    private static final DefaultRedisScript<Long> CAS=new DefaultRedisScript<>("""
        local current=redis.call('GET',KEYS[1])
        if ARGV[1]=='ABSENT' then
          if current then return 0 end
        elseif current~=ARGV[2] then return 0 end
        redis.call('SET',KEYS[1],ARGV[3])
        return 1
        """,Long.class);
    private final SourceAdmissionRepository repository;
    private final ReactiveStringRedisTemplate redis;
    private final TransactionalOperator transactions;
    private final Clock clock;
    public SourceAdmissionService(SourceAdmissionRepository repository,ReactiveStringRedisTemplate redis,ReactiveTransactionManager manager,Clock clock) {
        this.repository=repository; this.redis=redis; this.transactions=TransactionalOperator.create(manager); this.clock=clock;
    }
    public Mono<AdmissionReply> execute(AdmissionRequest request) {
        return TransactionSynchronizationManager.forCurrentTransaction().map(TransactionSynchronizationManager::isActualTransactionActive)
                .onErrorResume(NoTransactionException.class,e->Mono.just(false))
                .flatMap(active->active?Mono.just(AdmissionReply.deny("UNAVAILABLE","AMBIENT_TRANSACTION")):owned(request));
    }
    private Mono<AdmissionReply> owned(AdmissionRequest r) {
        return Mono.defer(()-> {
            if(!fresh(r)) return Mono.just(AdmissionReply.deny("HELD","REQUEST_EXPIRED"));
            // The Mono overload buffers the reply until the *owned* transaction's commit completes.
            AtomicReference<Continuation> pending=new AtomicReference<>();
            return transactions.transactional(Mono.defer(()->locked(r,pending::set)))
                    .flatMap(reply->{
                        Continuation continuation=pending.getAndSet(null);
                        return continuation==null?Mono.just(reply):transactions.transactional(continueBurn(r,continuation));
                    })
                    .timeout(Duration.between(clock.instant(),r.expiresAt()))
                    .onErrorResume(e->Mono.just(AdmissionReply.deny("UNAVAILABLE","COORDINATION_FAILED")));
        });
    }
    private boolean fresh(AdmissionRequest r) { Instant now=clock.instant(); return r.expiresAt().isAfter(now) && !r.expiresAt().isAfter(now.plusSeconds(10)); }
    private Mono<AdmissionReply> locked(AdmissionRequest r,Consumer<Continuation> pending) {
        return repository.lockJob(r.scope().jobId()).flatMap(job->{
            if(!fresh(r)) return Mono.just(AdmissionReply.deny("HELD","REQUEST_EXPIRED"));
            if(!job.type().equals(r.scope().commandType())) return Mono.just(AdmissionReply.deny("HELD","SCOPE_CONFLICT"));
            if(TERMINALS.contains(job.status())) return Mono.just(new AdmissionReply("TERMINAL","JOB_TERMINAL",null,null,null,job.status()));
            if(job.status().equals("PAUSED")) return Mono.just(AdmissionReply.deny("PAUSED","JOB_PAUSED"));
            if(!Set.of("PENDING","QUEUED","RUNNING").contains(job.status())) return Mono.just(AdmissionReply.deny("HELD","JOB_NOT_ACTIVE"));
            return repository.lockAuthority(r.scope().jobId()).flatMap(authority->{
                if(!fresh(r)) return Mono.just(AdmissionReply.deny("HELD","REQUEST_EXPIRED"));
                if(!authority.matches(r.scope())) return Mono.just(AdmissionReply.deny("HELD","SCOPE_CONFLICT"));
                return redis.execute(connection->connection.stringCommands().get(java.nio.ByteBuffer.wrap(utf8(stateKey(r.scope())))))
                        .singleOrEmpty().map(SourceAdmissionDtos::strictUtf8).defaultIfEmpty("").flatMap(raw->
                        repository.issue(r.scope().jobId(),r.nonce()).flatMap(issue->issue.sequence()!=authority.sequence()?Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN")):readback(r,issue,raw))
                                .switchIfEmpty(Mono.defer(()->r.operation().equals("READBACK")?Mono.just(AdmissionReply.deny("HELD","UNKNOWN_NONCE")):admit(r,authority,raw,pending))))
                        .onErrorResume(IllegalArgumentException.class,e->Mono.just(AdmissionReply.deny("HELD","CORRUPT_STATE")));
            }).defaultIfEmpty(AdmissionReply.deny("HELD","LEGACY_UNBOUND"));
        }).defaultIfEmpty(AdmissionReply.deny("HELD","UNKNOWN_IDENTITY"));
    }
    private Mono<AdmissionReply> readback(AdmissionRequest r,SourceAdmissionRepository.Issue issue,String raw) {
        // A reservation is never an execution ticket recoverable from a row or nonce.
        if(issue.operation().equals("RESERVE")) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
        if(!fresh(r) || !r.tupleDigest().equals(issue.tupleDigest()) || !sha256(utf8(raw)).equals(issue.issuedDigest())) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
        try {
            var state=SourceAdmissionState.decode(raw);
            if(!state.scope().equals(r.scope()) || !state.receipt().equals(issue.receipt()) || state.generation()!=issue.issuedGeneration()
                    || !state.phase().equals(issue.operation().equals("BIND")?"READY":"RESERVED")) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
            return Mono.just(success(state,issue.operation().equals("BIND")?"BOUND":"RESERVED",issue.issuedDeadline()));
        } catch(IllegalArgumentException invalid) { return Mono.just(AdmissionReply.deny("HELD","CORRUPT_STATE")); }
    }
    private Mono<AdmissionReply> admit(AdmissionRequest r,SourceAdmissionRepository.Authority authority,String raw,Consumer<Continuation> pending) {
        if(authority.sequence()==0) {
            if(!raw.isEmpty()) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
            if(!r.operation().equals("BIND") || r.expectedGeneration()!=0 || r.expectedStateDigest()!=null || r.predecessorSequence()!=0) return Mono.just(AdmissionReply.deny("HELD","SCOPE_CONFLICT"));
            return redis.opsForValue().get("camel:worker:source-progress:"+r.scope().idempotencyKey()).hasElement()
                    .flatMap(legacy->legacy?Mono.just(AdmissionReply.deny("HELD","LEGACY_UNBOUND")):issue(r,null,SourceAdmissionState.initial(r)));
        }
        if(raw.isEmpty()) return Mono.just(AdmissionReply.deny("HELD","STATE_LOST"));
        final SourceAdmissionState state;
        try { state=SourceAdmissionState.decode(raw); }
        catch(IllegalArgumentException invalid) { return Mono.just(AdmissionReply.deny("HELD","CORRUPT_STATE")); }
        if(!state.scope().equals(r.scope())) return Mono.just(AdmissionReply.deny("HELD","SCOPE_CONFLICT"));
        return repository.latest(r.scope().jobId(),authority.sequence()).flatMap(latest->{
            if(!state.descendsFrom(latest,authority.targetCount())) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
            if(state.phase().equals("HELD")) return Mono.just(AdmissionReply.deny("HELD",state.reason()));
            if(r.operation().equals("BIND")) {
                if(state.phase().equals("RESERVED") && !state.deadline().isAfter(clock.instant())) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
                return Mono.just(success(state,"BOUND",latest.issuedDeadline())); // Read/recovery only, including live duplicate RESERVED.
            }
            if(!r.expectedStateDigest().equals(state.digest()) || r.expectedGeneration()!=state.generation() || r.predecessorSequence()!=authority.sequence()) return Mono.just(AdmissionReply.deny("HELD","PREDECESSOR_CONFLICT"));
            if(state.nextIndex()>=authority.targetCount() || r.index()!=state.nextIndex()) return Mono.just(AdmissionReply.deny("HELD","INDEX_CONFLICT"));
            boolean ready=state.phase().equals("READY") && r.attemptNumber()==1;
            boolean retry=state.phase().equals("RETRY_READY") && r.attemptNumber()==state.attempt()+1;
            if(!ready && !retry) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
            Instant now=clock.instant();
            if(!r.deadline().isAfter(now) || r.deadline().isAfter(now.plusSeconds(120))) return Mono.just(AdmissionReply.deny("HELD","INVALID_LEASE"));
            SourceAdmissionState issued=state.reserve(r,authority.sequence()+1);
            SourceAdmissionState consumed=issued.consumed();
            // No Redis mutation here. The attempt is irreversibly burned before any uncertain CAS.
            return repository.append(r,issued).then(Mono.fromSupplier(()->{
                pending.accept(new Continuation(raw,consumed));
                return AdmissionReply.deny("HELD","BURN_PENDING"); // Internal only; never an execution reply.
            }));
        }).defaultIfEmpty(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
    }

    /** A subscription-local capability, never serialized, returned or reconstructed from DB. */
    private static final class Continuation {
        final String predecessor;
        final SourceAdmissionState consumed;
        final AtomicBoolean used=new AtomicBoolean();
        Continuation(String predecessor,SourceAdmissionState consumed) { this.predecessor=predecessor; this.consumed=consumed; }
    }

    private Mono<AdmissionReply> continueBurn(AdmissionRequest r,Continuation continuation) {
        return Mono.defer(()->{
            if(!continuation.used.compareAndSet(false,true) || !fresh(r)) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
            return repository.lockJob(r.scope().jobId()).flatMap(job->{
                if(!fresh(r)) return Mono.just(AdmissionReply.deny("HELD","REQUEST_EXPIRED"));
                if(TERMINALS.contains(job.status())) return Mono.just(new AdmissionReply("TERMINAL","JOB_TERMINAL",null,null,null,job.status()));
                if(job.status().equals("PAUSED")) return Mono.just(AdmissionReply.deny("PAUSED","JOB_PAUSED"));
                if(!job.type().equals(r.scope().commandType()) || !Set.of("PENDING","QUEUED","RUNNING").contains(job.status())) return Mono.just(AdmissionReply.deny("HELD","SCOPE_CONFLICT"));
                return repository.lockAuthority(r.scope().jobId()).flatMap(authority->{
                    if(!fresh(r) || !r.deadline().isAfter(clock.instant())) return Mono.just(AdmissionReply.deny("HELD","REQUEST_EXPIRED"));
                    if(!authority.matches(r.scope()) || authority.sequence()!=continuation.consumed.receipt().sequence()) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
                    return repository.latest(r.scope().jobId(),authority.sequence()).flatMap(issue->{
                        if(!"BURNED".equals(issue.consumptionStatus()) || !issue.tupleDigest().equals(r.tupleDigest()) || !issue.receipt().equals(continuation.consumed.receipt())) return Mono.just(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
                        // The predecessor is the original committed receipt, NOT the latest BURNED issue.
                        return repository.latest(r.scope().jobId(),r.predecessorSequence()).flatMap(previous->{
                            var predecessor=SourceAdmissionState.decode(continuation.predecessor);
                            if(!predecessor.descendsFrom(previous,authority.targetCount()) || predecessor.generation()!=r.expectedGeneration() || !predecessor.digest().equals(r.expectedStateDigest())) return Mono.just(AdmissionReply.deny("HELD","PREDECESSOR_CONFLICT"));
                            if(!fresh(r) || !r.deadline().isAfter(clock.instant())) return Mono.just(AdmissionReply.deny("HELD","REQUEST_EXPIRED"));
                            return redis.execute(CAS,List.of(stateKey(r.scope())),"PRESENT",continuation.predecessor,continuation.consumed.raw()).single()
                                    .flatMap(changed->changed==1?repository.confirm(r,continuation.consumed)
                                            .thenReturn(success(continuation.consumed,"RESERVED",r.deadline())):Mono.just(AdmissionReply.deny("HELD","CAS_CONFLICT")));
                        }).defaultIfEmpty(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
                    }).defaultIfEmpty(AdmissionReply.deny("HELD","INVOCATION_UNCERTAIN"));
                }).defaultIfEmpty(AdmissionReply.deny("HELD","UNKNOWN_IDENTITY"));
            }).defaultIfEmpty(AdmissionReply.deny("HELD","UNKNOWN_IDENTITY"));
        });
    }
    private Mono<AdmissionReply> issue(AdmissionRequest request,String previous,SourceAdmissionState next) {
        if(!fresh(request)) return Mono.just(AdmissionReply.deny("HELD","REQUEST_EXPIRED"));
        return redis.execute(CAS,List.of(stateKey(request.scope())),previous==null?"ABSENT":"PRESENT",previous==null?"":previous,next.raw()).single()
                .flatMap(changed->changed==1?repository.append(request,next).thenReturn(success(next,request.operation().equals("BIND")?"BOUND":"RESERVED",request.deadline())):Mono.just(AdmissionReply.deny("HELD","CAS_CONFLICT")));
    }
    private static AdmissionReply success(SourceAdmissionState state,String status,Instant deadline) { return new AdmissionReply(status,"OK",state.digest(),state.receipt(),deadline,null); }
}
