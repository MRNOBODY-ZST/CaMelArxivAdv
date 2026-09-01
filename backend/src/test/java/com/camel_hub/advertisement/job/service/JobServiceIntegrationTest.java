package com.camel_hub.advertisement.job.service;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.job.domain.JobAction;
import com.camel_hub.advertisement.job.domain.JobStateMachine;
import com.camel_hub.advertisement.job.domain.JobStatus;
import com.camel_hub.advertisement.job.persistence.JobRepository;
import com.camel_hub.advertisement.messaging.ArxivResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
		databaseClient.sql("""
				INSERT INTO job_items (job_id, external_key, status)
				VALUES (:jobId, 'metadata-page-1', 'PENDING')
				""").bind("jobId", jobId).fetch().rowsUpdated().block();

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
		assertThat(databaseClient.sql("""
				SELECT status FROM job_items WHERE job_id = :jobId
				""").bind("jobId", jobId)
				.map((row, metadata) -> row.get("status", String.class)).one().block())
				.isEqualTo("PENDING");
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

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"FAILED", "CANCELED"})
	void sourceRetryRecreatesPendingItemsThatMatchEveryRepublishedTarget(JobStatus terminalStatus) {
		SourceJob original = insertSourceJob(terminalStatus);

		JobService.JobView retry = service.control(
				original.jobId(), JobAction.RETRY, ACTOR_ID, context()).block();

		assertThat(retry.status()).isEqualTo(JobStatus.PENDING);
		assertThat(retry.totalCount()).isEqualTo(2);
		assertThat(databaseClient.sql("""
				SELECT count(*) AS total
				FROM job_items item
				JOIN outbox_messages message ON message.aggregate_id = item.job_id
				CROSS JOIN LATERAL jsonb_array_elements(
				  message.payload->'payload'->'targets') target
				WHERE item.job_id = :jobId
				  AND item.status = 'PENDING'
				  AND item.external_key = target->>'paperId'
				""").bind("jobId", retry.id())
				.map((row, metadata) -> row.get("total", Long.class)).one().block()).isEqualTo(2L);
		assertThat(databaseClient.sql("""
				SELECT count(*) AS total FROM job_items
				WHERE job_id = :jobId AND external_key = ANY(:externalKeys)
				""").bind("jobId", retry.id())
				.bind("externalKeys", original.paperIds().stream()
						.map(UUID::toString).toArray(String[]::new))
				.map((row, metadata) -> row.get("total", Long.class)).one().block()).isEqualTo(2L);
		assertThat(databaseClient.sql("""
				SELECT count(*) AS total FROM job_items
				WHERE job_id = :jobId AND status <> 'PENDING'
				""").bind("jobId", retry.id())
				.map((row, metadata) -> row.get("total", Long.class)).one().block()).isZero();
	}

	@Test
	void sourceRetryRebuildsItemsFromStoredTargetsWhenALegacyRetryHasNoItems() {
		SourceJob original = insertSourceJob(JobStatus.FAILED);
		databaseClient.sql("DELETE FROM job_items WHERE job_id = :jobId")
				.bind("jobId", original.jobId()).fetch().rowsUpdated().block();

		JobService.JobView retry = service.control(
				original.jobId(), JobAction.RETRY, ACTOR_ID, context()).block();

		assertThat(databaseClient.sql("""
				SELECT coalesce(array_agg(external_key ORDER BY external_key), ARRAY[]::text[]) AS keys
				FROM job_items WHERE job_id = :jobId AND status = 'PENDING'
				""").bind("jobId", retry.id())
				.map((row, metadata) -> List.of(row.get("keys", String[].class))).one().block())
				.containsExactlyElementsOf(original.paperIds().stream()
						.map(UUID::toString).sorted().toList());
	}

	@Test
	void sourceRetryUsesStoredTargetsAfterZeroItemWatchdogFailure() {
		SourceJob original = insertSourceJob(JobStatus.FAILED);
		Instant now = Instant.parse("2026-09-02T09:00:00Z");
		databaseClient.sql("DELETE FROM job_items WHERE job_id = :jobId")
				.bind("jobId", original.jobId()).fetch().rowsUpdated().block();
		databaseClient.sql("""
				UPDATE jobs SET status = 'RUNNING', ended_at = NULL,
				  current_stage = 'AWAITING_ITEM_RESULTS',
				  last_message_at = :lastMessage, updated_at = :lastMessage
				WHERE id = :jobId
				""").bind("lastMessage", now.minus(Duration.ofMinutes(16)))
				.bind("jobId", original.jobId()).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO job_events (job_id, event_type, stage, message)
				VALUES (:jobId, 'ARXIV_JOB_COMPLETION_DEFERRED',
				        'AWAITING_ITEM_RESULTS', 'Waiting for item results')
				""").bind("jobId", original.jobId()).fetch().rowsUpdated().block();

		assertThat(new ArxivResultRepository(databaseClient)
				.reconcileStaleDeferredSourceCompletions(
						now.minus(Duration.ofMinutes(15)), now).block()).isEqualTo(1);
		assertThat(service.get(original.jobId()).block().status()).isEqualTo(JobStatus.FAILED);
		assertThat(service.get(original.jobId()).block().totalCount()).isZero();

		JobService.JobView retry = service.control(
				original.jobId(), JobAction.RETRY, ACTOR_ID, context()).block();

		assertThat(retry.status()).isEqualTo(JobStatus.PENDING);
		assertThat(retry.totalCount()).isEqualTo(2);
		assertThat(databaseClient.sql("""
				SELECT count(*) AS total FROM job_items
				WHERE job_id = :jobId AND status = 'PENDING'
				""").bind("jobId", retry.id())
				.map((row, metadata) -> row.get("total", Long.class)).one().block())
				.isEqualTo(2);
		assertThat(databaseClient.sql("""
				SELECT jsonb_array_length(payload->'payload'->'targets') AS total
				FROM outbox_messages WHERE aggregate_id = :jobId
				""").bind("jobId", retry.id())
				.map((row, metadata) -> row.get("total", Integer.class)).one().block())
				.isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"PENDING", "RUNNING"})
	void cancelingASourceJobClosesOpenItemsAndUsesExactPersistedCounters(String openStatus) {
		SourceJob source = insertSourceJob(JobStatus.FAILED);
		databaseClient.sql("""
				UPDATE jobs SET status = 'RUNNING', ended_at = NULL, current_stage = 'EXTRACTING',
				  processed_count = 2, success_count = 2, failed_count = 0,
				  progress_percent = 100
				WHERE id = :jobId
				""").bind("jobId", source.jobId()).fetch().rowsUpdated().block();
		databaseClient.sql("""
				UPDATE job_items SET status = CASE
				  WHEN external_key = :firstPaper THEN 'SUCCEEDED' ELSE :openStatus END,
				  completed_at = CASE WHEN external_key = :firstPaper THEN now() ELSE NULL END
				WHERE job_id = :jobId
				""").bind("firstPaper", source.paperIds().getFirst().toString())
				.bind("openStatus", openStatus)
				.bind("jobId", source.jobId()).fetch().rowsUpdated().block();

		JobService.JobView canceled = service.control(
				source.jobId(), JobAction.CANCEL, ACTOR_ID, context()).block();

		assertThat(canceled.status()).isEqualTo(JobStatus.CANCELED);
		assertThat(databaseClient.sql("""
				SELECT count(*) AS total FROM job_items
				WHERE job_id = :jobId AND status = 'CANCELED' AND completed_at IS NOT NULL
				""").bind("jobId", source.jobId())
				.map((row, metadata) -> row.get("total", Long.class)).one().block()).isEqualTo(1L);
		assertThat(databaseClient.sql("""
				SELECT processed_count, success_count, skipped_count, failed_count,
				       total_count, progress_percent::bigint AS progress
				FROM jobs WHERE id = :jobId
				""").bind("jobId", source.jobId()).map((row, metadata) -> List.of(
						row.get("processed_count", Long.class), row.get("success_count", Long.class),
						row.get("skipped_count", Long.class), row.get("failed_count", Long.class),
						row.get("total_count", Long.class), row.get("progress", Long.class)))
				.one().block()).containsExactly(1L, 1L, 0L, 0L, 2L, 50L);
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

	private SourceJob insertSourceJob(JobStatus status) {
		UUID jobId = UUID.randomUUID();
		List<UUID> paperIds = List.of(UUID.randomUUID(), UUID.randomUUID());
		databaseClient.sql("""
				INSERT INTO jobs (
				  id, type, status, created_by, parameters, idempotency_key,
				  total_count, processed_count, success_count, failed_count,
				  current_stage, started_at, ended_at)
				VALUES (
				  :id, 'ARXIV_FETCH_AND_PARSE_SOURCE', :status, :actor,
				  jsonb_build_object(
				    'parserVersion', '0.1.0',
				    'targets', jsonb_build_array(
				      jsonb_build_object('paperId', :firstPaper, 'arxivId', '2609.00001'),
				      jsonb_build_object('paperId', :secondPaper, 'arxivId', '2609.00002'))),
				  :key, 2, 2, 1, 1, 'FAILED', now(), now())
				""").bind("id", jobId).bind("status", status.name()).bind("actor", ACTOR_ID)
				.bind("firstPaper", paperIds.get(0)).bind("secondPaper", paperIds.get(1))
				.bind("key", "source-retry-test:" + jobId).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO job_items (job_id, external_key, status, attempt_count, completed_at)
				VALUES (:jobId, :firstPaper, 'SUCCEEDED', 2, now()),
				       (:jobId, :secondPaper, 'FAILED', 3, now())
				""").bind("jobId", jobId).bind("firstPaper", paperIds.get(0).toString())
				.bind("secondPaper", paperIds.get(1).toString()).fetch().rowsUpdated().block();
		return new SourceJob(jobId, paperIds);
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("192.0.2.5", "job-test", "0123456789abcdef");
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}

	private record SourceJob(UUID jobId, List<UUID> paperIds) { }
}
