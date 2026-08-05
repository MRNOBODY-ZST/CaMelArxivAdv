package com.camel_hub.advertisement.job.persistence;

import com.camel_hub.advertisement.job.domain.JobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JobRepository {

	private final DatabaseClient databaseClient;
	@SuppressWarnings("unused")
	private final ObjectMapper objectMapper;

	public JobRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Mono<Long> count(String status, String type) {
		return bindFilters(databaseClient.sql("""
				SELECT count(*) AS total FROM jobs
				WHERE type LIKE 'ARXIV_%'
				  AND (:statusEmpty OR status = :status)
				  AND (:typeEmpty OR type = :type)
				"""), status, type)
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Flux<JobRecord> list(int offset, int limit, String status, String type) {
		return bindFilters(databaseClient.sql(selectSql() + """
				WHERE j.type LIKE 'ARXIV_%'
				  AND (:statusEmpty OR j.status = :status)
				  AND (:typeEmpty OR j.type = :type)
				ORDER BY j.created_at DESC, j.id
				OFFSET :offset LIMIT :limit
				"""), status, type)
				.bind("offset", offset).bind("limit", limit)
				.map(this::mapJob).all();
	}

	public Mono<JobRecord> find(UUID id) {
		return databaseClient.sql(selectSql() + """
				WHERE j.id = :id AND j.type LIKE 'ARXIV_%'
				""")
				.bind("id", id).map(this::mapJob).one();
	}

	public Mono<Integer> updateStatus(
			UUID id, long expectedVersion, JobStatus target,
			boolean pauseRequested, boolean cancelRequested
	) {
		return databaseClient.sql("""
				UPDATE jobs
				SET status = :status,
				    pause_requested = :pauseRequested,
				    cancel_requested = :cancelRequested,
				    control_requested_at = now(),
				    ended_at = CASE WHEN :terminal THEN now() ELSE NULL END,
				    current_stage = CASE
				        WHEN :status = 'PAUSED' THEN 'PAUSED_BY_USER'
				        WHEN :status = 'CANCELED' THEN 'CANCELED_BY_USER'
				        WHEN :status = 'QUEUED' THEN 'WAITING_FOR_WORKER'
				        ELSE current_stage END,
				    updated_at = now(), version = version + 1
				WHERE id = :id AND version = :expectedVersion
				""")
				.bind("status", target.name())
				.bind("pauseRequested", pauseRequested)
				.bind("cancelRequested", cancelRequested)
				.bind("terminal", target.isTerminal())
				.bind("id", id).bind("expectedVersion", expectedVersion)
				.fetch().rowsUpdated().map(Long::intValue);
	}

	public Mono<JobRecord> createRetry(JobRecord original, UUID actorUserId) {
		UUID newId = UUID.randomUUID();
		UUID rootId = original.rootJobId() == null ? original.id() : original.rootJobId();
		return databaseClient.sql("""
				INSERT INTO jobs (
				    id, type, status, created_by, parameters, idempotency_key, total_count,
				    current_stage, retry_count, parent_job_id, root_job_id, checkpoint
				)
				SELECT :newId, type, 'PENDING', :actorUserId, parameters, :idempotencyKey, total_count,
				       'WAITING_FOR_WORKER', retry_count + 1, id, :rootId, checkpoint
				FROM jobs WHERE id = :originalId
				""")
				.bind("newId", newId).bind("actorUserId", actorUserId)
				.bind("idempotencyKey", original.idempotencyKey() + ":retry:" + newId)
				.bind("rootId", rootId).bind("originalId", original.id())
				.fetch().rowsUpdated()
				.flatMap(rows -> rows == 1 ? find(newId) : Mono.empty());
	}

	public Mono<Void> appendEvent(
			UUID jobId, String eventType, String stage, String message
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO job_events (job_id, event_type, stage, message)
				VALUES (:jobId, :eventType, :stage, :message)
				""").bind("jobId", jobId).bind("eventType", eventType).bind("message", message);
		statement = stage == null ? statement.bindNull("stage", String.class) : statement.bind("stage", stage);
		return statement.fetch().rowsUpdated().then();
	}

	public Flux<JobEventRecord> events(UUID jobId, long afterId, int limit) {
		return databaseClient.sql("""
				SELECT id, job_id, event_type, stage, message, CAST(details AS text) AS details, occurred_at
				FROM job_events
				WHERE job_id = :jobId AND id > :afterId
				ORDER BY id
				LIMIT :limit
				""")
				.bind("jobId", jobId).bind("afterId", afterId).bind("limit", limit)
				.map((row, metadata) -> new JobEventRecord(
						row.get("id", Long.class), row.get("job_id", UUID.class),
						row.get("event_type", String.class), row.get("stage", String.class),
						row.get("message", String.class), row.get("details", String.class),
						row.get("occurred_at", Instant.class)))
				.all();
	}

	public Mono<Boolean> isTerminal(UUID jobId) {
		return databaseClient.sql("""
				SELECT status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED') AS terminal
				FROM jobs WHERE id = :jobId
				""").bind("jobId", jobId)
				.map((row, metadata) -> Boolean.TRUE.equals(row.get("terminal", Boolean.class))).one();
	}

	private DatabaseClient.GenericExecuteSpec bindFilters(
			DatabaseClient.GenericExecuteSpec statement, String status, String type
	) {
		String safeStatus = status == null ? "" : status;
		String safeType = type == null ? "" : type;
		return statement.bind("statusEmpty", safeStatus.isEmpty()).bind("status", safeStatus)
				.bind("typeEmpty", safeType.isEmpty()).bind("type", safeType);
	}

	private String selectSql() {
		return """
				SELECT j.id, j.type, j.status, j.created_by, j.parent_job_id, j.root_job_id,
				       j.version, j.idempotency_key, j.total_count, j.processed_count,
				       j.success_count, j.skipped_count, j.failed_count, j.current_stage,
				       j.progress_percent, j.started_at, j.ended_at,
				       coalesce(j.heartbeat_at, wh.last_seen_at) AS heartbeat_at,
				       j.created_at, j.updated_at, j.error_summary
				FROM jobs j
				LEFT JOIN LATERAL (
				    SELECT max(last_seen_at) AS last_seen_at FROM worker_heartbeats w
				    WHERE w.current_job_id = j.id
				) wh ON true
				""";
	}

	private JobRecord mapJob(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new JobRecord(
				row.get("id", UUID.class), row.get("type", String.class),
				JobStatus.valueOf(row.get("status", String.class)), row.get("created_by", UUID.class),
				row.get("parent_job_id", UUID.class), row.get("root_job_id", UUID.class),
				value(row.get("version", Long.class)), row.get("idempotency_key", String.class),
				value(row.get("total_count", Long.class)), value(row.get("processed_count", Long.class)),
				value(row.get("success_count", Long.class)), value(row.get("skipped_count", Long.class)),
				value(row.get("failed_count", Long.class)), row.get("current_stage", String.class),
				decimal(row.get("progress_percent", BigDecimal.class)), row.get("started_at", Instant.class),
				row.get("ended_at", Instant.class), row.get("heartbeat_at", Instant.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class),
				row.get("error_summary", String.class));
	}

	private long value(Long value) {
		return value == null ? 0 : value;
	}

	private double decimal(BigDecimal value) {
		return value == null ? 0 : value.doubleValue();
	}

	public record JobRecord(
			UUID id, String type, JobStatus status, UUID createdBy,
			UUID parentJobId, UUID rootJobId, long version, String idempotencyKey,
			long totalCount, long processedCount, long successCount, long skippedCount, long failedCount,
			String currentStage, double progressPercent, Instant startedAt, Instant endedAt,
			Instant heartbeatAt, Instant createdAt, Instant updatedAt, String errorSummary
	) {
	}

	public record JobEventRecord(
			long id, UUID jobId, String eventType, String stage,
			String message, String details, Instant occurredAt
	) {
	}
}
