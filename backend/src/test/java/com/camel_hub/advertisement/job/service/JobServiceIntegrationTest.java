package com.camel_hub.advertisement.job.service;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.job.domain.JobAction;
import com.camel_hub.advertisement.job.domain.JobStateMachine;
import com.camel_hub.advertisement.job.domain.JobStatus;
import com.camel_hub.advertisement.job.persistence.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_jobs_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	private static final UUID ACTOR_ID = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");
	private DatabaseClient databaseClient;
	private JobService service;

	@BeforeEach
	void setUp() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
			Flyway.configure()
					.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").load().migrate();
		}
		var connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
		databaseClient.sql("DELETE FROM audit_logs").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM jobs").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES (:id, 'job-admin', 'job-admin@example.invalid', 'hash', 'Job Admin')
				""").bind("id", ACTOR_ID).fetch().rowsUpdated().block();
		AuditService audit = mock(AuditService.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		service = new JobService(
				new JobRepository(databaseClient, new ObjectMapper().findAndRegisterModules()),
				new JobStateMachine(), audit, hasher,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void pausesResumesAndCancelsWithVersionedEvents() {
		UUID jobId = insertJob(JobStatus.RUNNING, false);

		var paused = service.control(jobId, JobAction.PAUSE, ACTOR_ID, context()).block();
		var resumed = service.control(jobId, JobAction.RESUME, ACTOR_ID, context()).block();
		var canceled = service.control(jobId, JobAction.CANCEL, ACTOR_ID, context()).block();

		assertThat(paused.status()).isEqualTo(JobStatus.PAUSED);
		assertThat(resumed.status()).isEqualTo(JobStatus.QUEUED);
		assertThat(canceled.status()).isEqualTo(JobStatus.CANCELED);
		assertThat(canceled.version()).isEqualTo(3);
		assertThat(canceled.endedAt()).isNotNull();
		assertThat(service.events(jobId, 0, 20).block()).extracting(JobService.JobEventView::eventType)
				.containsExactly("JOB_PAUSED", "JOB_RESUMED", "JOB_CANCELED");
	}

	@Test
	void optimisticConcurrencyAllowsOnlyOneControlCommandForTheSameVersion() {
		UUID jobId = insertJob(JobStatus.RUNNING, false);

		List<reactor.core.publisher.Signal<JobService.JobView>> signals = Flux.merge(
				service.control(jobId, JobAction.PAUSE, ACTOR_ID, context()).materialize(),
				service.control(jobId, JobAction.CANCEL, ACTOR_ID, context()).materialize())
				.filter(signal -> signal.isOnNext() || signal.isOnError())
				.collectList().block();

		assertThat(signals).hasSize(2);
		assertThat(signals).filteredOn(reactor.core.publisher.Signal::isOnNext).hasSize(1);
		assertThat(signals).filteredOn(reactor.core.publisher.Signal::isOnError).hasSize(1)
				.first().extracting(signal -> signal.getThrowable().getClass())
				.isEqualTo(JobConflictException.class);
	}

	@Test
	void retryCreatesANewLineageAndPreservesTheOriginalJob() {
		UUID failedId = insertJob(JobStatus.FAILED, true);

		JobService.JobView retry = service.control(
				failedId, JobAction.RETRY, ACTOR_ID, context()).block();

		assertThat(retry.id()).isNotEqualTo(failedId);
		assertThat(retry.status()).isEqualTo(JobStatus.PENDING);
		assertThat(retry.parentJobId()).isEqualTo(failedId);
		assertThat(retry.rootJobId()).isEqualTo(failedId);
		assertThat(service.get(failedId).block().status()).isEqualTo(JobStatus.FAILED);
		assertThat(databaseClient.sql("""
				SELECT count(*) AS total FROM outbox_messages
				WHERE aggregate_id = :jobId AND routing_key = 'arxiv.import.metadata'
				""").bind("jobId", retry.id())
				.map((row, metadata) -> row.get("total", Long.class)).one().block()).isEqualTo(1L);
		assertThat(databaseClient.sql("""
				SELECT payload->>'jobId' FROM outbox_messages WHERE aggregate_id = :jobId
				""").bind("jobId", retry.id())
				.map((row, metadata) -> row.get(0, String.class)).one().block())
				.isEqualTo(retry.id().toString());
	}

	@Test
	void liveWorkerHeartbeatOverridesAnOlderJobResultHeartbeat() {
		UUID jobId = insertJob(JobStatus.RUNNING, false);
		Instant jobHeartbeat = Instant.now().minusSeconds(7_200);
		Instant workerHeartbeat = Instant.now();
		databaseClient.sql("UPDATE jobs SET heartbeat_at = :heartbeat WHERE id = :jobId")
				.bind("heartbeat", jobHeartbeat).bind("jobId", jobId)
				.fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO worker_heartbeats (
				    worker_id, worker_type, version, status, current_job_id, last_seen_at
				) VALUES ('worker-heartbeat-test', 'ARXIV', 'test', 'BUSY', :jobId, :lastSeenAt)
				ON CONFLICT (worker_id) DO UPDATE SET
				  current_job_id = EXCLUDED.current_job_id,
				  status = EXCLUDED.status,
				  last_seen_at = EXCLUDED.last_seen_at
				""").bind("jobId", jobId).bind("lastSeenAt", workerHeartbeat)
				.fetch().rowsUpdated().block();

		JobService.JobView view = service.get(jobId).block();

		assertThat(view.workerStale()).isFalse();
		assertThat(view.heartbeatAt()).isAfter(jobHeartbeat);
		assertThat(view.heartbeatAt()).isEqualTo(workerHeartbeat);
	}

	private UUID insertJob(JobStatus status, boolean terminal) {
		UUID id = UUID.randomUUID();
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO jobs (
				    id, type, status, created_by, idempotency_key, current_stage, started_at, ended_at
				)
				VALUES (
				    :id, 'ARXIV_IMPORT_METADATA', :status, :actor, :key, 'FETCHING', now(), :endedAt
				)
				""")
				.bind("id", id).bind("status", status.name()).bind("actor", ACTOR_ID)
				.bind("key", "test-job:" + id);
		statement = terminal ? statement.bind("endedAt", java.time.Instant.now())
				: statement.bindNull("endedAt", java.time.Instant.class);
		statement.fetch().rowsUpdated().block();
		return id;
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("192.0.2.5", "job-test", "0123456789abcdef");
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
