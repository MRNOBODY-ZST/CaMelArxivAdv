package com.camel_hub.advertisement.arxiv.extraction.admission;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicitly selected live proof. Owns every datastore and child; no app/Kafka/source activation. */
@Testcontainers
@Timeout(90)
// Physical-loss proof runs last; unchanged R2 children use the original fixture endpoint.
@TestMethodOrder(MethodOrderer.MethodName.class)
class SourceAdmissionPythonInteropIntegrationTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("r2_python_interop").withUsername("camel").withPassword("test-only");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.0.2-alpine")
            .withExposedPorts(6379);
    static DatabaseClient db;
    static R2dbcTransactionManager manager;
    static ReactiveStringRedisTemplate redis;
    static LettuceConnectionFactory lettuce;
    static Path python;
    static final String PAYLOAD = """
        {"parserVersion":"0.1.0","targets":[
          {"paperId":"77777777-7777-7777-7777-777777777777","arxivId":"2608.00001"},
          {"paperId":"88888888-8888-8888-8888-888888888888","arxivId":"2608.00002"}]}
        """;

    @BeforeAll static void connect() {
        String configured = System.getenv("R2_PYTHON");
        assertThat(configured).as("Explicit pre-provisioned Python required").isNotBlank();
        python = Path.of(configured);
        assertThat(python.isAbsolute() && Files.isExecutable(python)).isTrue();
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        var cf = ConnectionFactories.get("r2dbc:postgresql://camel:test-only@" + PG.getHost()
                + ":" + PG.getFirstMappedPort() + "/r2_python_interop");
        db = DatabaseClient.create(cf);
        manager = new R2dbcTransactionManager(cf);
        lettuce = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        lettuce.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(lettuce, RedisSerializationContext.string());
    }

    @AfterAll static void close() { if (lettuce != null) lettuce.destroy(); }

    @ParameterizedTest(name = "Python live {0}")
    @ValueSource(strings = {"happy", "terminal", "cancel_first", "paused", "unknown", "legacy",
            "scope_conflict", "missing_bound", "rollback", "lost_cas", "lost_commit", "cancel_between",
            "lock_expiry", "no_reply", "restored_issued", "lease_expiry", "concurrent",
            "original_repeat", "pending_expiry", "late_stage", "same_runtime_duplicate",
            "clock_reserve_rollback", "clock_renew_rollback", "clock_offset",
            "client_cancel", "lock_second_expiry", "result_recovery"})
    void pythonUsesOnlyOriginalConfirmedMailboxGrant(String scenario) throws Exception {
        var scope = new Scope("synthetic:r2:" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ARXIV_FETCH_AND_PARSE_SOURCE", SourceCommandIdentity.fingerprint(PAYLOAD));
        var repository = new SourceAdmissionRepository(db);
        db.sql("INSERT INTO jobs(id,type,status,idempotency_key,parameters) VALUES(:id,:type,'PENDING',:key,'{}')")
                .bind("id", scope.jobId()).bind("type", scope.commandType())
                .bind("key", scope.idempotencyKey()).fetch().rowsUpdated().block();
        if (!Set.of("terminal", "unknown").contains(scenario)) {
            TransactionalOperator.create(manager).transactional(repository.initializeCommand(scope.jobId(),
                    scope.commandMessageId(), scope.commandType(), scope.idempotencyKey(),
                    scenario.equals("scope_conflict") ? "b".repeat(64) : scope.payloadFingerprint(), 2)).block();
        }
        if (Set.of("terminal", "cancel_first", "paused").contains(scenario)) {
            setStatus(scope, scenario.equals("paused") ? "PAUSED" : scenario.equals("terminal") ? "FAILED" : "CANCELED");
        }
        if (scenario.equals("legacy")) redis.opsForValue().set("camel:worker:source-progress:" + scope.idempotencyKey(), "synthetic-legacy-byte-preservation").block();
        var normal = new SourceAdmissionService(repository, redis, manager, Clock.systemUTC());
        String[] predecessor = new String[1];
        var commits = new AtomicInteger();
        var secondUnlock = Sinks.<Void>empty();
        var secondHolder = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>>();
        var hooked = new R2dbcTransactionManager(manager.getConnectionFactory()) {
            @Override protected Mono<Void> doCommit(TransactionSynchronizationManager synchronization,
                                                    GenericReactiveTransaction status) {
                int commit = commits.incrementAndGet();
                if (commit == 1) {
                    return super.doCommit(synchronization, status).then(Mono.delay(Duration.ofMillis(100))).then(Mono.fromRunnable(() -> {
                        assertThat(consumption(scope)).isEqualTo("BURNED");
                        assertThat(redis.opsForValue().get(stateKey(scope)).block()).isEqualTo(predecessor[0]);
                        assertThat(redis.opsForValue().get(runnerKey(scope)).block()).isEqualTo("0");
                        assertThat(redis.opsForValue().get("r2:request-done:" + scope.jobId()).block()).isNull();
                        if (scenario.equals("cancel_between")) setStatus(scope, "CANCELED");
                        if (scenario.equals("client_cancel")) {
                            redis.opsForValue().set("r2:burned:" + scope.jobId(), "1").block();
                            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                                    assertThat(redis.opsForValue().get("r2:request-done:" + scope.jobId()).block()).isEqualTo("1"));
                        }
                        if (scenario.equals("lock_second_expiry")) {
                            var locked = Sinks.<Void>empty();
                            secondHolder.set(TransactionalOperator.create(manager).transactional(repository.lockAuthority(scope.jobId())
                                    .flatMap(authority -> { locked.tryEmitEmpty(); return secondUnlock.asMono().thenReturn(authority); })).toFuture());
                            locked.asMono().block(Duration.ofSeconds(3));
                            CompletableFuture.delayedExecutor(10_200, TimeUnit.MILLISECONDS).execute(() -> secondUnlock.tryEmitEmpty());
                        }
                    }).subscribeOn(Schedulers.boundedElastic())).then();
                }
                if (commit == 2) {
                    Mono<Void> check = Mono.fromRunnable(() -> {
                        if (!scenario.equals("cancel_between")) {
                            assertThat(consumption(scope)).isEqualTo("BURNED");
                            assertThat(SourceAdmissionState.decode(redis.opsForValue().get(stateKey(scope)).block()).generation()).isEqualTo(2);
                        }
                        assertThat(redis.opsForValue().get(runnerKey(scope)).block()).isEqualTo("0");
                        if (!scenario.equals("client_cancel")) assertThat(redis.opsForValue().get("r2:request-done:" + scope.jobId()).block()).isNull();
                    }).subscribeOn(Schedulers.boundedElastic()).then();
                    if (scenario.equals("rollback")) return check.then(super.doRollback(synchronization, status))
                            .then(Mono.error(new IllegalStateException("R2_SECOND_ROLLBACK")));
                    Mono<Void> committed = Mono.delay(Duration.ofMillis(100)).then(check).then(super.doCommit(synchronization, status));
                    if (scenario.equals("lost_commit")) return committed.then(Mono.error(new IllegalStateException("R2_COMMIT_ACK_LOST")));
                    return committed;
                }
                return super.doCommit(synchronization, status);
            }
        };
        var sourceRedis = redis;
        if (scenario.equals("lost_cas")) {
            sourceRedis = spy(redis);
            doAnswer(call -> {
                @SuppressWarnings("unchecked") var actual = (Flux<Long>) call.callRealMethod();
                return actual.flatMap(value -> Flux.error(new IllegalStateException("R2_CAS_ACK_LOST")));
            }).when(sourceRedis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyList());
        }
        var faulted = new SourceAdmissionService(repository, sourceRedis, hooked, Clock.systemUTC());
        var mailboxRedis = redis;
        if (scenario.equals("no_reply")) {
            mailboxRedis = spy(redis);
            @SuppressWarnings("unchecked") ReactiveValueOperations<String, String> lost = spy(redis.opsForValue());
            doReturn(Mono.just(true)).when(lost).setIfAbsent(anyString(), anyString(), any(Duration.class));
            doReturn(lost).when(mailboxRedis).opsForValue(); // Negative fault: actual grant is never published.
        }
        var normalMailbox = new SourceAdmissionMailbox(normal, redis);
        var reserveMailbox = new SourceAdmissionMailbox(faulted, mailboxRedis);
        Path worker = Path.of("../worker").toAbsolutePath().normalize();
        Path evidence = Path.of("build/test-results/r2-python");
        Files.createDirectories(evidence);
        var command = new ProcessBuilder(python.toString(), "-m", "pytest", "tests/jobs/test_source_admission.py",
                "-q", "-s", "-k", "test_live_bridge", "--tb=short", "--junitxml=" + evidence.toAbsolutePath().resolve(scenario + ".xml"));
        command.directory(worker.toFile()).redirectErrorStream(true);
        command.environment().clear();
        command.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "PYTHONUTF8", "1", "R2_LIVE_REQUIRED", "1",
                "R2_SCENARIO", scenario, "R2_REDIS", "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0",
                "R2_KEY", scope.idempotencyKey(), "R2_JOB", scope.jobId().toString(),
                "R2_MESSAGE", scope.commandMessageId().toString(), "R2_FINGERPRINT", scope.payloadFingerprint()));
        Process child = command.start();
        var tokens = new LinkedBlockingQueue<String>(64);
        var output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines = child.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (output.length() < 256 * 1024) output.append(line).append('\n');
                    if (line.startsWith("R2:")) tokens.offer(line.substring(3));
                }
            } catch (IOException ignored) { tokens.offer("IO_FAILED"); }
            finally { tokens.offer("EXIT"); }
        });
        boolean done = false;
        try (var input = child.outputWriter(StandardCharsets.UTF_8)) {
            while (!done) {
                String token = tokens.poll(25, TimeUnit.SECONDS);
                assertThat(token).as("Bounded Python process control").isNotNull();
                switch (token) {
                    case "BIND" -> {
                        poll(normalMailbox, scope);
                        predecessor[0] = redis.opsForValue().get(stateKey(scope)).block();
                    }
                    case "RESERVE" -> {
                        if (scenario.equals("lock_expiry")) {
                            // Hold the real job lock past the unchanged original request expiry.
                            var locked = Sinks.<Void>empty();
                            var release = Sinks.<Void>empty();
                            var holder = TransactionalOperator.create(manager).transactional(repository.lockJob(scope.jobId())
                                    .flatMap(job -> { locked.tryEmitEmpty(); return release.asMono().thenReturn(job); })).toFuture();
                            locked.asMono().block(Duration.ofSeconds(3));
                            var work = CompletableFuture.runAsync(() -> poll(reserveMailbox, scope));
                            Thread.sleep(10_200);
                            release.tryEmitEmpty(); holder.get(3, TimeUnit.SECONDS);
                            try { work.get(3, TimeUnit.SECONDS); } catch (ExecutionException expected) { /* request expired */ }
                        } else if (scenario.equals("lock_second_expiry")) {
                            try { poll(reserveMailbox, scope); } catch (RuntimeException expected) { /* unchanged request expires at real lock */ }
                            assertThat(consumption(scope)).isEqualTo("BURNED");
                            assertThat(redis.opsForValue().get(stateKey(scope)).block()).isEqualTo(predecessor[0]);
                        } else poll(reserveMailbox, scope);
                    }
                    case "POLL" -> poll(normalMailbox, scope);
                    case "START" -> normalMailbox.start();
                    case "STOP" -> normalMailbox.stop();
                    case "REMOVE" -> redis.delete(stateKey(scope)).block();
                    case "RESTORE" -> {
                        if (!scenario.equals("lock_expiry")) assertThat(consumption(scope)).isIn("BURNED", "CONFIRMED");
                        // Only this test's synthetic predecessor is restored; no external Redis flush.
                        redis.opsForValue().set(stateKey(scope), predecessor[0]).block();
                        if (scenario.equals("lock_expiry")) setStatus(scope, "CANCELED");
                    }
                    case "RESTORE_ISSUED" -> {
                        assertThat(consumption(scope)).isEqualTo("CONFIRMED");
                        String raw = redis.opsForValue().get(stateKey(scope)).block();
                        redis.opsForValue().set(stateKey(scope), raw.replace("\"generation\":2", "\"generation\":1")).block();
                    }
                    case "TERMINAL" -> setStatus(scope, "FAILED");
                    case "DONE" -> done = true;
                    default -> fail("Unexpected or failed Python bridge control; inspect retained synthetic log");
                }
                input.write("GO\n"); input.flush();
            }
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as("Python assertions exit status").isZero();
            reader.join(2000);
            String count = redis.opsForValue().get(runnerKey(scope)).block();
            assertThat(count).isEqualTo(scenario.equals("happy") ? "7" : Set.of("concurrent", "late_stage", "same_runtime_duplicate", "clock_renew_rollback", "clock_offset", "result_recovery").contains(scenario) ? "1" : "0");
            long expectedIssues = switch (scenario) {
                case "happy" -> 8;
                case "terminal", "cancel_first", "paused", "unknown", "legacy", "scope_conflict" -> 0;
                case "missing_bound", "lock_expiry" -> 1;
                default -> 2;
            };
            assertThat(issueCount(scope)).isEqualTo(expectedIssues);
            if (scenario.equals("happy")) {
                assertThat(issueCount(scope)).isEqualTo(8);
                assertThat(SourceAdmissionState.decode(redis.opsForValue().get(stateKey(scope)).block()).phase()).isEqualTo("TERMINAL");
            }
            if (scenario.equals("legacy")) assertThat(redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>("return redis.call('TTL',KEYS[1])", Long.class), List.of(stateKey(scope) + ":legacy-sha256")).single().block()).isEqualTo(-1L);
        } finally {
            secondUnlock.tryEmitEmpty();
            if (secondHolder.get() != null) secondHolder.get().get(3, TimeUnit.SECONDS);
            normalMailbox.stop(); reserveMailbox.stop();
            if (child.isAlive()) { child.destroy(); if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly(); }
            reader.join(2000);
            Files.writeString(evidence.resolve(scenario + ".log"), output.toString());
        }
    }

    @Test
    void realRedisRestartAfterStagingCannotMintAnotherInvocation() throws Throwable {
        realHandlerRestartsAtEveryDurabilityBoundary("redis_restart");
    }

    @ParameterizedTest(name = "R4 actual handler crash {0}")
    @ValueSource(strings = {"reserve_after", "stage_before", "stage_after", "result_reject", "result_unknown",
            "progress_reject", "progress_unknown", "advance_before", "advance_after", "transient_after",
            "retry_reject", "retry_unknown", "retry_commit", "terminal_reject", "terminal_unknown",
            "pending_before", "pending_after", "marker_before", "marker_after", "tombstone_before",
            "tombstone_after", "offset", "exhaust", "fail_once", "terminal_deny", "unknown",
            "scope_conflict", "marker_only", "missing_bound", "generic_drain", "generic_drain_reject",
            "pending_db_terminal", "published_db_terminal", "marker_expiry", "corrupt",
            "staged_db_terminal", "stage_ack_lost", "advance_ack_lost"})
    void realHandlerRestartsAtEveryDurabilityBoundary(String scenario) throws Throwable {
        var scope = new Scope("synthetic:r4:" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ARXIV_FETCH_AND_PARSE_SOURCE", SourceCommandIdentity.fingerprint(PAYLOAD));
        var repository = new SourceAdmissionRepository(db);
        db.sql("INSERT INTO jobs(id,type,status,idempotency_key,parameters) VALUES(:id,:type,'PENDING',:key,'{}')")
                .bind("id", scope.jobId()).bind("type", scope.commandType()).bind("key", scope.idempotencyKey())
                .fetch().rowsUpdated().block();
        if (!Set.of("unknown", "terminal_deny").contains(scenario)) {
            TransactionalOperator.create(manager).transactional(repository.initializeCommand(scope.jobId(),
                    scope.commandMessageId(), scope.commandType(), scope.idempotencyKey(),
                    scenario.equals("scope_conflict") ? "b".repeat(64) : scope.payloadFingerprint(), 2)).block();
        }
        if (scenario.equals("terminal_deny")) setStatus(scope, "FAILED");
        var mailbox = new SourceAdmissionMailbox(new SourceAdmissionService(repository, redis, manager,
                Clock.systemUTC()), redis);
        String prefix = "r4:" + scope.jobId() + ":";
        Path evidence = Path.of("build/test-results/r4-python").toAbsolutePath();
        Files.createDirectories(evidence);
        boolean noCrash = Set.of("exhaust", "fail_once", "terminal_deny", "unknown", "scope_conflict",
                "generic_drain", "stage_ack_lost", "advance_ack_lost").contains(scenario);
        long beforeRedisRunner = 0;
        List<String> beforeRedisObservations = List.of();
        Throwable primaryFailure = null;
        int childRedisPort = REDIS.getMappedPort(6379);
        mailbox.start();
        try {
            for (int restart = 0; restart < (noCrash ? 1 : 2); restart++) {
                if (restart == 1) {
                    assertThat(redis.opsForValue().get(prefix + "offsets").block()).isNull();
                    String beforeRestart = redis.opsForValue().get(stateKey(scope)).block();
                    Files.writeString(evidence.resolve(scenario + "-crash-state.json"), beforeRestart);
                    var crashed = SourceAdmissionState.decode(beforeRestart);
                    String expectedPhase = switch (scenario) {
                        case "reserve_after", "stage_before" -> "RESERVED";
                        case "stage_after", "result_reject", "result_unknown", "progress_reject",
                             "progress_unknown", "advance_before", "marker_only", "missing_bound",
                             "generic_drain_reject", "corrupt", "staged_db_terminal", "redis_restart" -> "RESULT_READY";
                        case "advance_after", "terminal_reject", "terminal_unknown", "pending_before",
                             "published_db_terminal" -> "READY";
                        case "transient_after", "retry_reject", "retry_unknown", "retry_commit" -> "RETRY_READY";
                        case "pending_after", "pending_db_terminal", "marker_before", "marker_after",
                             "tombstone_before" -> "TERMINAL_PENDING";
                        default -> "TERMINAL";
                    };
                    assertThat(crashed.phase()).isEqualTo(expectedPhase);
                    if (Set.of("reserve_after", "stage_before").contains(scenario)) Thread.sleep(6200);
                    if (Set.of("pending_db_terminal", "published_db_terminal", "staged_db_terminal").contains(scenario)) setStatus(scope, "FAILED");
                    if (scenario.equals("marker_expiry")) {
                        // Actual expiration on only this owned generic marker, not a source-state rewrite.
                        redis.expire("camel:worker:processed:" + scope.idempotencyKey(), Duration.ofMillis(1)).block();
                        Thread.sleep(10);
                    }
                    if (scenario.equals("marker_only")) redis.opsForValue().set("camel:worker:processed:" + scope.idempotencyKey(), "1").block();
                    if (scenario.equals("missing_bound")) redis.delete(stateKey(scope)).block();
                    if (scenario.equals("corrupt")) redis.opsForValue().set(stateKey(scope), "invalid-synthetic-state").block();
                    if (scenario.equals("redis_restart")) {
                        mailbox.stop();
                        // Preserve evidence outside Redis BEFORE a physical kill. The first child is dead.
                        beforeRedisRunner = Long.parseLong(redis.opsForValue().get(prefix + "runner").block());
                        beforeRedisObservations = redis.opsForList().range(prefix + "observations", 0, -1).collectList().block();
                        var beforeAccepted = redis.opsForList().range(prefix + "accepted", 0, -1).collectList().block();
                        assertThat(beforeRedisRunner).isEqualTo(1);
                        assertThat(beforeAccepted).hasSize(1); // STARTED only; staged result not sent.
                        assertThat(issueCount(scope)).isEqualTo(2);
                        String ownedId = REDIS.getContainerId();
                        int ownedPort = REDIS.getMappedPort(6379);
                        assertThat(REDIS.getDockerImageName()).isEqualTo("redis:8.0.2-alpine");
                        Files.writeString(evidence.resolve("redis-restart-before.log"),
                                "ownedContainer=" + ownedId + "\nport=" + ownedPort + "\nrunner=" + beforeRedisRunner
                                + "\naccepted=" + beforeAccepted + "\nobservations=" + beforeRedisObservations);
                        var docker = org.testcontainers.DockerClientFactory.instance().client();
                        String beforeStarted = docker.inspectContainerCmd(ownedId).exec().getState().getStartedAt();
                        lettuce.destroy(); // No old-socket auto-reconnect claim.
                        docker.restartContainerCmd(ownedId).withSignal("KILL").withTimeout(0).exec();
                        var inspected = docker.inspectContainerCmd(ownedId).exec();
                        childRedisPort = Integer.parseInt(inspected.getNetworkSettings().getPorts().getBindings()
                                .get(com.github.dockerjava.api.model.ExposedPort.tcp(6379))[0].getHostPortSpec());
                        assertThat(childRedisPort).isBetween(1, 65535);
                        Files.writeString(evidence.resolve("redis-restart-engine.log"),
                                "ownedContainer=" + ownedId + "\nportBefore=" + ownedPort
                                + "\nportAfter=" + childRedisPort
                                + "\nstartedBefore=" + beforeStarted
                                + "\nstartedAfter=" + inspected.getState().getStartedAt());
                        assertThat(inspected.getId()).isEqualTo(ownedId);
                        assertThat(inspected.getState().getStartedAt()).isNotEqualTo(beforeStarted);
                        // Testcontainers caches the pre-restart port; use inspected owned identity only.
                        lettuce = new LettuceConnectionFactory(REDIS.getHost(), childRedisPort);
                        lettuce.afterPropertiesSet();
                        redis = new ReactiveStringRedisTemplate(lettuce, RedisSerializationContext.string());
                        mailbox = new SourceAdmissionMailbox(new SourceAdmissionService(repository, redis, manager,
                                Clock.systemUTC()), redis);
                        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).ignoreExceptions()
                                .untilAsserted(() -> assertThat(redis.opsForValue().get(stateKey(scope)).block(Duration.ofSeconds(1))).isNull());
                        // No flush/restore/seed. PG still contains the confirmed consumed issue.
                        assertThat(redis.opsForValue().get(prefix + "runner").block()).isNull();
                        assertThat(issueCount(scope)).isEqualTo(2);
                        Files.writeString(evidence.resolve("redis-restart-after.log"),
                                "ownedContainer=" + ownedId + "\nportBefore=" + ownedPort + "\nportAfter=" + childRedisPort
                                + "\nstartedBefore=" + beforeStarted + "\nstartedAfter=" + inspected.getState().getStartedAt()
                                + "\nstateAbsent=true\nrunnerAbsent=true\nissues=2");
                        mailbox.start();
                    }
                }
                Path log = evidence.resolve(scenario + "-" + restart + ".log");
                var command = new ProcessBuilder(python.toString(), "-m", "pytest", "tests/test_main.py",
                        "-q", "-s", "-k", "test_required_live_handler_bridge", "--tb=short",
                        "--junitxml=" + evidence.resolve(scenario + "-" + restart + ".xml"));
                command.directory(Path.of("../worker").toAbsolutePath().normalize().toFile())
                        .redirectErrorStream(true).redirectOutput(log.toFile());
                command.environment().clear();
                command.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "PYTHONUTF8", "1",
                        "R4_LIVE_REQUIRED", "1", "R4_SCENARIO", scenario, "R4_RESTART", Integer.toString(restart),
                        "R4_REDIS", "redis://" + REDIS.getHost() + ":" + childRedisPort + "/0",
                        "R4_KEY", scope.idempotencyKey(), "R4_JOB", scope.jobId().toString(),
                        "R4_MESSAGE", scope.commandMessageId().toString(), "R4_FINGERPRINT", scope.payloadFingerprint()));
                Process child = command.start();
                try {
                    assertThat(child.waitFor(40, TimeUnit.SECONDS)).as("bounded selected R4 child: " + log).isTrue();
                    assertThat(child.exitValue()).as("R4 child: " + log).isEqualTo(restart == 0 && !noCrash ? 73 : 0);
                    if (restart == 1 || noCrash) assertThat(Files.readString(log)).contains("R4_VERIFIED " + scenario, "1 passed");
                } finally {
                    if (child.isAlive()) { child.destroy(); if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly(); }
                }
            }
            long runners = switch (scenario) {
                case "reserve_after", "terminal_deny", "unknown", "scope_conflict" -> 0;
                case "stage_before", "marker_only", "missing_bound", "generic_drain", "generic_drain_reject",
                     "corrupt", "staged_db_terminal", "redis_restart" -> 1;
                case "exhaust", "retry_reject", "retry_unknown", "retry_commit", "transient_after" -> 6;
                case "fail_once" -> 4;
                default -> 2;
            };
            String count = redis.opsForValue().get(prefix + "runner").block();
            assertThat(beforeRedisRunner + (count == null ? 0 : Long.parseLong(count))).isEqualTo(runners);
            long expectedIssues = Set.of("terminal_deny", "unknown", "scope_conflict").contains(scenario)
                    ? 0 : runners + (scenario.equals("reserve_after") ? 2 : 1);
            assertThat(issueCount(scope)).isEqualTo(expectedIssues);
            assertThat(redis.opsForList().range(prefix + "observations", 0, -1).collectList().block())
                    .contains("verified:" + scenario, "offset-confirmed");
            String raw = redis.opsForValue().get(stateKey(scope)).block();
            if (!Set.of("reserve_after", "stage_before", "terminal_deny", "unknown", "scope_conflict",
                    "marker_only", "missing_bound", "published_db_terminal", "corrupt", "staged_db_terminal", "redis_restart").contains(scenario)) {
                var state = SourceAdmissionState.decode(raw);
                assertThat(state.phase()).isEqualTo("TERMINAL");
                assertThat(redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                        "return redis.call('TTL',KEYS[1])", Long.class), List.of(stateKey(scope)))
                        .single().block()).isEqualTo(-1L);
            }
            if (Set.of("terminal_deny", "unknown", "scope_conflict", "missing_bound", "redis_restart").contains(scenario)) {
                assertThat(raw).isNull();
            }
            if (scenario.equals("published_db_terminal")) {
                assertThat(SourceAdmissionState.decode(raw).phase()).isEqualTo("READY");
                assertThat(SourceAdmissionState.decode(raw).nextIndex()).isEqualTo(2);
            }
            if (scenario.equals("staged_db_terminal")) {
                assertThat(SourceAdmissionState.decode(raw).phase()).isEqualTo("RESULT_READY");
                assertThat(SourceAdmissionState.decode(raw).nextIndex()).isZero();
            }
            if (scenario.equals("redis_restart")) {
                assertThat(count).isNull();
                assertThat(redis.opsForList().range(prefix + "observations", 0, -1).collectList().block())
                        .noneMatch(event -> event.startsWith("runner:"));
                var postAccepted = redis.opsForList().range(prefix + "accepted", 0, -1).collectList().block();
                assertThat(postAccepted).singleElement().asString().contains("SOURCE_RECONCILIATION_REQUIRED");
            }
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            long savedRunner = beforeRedisRunner;
            List<String> savedObservations = beforeRedisObservations;
            finalizeEvidence(primaryFailure, mailbox::stop,
                () -> Files.writeString(evidence.resolve(scenario + "-observations.log"),
                    String.join("\n", redis.opsForList().range(prefix + "observations", 0, -1).collectList().block())
                    + "\nissues=" + issueCount(scope) + "\nrunner=" + redis.opsForValue().get(prefix + "runner").block()
                    + "\nstate=" + redis.opsForValue().get(stateKey(scope)).block()
                    + "\npre-restart-runner=" + savedRunner + "\npre-restart-observations=" + savedObservations),
                snapshotFailure -> Files.writeString(evidence.resolve(scenario + "-snapshot-unavailable.log"),
                        "snapshotFailureType=" + snapshotFailure.getClass().getSimpleName()
                        + "\npre-restart-runner=" + savedRunner + "\npre-restart-observations=" + savedObservations));
        }
    }

    @ParameterizedTest(name = "finalizer primary={0}, fault={1}")
    @org.junit.jupiter.params.provider.CsvSource({"true,snapshot_io", "true,fallback_io", "true,cleanup",
            "false,snapshot_io", "false,fallback_io", "false,cleanup"})
    void finalizerPreservesPrimaryAndFailsClosed(boolean hasPrimary, String fault) {
        AssertionError primary = hasPrimary ? new AssertionError("CONTROLLED_PRIMARY") : null;
        var cleanupFailure = new IllegalStateException("CONTROLLED_CLEANUP");
        Exception snapshotFailure = fault.equals("snapshot_io")
                ? new IOException("CONTROLLED_SNAPSHOT_WRITE") : new IllegalStateException("CONTROLLED_SNAPSHOT_READ");
        var fallbackFailure = new IOException("CONTROLLED_FALLBACK_WRITE");
        var calls = new ArrayList<String>();
        Throwable caught = catchThrowable(() -> {
            try {
                if (primary != null) throw primary;
            } finally {
                finalizeEvidence(primary, () -> {
                    calls.add("cleanup");
                    if (fault.equals("cleanup")) throw cleanupFailure;
                }, () -> {
                    calls.add("snapshot");
                    if (!fault.equals("cleanup")) throw snapshotFailure;
                }, failure -> {
                    assertThat(failure).isSameAs(snapshotFailure);
                    calls.add("fallback");
                    if (fault.equals("fallback_io")) throw fallbackFailure;
                });
            }
        });
        Throwable firstEvidenceFailure = fault.equals("cleanup") ? cleanupFailure : snapshotFailure;
        assertThat(caught).isSameAs(hasPrimary ? primary : firstEvidenceFailure);
        if (hasPrimary) {
            assertThat(caught.getSuppressed()).containsExactly(fault.equals("fallback_io")
                    ? new Throwable[]{snapshotFailure, fallbackFailure} : new Throwable[]{firstEvidenceFailure});
        } else {
            assertThat(caught.getSuppressed()).containsExactly(fault.equals("fallback_io")
                    ? new Throwable[]{fallbackFailure} : new Throwable[]{});
        }
        assertThat(calls).containsExactlyElementsOf(fault.equals("cleanup")
                ? List.of("cleanup", "snapshot") : List.of("cleanup", "snapshot", "fallback"));
    }

    static void finalizeEvidence(Throwable primary, org.junit.jupiter.api.function.Executable cleanup,
                                 org.junit.jupiter.api.function.Executable snapshot,
                                 org.junit.jupiter.api.function.ThrowingConsumer<Throwable> fallback) throws Throwable {
        var failures = new ArrayList<Throwable>();
        try {
            cleanup.execute();
        } catch (Throwable cleanupFailure) {
            failures.add(cleanupFailure);
        }
        try {
            snapshot.execute();
        } catch (Throwable snapshotFailure) {
            failures.add(snapshotFailure);
            try {
                fallback.accept(snapshotFailure);
            } catch (Throwable fallbackFailure) {
                failures.add(fallbackFailure);
            }
        }
        if (failures.isEmpty()) return;
        Throwable retained = primary == null ? failures.removeFirst() : primary;
        for (Throwable failure : failures) {
            if (failure != retained) retained.addSuppressed(failure);
        }
        // The caller rethrows its original failure; otherwise evidence loss must fail closed.
        if (primary == null) throw retained;
    }

    static String runnerKey(Scope scope) { return "r2:runner:" + scope.jobId(); }
    static void setStatus(Scope scope, String status) {
        db.sql("UPDATE jobs SET status=:status,ended_at=CASE WHEN :status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED') THEN now() ELSE NULL END WHERE id=:id")
                .bind("status", status).bind("id", scope.jobId()).fetch().rowsUpdated().block();
    }
    static String consumption(Scope scope) {
        return db.sql("SELECT consumption_status FROM source_command_issues WHERE job_id=:id AND sequence=2")
                .bind("id", scope.jobId()).map((row, metadata) -> row.get(0, String.class)).one().block();
    }
    static long issueCount(Scope scope) {
        return db.sql("SELECT count(*) FROM source_command_issues WHERE job_id=:id")
                .bind("id", scope.jobId()).map((row, metadata) -> row.get(0, Long.class)).one().block();
    }
    static void poll(SourceAdmissionMailbox mailbox, Scope scope) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            for (int slot = 0; slot < 64; slot++) {
                String raw = redis.opsForValue().get("camel:worker:source-admission:{source-admission}:slot:" + slot).block();
                if (raw != null && decodeRequest(raw).scope().equals(scope)) {
                    mailbox.pollSlot(slot).block(Duration.ofSeconds(12));
                    return;
                }
            }
            try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("R2_INTERRUPTED"); }
        }
        throw new AssertionError("R2_MAILBOX_FRAME_MISSING");
    }
}
