package com.camel_hub.advertisement.arxiv.extraction.admission;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;

import java.time.*;
import java.util.UUID;
import java.util.List;

import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionDtos.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SourceAdmissionIntegrationTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("source_admission_test").withUsername("camel").withPassword("test-only");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.0.2-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory lettuce;
    static DatabaseClient db;
    static TransactionalOperator tx;
    static R2dbcTransactionManager manager;
    static ReactiveStringRedisTemplate redis;
    SourceAdmissionRepository repository;
    SourceAdmissionService service;
    Scope scope;

    @BeforeAll static void connect() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        var cf = ConnectionFactories.get("r2dbc:postgresql://camel:test-only@" + PG.getHost() + ":" + PG.getFirstMappedPort() + "/source_admission_test");
        db = DatabaseClient.create(cf); manager = new R2dbcTransactionManager(cf); tx = TransactionalOperator.create(manager);
        lettuce = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)); lettuce.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(lettuce, RedisSerializationContext.string());
    }
    @AfterAll static void close() { if (lettuce != null) lettuce.destroy(); }
    @BeforeEach void setup() {
        repository = new SourceAdmissionRepository(db);
        service = new SourceAdmissionService(repository, redis, manager, Clock.systemUTC());
        scope = new Scope("synthetic:" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ARXIV_FETCH_AND_PARSE_SOURCE", "a".repeat(64));
        db.sql("INSERT INTO jobs(id,type,status,idempotency_key,parameters) VALUES(:id,:type,'PENDING',:key,'{}')")
                .bind("id", scope.jobId()).bind("type", scope.commandType()).bind("key", scope.idempotencyKey()).fetch().rowsUpdated().block();
    }
    void initialize() { tx.transactional(repository.initializeCommand(scope.jobId(), scope.commandMessageId(), scope.commandType(), scope.idempotencyKey(), scope.payloadFingerprint(), 2)).block(); }
    AdmissionRequest bind() { return new AdmissionRequest("BIND", scope, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(9).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 0, null, 0, null, null, null, null); }
    AdmissionRequest reserve(AdmissionReply bound) {
        return new AdmissionRequest("RESERVE", scope, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(9).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 0, bound.stateDigest(), 1, 0L, 1L, UUID.randomUUID(), Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
    long count(String table) { return db.sql("SELECT count(*) FROM " + table + " WHERE job_id=:id").bind("id", scope.jobId()).map((r,m)->r.get(0,Long.class)).one().block(); }

    @Test void legacyTerminalWithoutMarkersNeverIssues() {
        db.sql("UPDATE jobs SET status='FAILED',ended_at=now() WHERE id=:id").bind("id",scope.jobId()).fetch().rowsUpdated().block();
        var reply = service.execute(bind()).block();
        assertThat(reply.status()).isEqualTo("TERMINAL"); assertThat(reply.terminalStatus()).isEqualTo("FAILED");
        assertThat(count("source_command_authorities")).isZero(); assertThat(count("source_command_issues")).isZero();
        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isNull();
    }
    @Test void canceledBeforeFirstDeliveryNeverIssues() {
        initialize(); db.sql("UPDATE jobs SET status='CANCELED',ended_at=now() WHERE id=:id").bind("id",scope.jobId()).fetch().rowsUpdated().block();
        assertThat(service.execute(bind()).block().status()).isEqualTo("TERMINAL"); assertThat(count("source_command_issues")).isZero();
    }
    @Test void unknownIdentityCannotBeInitializedByMailbox() {
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD"); assertThat(count("source_command_authorities")).isZero();
    }
    @Test void bindReserveReadbackAndConsumedGenerationCannotBeReused() {
        initialize(); var bound = service.execute(bind()).block(); assertThat(bound.status()).isEqualTo("BOUND");
        var request = reserve(bound); var issued = service.execute(request).block();
        assertThat(issued.status()).isEqualTo("RESERVED"); assertThat(issued.receipt().sequence()).isEqualTo(2);
        assertThat(service.execute(request.readback()).block().status()).isEqualTo("HELD");
        assertThat(service.execute(request).block().status()).isEqualTo("HELD");
        String raw = redis.opsForValue().get(stateKey(scope)).block();
        assertThat(SourceAdmissionState.decode(raw).generation()).isEqualTo(2);
        // Restoring exact immutable issued bytes cannot unburn the DB authority.
        redis.opsForValue().set(stateKey(scope), raw.replace("\"generation\":2", "\"generation\":1")).block();
        assertThat(service.execute(request.readback()).block().status()).isEqualTo("HELD");
        assertThat(service.execute(request).block().status()).isEqualTo("HELD");
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD");
        assertThat(count("source_command_issues")).isEqualTo(2);
    }
    @Test void missingStateAfterBindingIsHeldAndNeverRebuilt() {
        initialize(); service.execute(bind()).block(); redis.delete(stateKey(scope)).block();
        assertThat(service.execute(bind()).block().code()).isEqualTo("STATE_LOST"); assertThat(count("source_command_issues")).isEqualTo(1);
    }
    @Test void oldV1EvidenceIsPreservedAndNeverBound() {
        initialize(); String key="camel:worker:source-progress:"+scope.idempotencyKey(); redis.opsForValue().set(key,"legacy-synthetic").block();
        assertThat(service.execute(bind()).block().code()).isEqualTo("LEGACY_UNBOUND");
        assertThat(redis.opsForValue().get(key).block()).isEqualTo("legacy-synthetic"); assertThat(count("source_command_issues")).isZero();
    }
    @Test void initializerRollsBackWithCallerAndNeverCopiesAuthority() {
        assertThatThrownBy(() -> tx.transactional(repository.initializeCommand(scope.jobId(),scope.commandMessageId(),scope.commandType(),scope.idempotencyKey(),scope.payloadFingerprint(),2).then(Mono.error(new IllegalStateException("synthetic")))).block()).isInstanceOf(IllegalStateException.class);
        assertThat(count("source_command_authorities")).isZero();
    }
    @Test void ambientTransactionCannotLeakAdmissionSuccess() {
        initialize(); var reply=tx.transactional(service.execute(bind())).block();
        assertThat(reply.status()).isEqualTo("UNAVAILABLE"); assertThat(count("source_command_issues")).isZero();
        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isNull();
    }

    @Test void ownedCommitIsTheMailboxReplyBarrierAndCancelWaitsForIt() {
        initialize(); var entered=Sinks.<Void>empty(); var release=Sinks.<Void>empty();
        var paused=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                return Mono.defer(()->{entered.tryEmitEmpty(); return release.asMono().then(super.doCommit(synchronization,status));});
            }
        };
        var issuer=new SourceAdmissionService(repository,redis,paused,Clock.systemUTC());
        var mailbox=new SourceAdmissionMailbox(issuer,redis); var request=bind();
        redis.opsForValue().set(slotKey(request.nonce()),encodeRequest(request),Duration.ofSeconds(10)).block();
        int slot=Integer.parseInt(slotKey(request.nonce()).substring(slotKey(request.nonce()).lastIndexOf(':')+1));
        var work=mailbox.pollSlot(slot).toFuture(); entered.asMono().block(Duration.ofSeconds(3));
        assertThat(work).isNotDone(); assertThat(redis.opsForValue().get(replyKey(request.nonce())).block()).isNull();
        assertThat(count("source_command_issues")).isZero();
        var cancel=db.sql("UPDATE jobs SET status='CANCELED',ended_at=now() WHERE id=:id").bind("id",scope.jobId()).fetch().rowsUpdated().toFuture();
        assertThat(cancel).isNotDone(); release.tryEmitEmpty(); work.join(); cancel.join();
        assertThat(redis.opsForValue().get(replyKey(request.nonce())).block()).contains("\"status\":\"BOUND\"");
        assertThat(service.execute(bind()).block().status()).isEqualTo("TERMINAL");
        assertThat(count("source_command_issues")).isEqualTo(1);
    }

    @Test void realRollbackAfterRedisCasLeavesOrphanHeld() {
        initialize();
        var rollback=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                return super.doRollback(synchronization,status).then(Mono.error(new IllegalStateException("SYNTHETIC_COMMIT_FAILURE")));
            }
        };
        var issuer=new SourceAdmissionService(repository,redis,rollback,Clock.systemUTC()); var request=bind();
        assertThat(issuer.execute(request).block().status()).isEqualTo("UNAVAILABLE");
        assertThat(count("source_command_issues")).isZero();
        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isNotNull();
        assertThat(service.execute(request.readback()).block().status()).isEqualTo("HELD");
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD");
    }

    @Test void terminalCommittedWhileAdmissionWaitsWinsWithoutNewEvents() {
        initialize(); var locked=Sinks.<Void>empty(); var release=Sinks.<Void>empty();
        var terminal=tx.transactional(db.sql("UPDATE jobs SET status='CANCELED',ended_at=now() WHERE id=:id").bind("id",scope.jobId()).fetch().rowsUpdated()
                .flatMap(n->{locked.tryEmitEmpty();return release.asMono().thenReturn(n);})).toFuture();
        locked.asMono().block(Duration.ofSeconds(3)); var admission=service.execute(bind()).toFuture();
        assertThat(admission).isNotDone(); release.tryEmitEmpty(); terminal.join();
        assertThat(admission.join().status()).isEqualTo("TERMINAL"); assertThat(count("source_command_issues")).isZero();
        assertThat(db.sql("SELECT count(*) FROM job_events WHERE job_id=:id").bind("id",scope.jobId()).map((r,m)->r.get(0,Long.class)).one().block()).isZero();
    }

    @Test void expiryIsRecheckedAfterAuthorityLockWait() {
        initialize(); var locked=Sinks.<Void>empty(); var release=Sinks.<Void>empty();
        var holder=tx.transactional(repository.lockAuthority(scope.jobId()).flatMap(a->{locked.tryEmitEmpty(); return release.asMono().thenReturn(a);})).toFuture();
        locked.asMono().block(Duration.ofSeconds(3));
        var original=bind(); var request=new AdmissionRequest("BIND",scope,original.nonce(),original.ownerId(),Instant.now().plusMillis(250).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),0,null,0,null,null,null,null);
        var pending=service.execute(request).toFuture(); Mono.delay(Duration.ofMillis(350)).block(); release.tryEmitEmpty(); holder.join();
        assertThat(pending.join().status()).isIn("HELD","UNAVAILABLE"); assertThat(count("source_command_issues")).isZero();
        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isNull();
    }

    @Test void liveDuplicateReadsOnlyAndExpiredReservationCannotBeAdopted() {
        initialize(); var bound=service.execute(bind()).block(); var request=reserve(bound); var issued=service.execute(request).block();
        assertThat(issued.status()).isEqualTo("RESERVED"); assertThat(service.execute(bind()).block().status()).isEqualTo("BOUND");
        var future=new SourceAdmissionService(repository,redis,manager,Clock.offset(Clock.systemUTC(),Duration.ofSeconds(121)));
        var now=bind(); var futureBind=new AdmissionRequest("BIND",scope,now.nonce(),now.ownerId(),Instant.now().plusSeconds(130).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),0,null,0,null,null,null,null);
        assertThat(future.execute(futureBind).block().status()).isEqualTo("HELD");
        assertThat(service.execute(reserve(bound)).block().status()).isEqualTo("HELD"); assertThat(count("source_command_issues")).isEqualTo(2);
    }

    @Test void redisStaleRestoreAndWrongScopeNeverIssue() {
        initialize(); var bound=service.execute(bind()).block(); String initial=redis.opsForValue().get(stateKey(scope)).block();
        service.execute(reserve(bound)).block(); redis.opsForValue().set(stateKey(scope),initial).block();
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD");
        var good=scope; scope=new Scope(good.idempotencyKey(),good.jobId(),UUID.randomUUID(),good.commandType(),good.payloadFingerprint());
        assertThat(service.execute(bind()).block().code()).isEqualTo("SCOPE_CONFLICT"); assertThat(count("source_command_issues")).isEqualTo(2);
    }

    @Test void duplicateNonceCannotChangeOwnerOrExtendExpiry() {
        initialize(); var request=bind(); service.execute(request).block();
        var other=new AdmissionRequest("READBACK",scope,request.nonce(),UUID.randomUUID(),request.expiresAt(),0,null,0,null,null,null,null);
        assertThat(service.execute(other).block().status()).isEqualTo("HELD");
        var extended=new AdmissionRequest("READBACK",scope,request.nonce(),request.ownerId(),request.expiresAt().plusMillis(10),0,null,0,null,null,null,null);
        assertThat(service.execute(extended).block().status()).isEqualTo("HELD"); assertThat(count("source_command_issues")).isEqualTo(1);
    }

    @Test void mailboxDeletesOnlyOriginalFrameAndDoesNotExtendReplyTtl() {
        initialize(); var request=bind(); var mailbox=new SourceAdmissionMailbox(service,redis); String key=slotKey(request.nonce()),frame=encodeRequest(request);
        redis.opsForValue().set(key,"replacement",Duration.ofSeconds(10)).block();
        assertThat(mailbox.remove(key,frame).block()).isZero(); assertThat(redis.opsForValue().get(key).block()).isEqualTo("replacement");
        redis.opsForValue().set(key,frame,Duration.ofSeconds(10)).block(); int slot=Integer.parseInt(key.substring(key.lastIndexOf(':')+1));
        mailbox.pollSlot(slot).block(); String reply=redis.opsForValue().get(replyKey(request.nonce())).block();
        redis.expire(replyKey(request.nonce()),Duration.ofSeconds(3)).block(); redis.opsForValue().set(key,frame,Duration.ofSeconds(10)).block(); mailbox.pollSlot(slot).block();
        assertThat(redis.opsForValue().get(replyKey(request.nonce())).block()).isEqualTo(reply);
        assertThat(redis.getExpire(replyKey(request.nonce())).block()).isLessThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(count("source_command_issues")).isEqualTo(1);
    }

    @Test void realCasSuccessWithLostAcknowledgmentNeverConfirmsAnIssue() {
        initialize();
        var uncertain=org.mockito.Mockito.spy(redis);
        org.mockito.Mockito.doAnswer(call->{
            @SuppressWarnings("unchecked") var real=(reactor.core.publisher.Flux<Long>)call.callRealMethod();
            return real.flatMap(value->reactor.core.publisher.Flux.error(new IllegalStateException("SYNTHETIC_LOST_ACK")));
        }).when(uncertain).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.<Object>anyList());
        var issuer=new SourceAdmissionService(repository,uncertain,manager,Clock.systemUTC()); var request=bind();
        assertThat(issuer.execute(request).block().status()).isEqualTo("UNAVAILABLE");
        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isNotNull();
        assertThat(count("source_command_issues")).isZero();
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD");
    }

    @Test void pausedAndIdentityMismatchesNeverMutateState() {
        initialize(); db.sql("UPDATE jobs SET status='PAUSED' WHERE id=:id").bind("id",scope.jobId()).fetch().rowsUpdated().block();
        assertThat(service.execute(bind()).block().status()).isEqualTo("PAUSED");
        db.sql("UPDATE jobs SET status='PENDING' WHERE id=:id").bind("id",scope.jobId()).fetch().rowsUpdated().block();
        var good=scope;
        for(Scope incoming:List.of(new Scope(good.idempotencyKey()+":old",good.jobId(),good.commandMessageId(),good.commandType(),good.payloadFingerprint()),new Scope(good.idempotencyKey(),good.jobId(),good.commandMessageId(),"ARXIV_REEXTRACT_CONTACTS",good.payloadFingerprint()),new Scope(good.idempotencyKey(),good.jobId(),good.commandMessageId(),good.commandType(),"b".repeat(64)))) {
            scope=incoming; assertThat(service.execute(bind()).block().status()).isEqualTo("HELD");
        }
        scope=good; assertThat(count("source_command_issues")).isZero(); assertThat(redis.opsForValue().get(stateKey(scope)).block()).isNull();
    }

    String consumptionStatus() { return db.sql("SELECT consumption_status FROM source_command_issues WHERE job_id=:id AND sequence=2").bind("id",scope.jobId()).map((r,m)->r.get(0,String.class)).one().block(); }

    @Test void burnCommitsBeforeRedisAndCancelBetweenCommitsPreventsConsumption() {
        initialize(); var bound=service.execute(bind()).block(); String predecessor=redis.opsForValue().get(stateKey(scope)).block();
        var entered=Sinks.<Void>empty(); var release=Sinks.<Void>empty(); var commits=new java.util.concurrent.atomic.AtomicInteger();
        var pausing=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                return super.doCommit(synchronization,status).then(Mono.defer(()->{
                    if(commits.incrementAndGet()==1) { entered.tryEmitEmpty(); return release.asMono(); } return Mono.empty();
                }));
            }
        };
        var request=reserve(bound); var issuer=new SourceAdmissionService(repository,redis,pausing,Clock.systemUTC()); var work=issuer.execute(request).toFuture();
        entered.asMono().block(Duration.ofSeconds(3));
        assertThat(work).isNotDone(); assertThat(consumptionStatus()).isEqualTo("BURNED");
        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isEqualTo(predecessor);
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD");
        assertThat(service.execute(request).block().status()).isEqualTo("HELD");
        db.sql("UPDATE jobs SET status='CANCELED',ended_at=now() WHERE id=:id").bind("id",scope.jobId()).fetch().rowsUpdated().block();
        release.tryEmitEmpty(); assertThat(work.join().status()).isEqualTo("TERMINAL");
        assertThat(consumptionStatus()).isEqualTo("BURNED"); assertThat(redis.opsForValue().get(stateKey(scope)).block()).isEqualTo(predecessor);
    }

    @Test void secondCommitRollbackAndPredecessorRestoreCannotRefillBurnedAttempt() {
        initialize(); var bindRequest=bind(); var bound=service.execute(bindRequest).block(); String predecessor=redis.opsForValue().get(stateKey(scope)).block();
        var commits=new java.util.concurrent.atomic.AtomicInteger();
        var rollback=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                if(commits.incrementAndGet()==2) return super.doRollback(synchronization,status).then(Mono.error(new IllegalStateException("SECOND_COMMIT_ROLLBACK")));
                return super.doCommit(synchronization,status);
            }
        };
        var request=reserve(bound); var issuer=new SourceAdmissionService(repository,redis,rollback,Clock.systemUTC());
        assertThat(issuer.execute(request).block().status()).isEqualTo("UNAVAILABLE");
        assertThat(consumptionStatus()).isEqualTo("BURNED"); assertThat(SourceAdmissionState.decode(redis.opsForValue().get(stateKey(scope)).block()).generation()).isEqualTo(2);
        redis.opsForValue().set(stateKey(scope),predecessor).block();
        assertThat(service.execute(bindRequest.readback()).block().status()).isEqualTo("HELD");
        assertThat(service.execute(request).block().status()).isEqualTo("HELD");
        assertThat(service.execute(request.readback()).block().status()).isEqualTo("HELD");
        assertThat(service.execute(reserve(bound)).block().status()).isEqualTo("HELD");
        assertThat(count("source_command_issues")).isEqualTo(2);
    }

    @Test void secondCommitConfirmedButAcknowledgmentLostIsNeverExecutionReadback() {
        initialize(); var bound=service.execute(bind()).block(); var commits=new java.util.concurrent.atomic.AtomicInteger();
        var lost=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                return super.doCommit(synchronization,status).then(Mono.defer(()->commits.incrementAndGet()==2?Mono.error(new IllegalStateException("SECOND_COMMIT_ACK_LOST")):Mono.empty()));
            }
        };
        var request=reserve(bound); assertThat(new SourceAdmissionService(repository,redis,lost,Clock.systemUTC()).execute(request).block().status()).isEqualTo("UNAVAILABLE");
        assertThat(consumptionStatus()).isEqualTo("CONFIRMED");
        assertThat(service.execute(request.readback()).block().status()).isEqualTo("HELD");
        assertThat(service.execute(request).block().status()).isEqualTo("HELD");
    }

    @Test void firstBurnCommitUnknownHasNoRedisMutationOrResumableContinuation() {
        initialize(); var bound=service.execute(bind()).block(); String predecessor=redis.opsForValue().get(stateKey(scope)).block();
        var lost=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                return super.doCommit(synchronization,status).then(Mono.error(new IllegalStateException("BURN_COMMIT_ACK_LOST")));
            }
        };
        var request=reserve(bound); var invocation=new SourceAdmissionService(repository,redis,lost,Clock.systemUTC()).execute(request);
        assertThat(invocation.block().status()).isEqualTo("UNAVAILABLE");
        assertThat(consumptionStatus()).isEqualTo("BURNED"); assertThat(redis.opsForValue().get(stateKey(scope)).block()).isEqualTo(predecessor);
        assertThat(service.execute(request).block().status()).isEqualTo("HELD"); assertThat(service.execute(reserve(bound)).block().status()).isEqualTo("HELD");
    }

    @Test void samePublisherSubscriptionCannotReuseThePrivateContinuation() {
        initialize(); var bound=service.execute(bind()).block(); var invocation=service.execute(reserve(bound));
        assertThat(invocation.block().status()).isEqualTo("RESERVED");
        assertThat(invocation.block().status()).isEqualTo("HELD"); assertThat(consumptionStatus()).isEqualTo("CONFIRMED");
    }

    @Test void lostRealConsumptionCasAckRemainsBurnedAfterExactPredecessorRestore() {
        initialize(); var bound=service.execute(bind()).block(); String predecessor=redis.opsForValue().get(stateKey(scope)).block();
        var uncertain=org.mockito.Mockito.spy(redis);
        org.mockito.Mockito.doAnswer(call->{
            @SuppressWarnings("unchecked") var real=(reactor.core.publisher.Flux<Long>)call.callRealMethod();
            return real.flatMap(value->reactor.core.publisher.Flux.error(new IllegalStateException("CONSUMPTION_CAS_ACK_LOST")));
        }).when(uncertain).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.<Object>anyList());
        var request=reserve(bound); assertThat(new SourceAdmissionService(repository,uncertain,manager,Clock.systemUTC()).execute(request).block().status()).isEqualTo("UNAVAILABLE");
        assertThat(consumptionStatus()).isEqualTo("BURNED"); assertThat(SourceAdmissionState.decode(redis.opsForValue().get(stateKey(scope)).block()).generation()).isEqualTo(2);
        redis.opsForValue().set(stateKey(scope),predecessor).block();
        assertThat(service.execute(request).block().status()).isEqualTo("HELD"); assertThat(service.execute(reserve(bound)).block().status()).isEqualTo("HELD");
    }

    @Test void malformedUtf8CannotBeNormalizedIntoAnAuthoritativeState() {
        scope=new Scope(scope.idempotencyKey()+"�",scope.jobId(),scope.commandMessageId(),scope.commandType(),scope.payloadFingerprint());
        initialize(); service.execute(bind()).block(); String raw=redis.opsForValue().get(stateKey(scope)).block();
        byte[] prefix=raw.substring(0,raw.indexOf('�')).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suffix=raw.substring(raw.indexOf('�')+1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var bytes=new java.io.ByteArrayOutputStream(); bytes.writeBytes(prefix); bytes.write(0x80); bytes.writeBytes(suffix);
        redis.execute(connection->connection.stringCommands().set(java.nio.ByteBuffer.wrap(stateKey(scope).getBytes(java.nio.charset.StandardCharsets.UTF_8)),java.nio.ByteBuffer.wrap(bytes.toByteArray()))).single().block();
        assertThat(service.execute(bind()).block().status()).isNotEqualTo("BOUND");
    }

    @Test void retryAllowanceIsBoundToConfirmedReceiptAndSixActualIssues() {
        initialize(); var bound=service.execute(bind()).block(); var request=reserve(bound);
        for(int attempt=1;attempt<=6;attempt++) {
            var reply=service.execute(request).block(); assertThat(reply.status()).isEqualTo("RESERVED");
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)SourceCommandIdentity.parse(redis.opsForValue().get(stateKey(scope)).block());
            node.put("generation",node.get("generation").longValue()+1); node.put("phase",attempt==6?"EXHAUSTED":"RETRY_READY"); node.put("reason","NETWORK");
            String transientState=canonicalJson(node); redis.opsForValue().set(stateKey(scope),transientState).block();
            if(attempt<6) request=new AdmissionRequest("RESERVE",scope,UUID.randomUUID(),UUID.randomUUID(),bind().expiresAt(),node.get("generation").longValue(),SourceCommandIdentity.sha256(transientState.getBytes(java.nio.charset.StandardCharsets.UTF_8)),reply.receipt().sequence(),0L,(long)attempt+1,UUID.randomUUID(),reserve(bound).deadline());
        }
        assertThat(count("source_command_issues")).isEqualTo(7);
        assertThat(service.execute(reserve(bound)).block().status()).isEqualTo("HELD");
    }

    @Test void storedResultRecoverySurvivesLeaseExpiryAndOnlyRealAdvanceAllowsNextIndex() throws Exception {
        initialize(); var bound=service.execute(bind()).block(); service.execute(reserve(bound)).block();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)SourceCommandIdentity.parse(redis.opsForValue().get(stateKey(scope)).block());
        var fixtures=SourceCommandIdentity.JSON.readTree(java.nio.file.Path.of("../contracts/fixtures/source-admission-state-v2.json").toFile());
        var staged=SourceCommandIdentity.parse(fixtures.get("states").get(4).get("raw").asText());
        node.put("generation",3); node.put("phase","RESULT_READY"); node.set("result",staged.get("result"));
        redis.opsForValue().set(stateKey(scope),canonicalJson(node)).block();
        var future=new SourceAdmissionService(repository,redis,manager,Clock.offset(Clock.systemUTC(),Duration.ofSeconds(121)));
        var original=bind(); var recovery=new AdmissionRequest("BIND",scope,original.nonce(),original.ownerId(),Instant.now().plusSeconds(130).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),0,null,0,null,null,null,null);
        assertThat(future.execute(recovery).block().status()).isEqualTo("BOUND");
        assertThat(service.execute(reserve(bound)).block().status()).isEqualTo("HELD");
        node.put("phase","READY"); node.putNull("result"); node.putNull("invocation");
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.get("progress")).put("next_index",1).put("success",1);
        redis.opsForValue().set(stateKey(scope),canonicalJson(node)).block();
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD"); // Advance cannot precede consume+stage+advance generation floor.
        node.put("generation",4); String advanced=canonicalJson(node); redis.opsForValue().set(stateKey(scope),advanced).block();
        var next=new AdmissionRequest("RESERVE",scope,UUID.randomUUID(),UUID.randomUUID(),bind().expiresAt(),4,SourceCommandIdentity.sha256(advanced.getBytes(java.nio.charset.StandardCharsets.UTF_8)),2,1L,1L,UUID.randomUUID(),reserve(bound).deadline());
        var reply=service.execute(next).block(); assertThat(reply.status()).isEqualTo("RESERVED");
        assertThat(reply.receipt().reservedGeneration()).isEqualTo(5); assertThat(SourceAdmissionState.decode(redis.opsForValue().get(stateKey(scope)).block()).generation()).isEqualTo(6);
    }

    @Test void renewalChangesCurrentDeadlineButNeverIssuedDeadlineOrExecutionReadback() {
        initialize(); var bound=service.execute(bind()).block(); var request=reserve(bound); var issued=service.execute(request).block();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)SourceCommandIdentity.parse(redis.opsForValue().get(stateKey(scope)).block());
        node.put("generation",3); ((com.fasterxml.jackson.databind.node.ObjectNode)node.get("invocation")).put("deadline",time(request.deadline().plusSeconds(30)));
        redis.opsForValue().set(stateKey(scope),canonicalJson(node)).block(); var read=service.execute(bind()).block();
        assertThat(read.status()).isEqualTo("BOUND"); assertThat(read.issuedDeadline()).isEqualTo(issued.issuedDeadline());
        assertThat(read.receipt()).isEqualTo(issued.receipt()); assertThat(service.execute(request.readback()).block().status()).isEqualTo("HELD");
    }

    @Test void requestExpiresWhileSecondTransactionAwaitsAuthorityLock() {
        initialize(); var bound=service.execute(bind()).block(); String predecessor=redis.opsForValue().get(stateKey(scope)).block();
        var burned=Sinks.<Void>empty(); var proceed=Sinks.<Void>empty(); var commits=new java.util.concurrent.atomic.AtomicInteger();
        var pausing=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                return super.doCommit(synchronization,status).then(Mono.defer(()->{if(commits.incrementAndGet()==1){burned.tryEmitEmpty();return proceed.asMono();}return Mono.empty();}));
            }
        };
        var original=reserve(bound); var shortRequest=new AdmissionRequest("RESERVE",scope,original.nonce(),original.ownerId(),Instant.now().plusMillis(650).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),0,bound.stateDigest(),1,0L,1L,original.token(),original.deadline());
        var work=new SourceAdmissionService(repository,redis,pausing,Clock.systemUTC()).execute(shortRequest).toFuture(); burned.asMono().block(Duration.ofSeconds(3));
        var locked=Sinks.<Void>empty(); var unlock=Sinks.<Void>empty();
        var holder=tx.transactional(repository.lockAuthority(scope.jobId()).flatMap(a->{locked.tryEmitEmpty(); return unlock.asMono().thenReturn(a);})).toFuture();
        locked.asMono().block(Duration.ofSeconds(3)); proceed.tryEmitEmpty(); Mono.delay(Duration.ofMillis(750)).block(); unlock.tryEmitEmpty(); holder.join();
        assertThat(work.join().status()).isIn("HELD","UNAVAILABLE"); assertThat(consumptionStatus()).isEqualTo("BURNED");
        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isEqualTo(predecessor);
    }

    @Test void duplicateDenialWinningImmutableReplyConservativelySuppressesOriginalPermit() {
        initialize(); var bound=service.execute(bind()).block(); var burned=Sinks.<Void>empty(); var proceed=Sinks.<Void>empty(); var commits=new java.util.concurrent.atomic.AtomicInteger();
        var pausing=new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,GenericReactiveTransaction status) {
                return super.doCommit(synchronization,status).then(Mono.defer(()->{if(commits.incrementAndGet()==1){burned.tryEmitEmpty();return proceed.asMono();}return Mono.empty();}));
            }
        };
        var request=reserve(bound); String key=slotKey(request.nonce()),frame=encodeRequest(request); int slot=Integer.parseInt(key.substring(key.lastIndexOf(':')+1));
        redis.opsForValue().set(key,frame,Duration.ofSeconds(10)).block();
        var original=new SourceAdmissionMailbox(new SourceAdmissionService(repository,redis,pausing,Clock.systemUTC()),redis).pollSlot(slot).toFuture();
        burned.asMono().block(Duration.ofSeconds(3)); redis.opsForValue().set(key,frame,Duration.ofSeconds(10)).block();
        new SourceAdmissionMailbox(service,redis).pollSlot(slot).block();
        assertThat(decodeReply(redis.opsForValue().get(replyKey(request.nonce())).block()).status()).isEqualTo("HELD");
        proceed.tryEmitEmpty(); assertThatThrownBy(original::join).hasRootCauseMessage("REPLY_NOT_ACKNOWLEDGED");
        assertThat(consumptionStatus()).isEqualTo("CONFIRMED"); assertThat(decodeReply(redis.opsForValue().get(replyKey(request.nonce())).block()).status()).isEqualTo("HELD");
    }

    @Test void unexplainedReadyGenerationAfterBindIsNotAuthoritative() {
        initialize(); service.execute(bind()).block(); String raw=redis.opsForValue().get(stateKey(scope)).block();
        redis.opsForValue().set(stateKey(scope),raw.replace("\"generation\":0","\"generation\":1")).block();
        assertThat(service.execute(bind()).block().status()).isEqualTo("HELD");
    }

    @Test void slowHandlersDoNotStopBoundedCursorPollingAndStopReleasesAllHandlers() {
        var active=new java.util.concurrent.atomic.AtomicInteger(); var peak=new java.util.concurrent.atomic.AtomicInteger(); var gate=Sinks.<Void>empty();
        var slow=org.mockito.Mockito.mock(SourceAdmissionService.class);
        org.mockito.Mockito.when(slow.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(call->Mono.defer(()->{
            int count=active.incrementAndGet(); peak.accumulateAndGet(count,Math::max);
            return gate.asMono().thenReturn(AdmissionReply.deny("UNAVAILABLE","COORDINATION_FAILED")).doFinally(signal->active.decrementAndGet());
        }));
        var keys=new java.util.ArrayList<String>();
        for(int slot=0;slot<64;slot++) redis.delete("camel:worker:source-admission:{source-admission}:slot:"+slot).block();
        for(int slot=0;slot<64;slot+=8) {
            AdmissionRequest request;
            do { request=bind(); } while(!slotKey(request.nonce()).endsWith(":"+slot));
            String key=slotKey(request.nonce()); keys.add(key); redis.opsForValue().set(key,encodeRequest(request),Duration.ofSeconds(10)).block();
        }
        var mailbox=new SourceAdmissionMailbox(slow,redis);
        try {
            mailbox.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(4)).untilAsserted(()->assertThat(active.get()).isEqualTo(4));
            assertThat(peak.get()).isEqualTo(4);
            assertThat(reactor.core.publisher.Flux.fromIterable(keys).flatMap(k->redis.opsForValue().get(k)).count().block()).isEqualTo(4);
            mailbox.stop();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(active.get()).isZero());
            assertThat(mailbox.isRunning()).isFalse();
            assertThat(reactor.core.publisher.Flux.fromIterable(keys).flatMap(k->redis.opsForValue().get(k)).count().block()).isEqualTo(4);
        } finally { mailbox.stop(); gate.tryEmitEmpty(); }
    }
}
