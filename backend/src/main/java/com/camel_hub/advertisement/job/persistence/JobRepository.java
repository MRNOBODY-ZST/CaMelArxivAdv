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

	public Mono<JobRecord> createRetry(JobRecord original, UUID actorUserId, String traceId) {
		UUID newId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		UUID rootId = original.rootJobId() == null ? original.id() : original.rootJobId();
		String idempotencyKey = original.idempotencyKey() + ":retry:" + newId;
		String routingKey = routingKey(original.type());
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
				.bind("idempotencyKey", idempotencyKey)
				.bind("rootId", rootId).bind("originalId", original.id())
				.fetch().rowsUpdated()
				.flatMap(rows -> rows == 1
						? copyRetrySourceItems(original, newId).then(insertRetryOutbox(
								newId, messageId, original.type(), routingKey,
								idempotencyKey, safeTrace(traceId))).then(find(newId))
						: Mono.empty());
	}

	private Mono<Void> copyRetrySourceItems(JobRecord original, UUID newJobId) {
		if (!isExtractionJob(original.type())) {
			return Mono.empty();
		}
		return databaseClient.sql("""
				INSERT INTO job_items (job_id, external_key, status)
				SELECT :newJobId, (target->>'paperId')::uuid::text, 'PENDING'
				FROM jobs source
				CROSS JOIN LATERAL jsonb_array_elements(source.parameters->'targets') target
				WHERE source.id = :originalJobId
				""").bind("newJobId", newJobId).bind("originalJobId", original.id())
				.fetch().rowsUpdated()
				.flatMap(rows -> rows == original.totalCount()
						? Mono.empty()
						: Mono.error(new IllegalStateException(
								"Source retry targets do not match the original item count")));
	}

	private Mono<Void> insertRetryOutbox(
			UUID jobId, UUID messageId, String type, String routingKey,
			String idempotencyKey, String traceId
	) {
		return databaseClient.sql("""
				INSERT INTO outbox_messages (
				    id, topic_name, routing_key, message_type, message_version,
				    aggregate_id, idempotency_key, payload, trace_id
				)
				SELECT :messageId, 'camel.arxiv.jobs.v1', :routingKey, :type, 1,
				       id, :outboxKey,
				       jsonb_build_object(
				         'version', 1, 'messageId', :messageId, 'type', type,
				         'jobId', id, 'idempotencyKey', idempotency_key,
				         'traceId', :traceId, 'occurredAt', now(), 'payload', parameters),
				       :traceId
				FROM jobs WHERE id = :jobId
				""").bind("messageId", messageId).bind("routingKey", routingKey)
				.bind("type", type).bind("outboxKey", "command:" + idempotencyKey)
				.bind("traceId", traceId).bind("jobId", jobId)
				.fetch().rowsUpdated()
				.flatMap(rows -> rows == 1 ? Mono.empty()
						: Mono.error(new IllegalStateException("Retry outbox could not be created")));
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

	public Mono<Void> cancelOpenItems(UUID jobId) {
		return databaseClient.sql("""
				UPDATE job_items SET
				  status = 'CANCELED', completed_at = coalesce(completed_at, now())
				WHERE job_id = :jobId AND status IN ('PENDING','RUNNING')
				""").bind("jobId", jobId).fetch().rowsUpdated()
				.then(synchronizeItemCounters(jobId));
	}

	private Mono<Void> synchronizeItemCounters(UUID jobId) {
		return databaseClient.sql("""
				WITH totals AS (
				  SELECT count(*) AS total,
				         count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
				         count(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
				         count(*) FILTER (WHERE status = 'FAILED') AS failed
				  FROM job_items WHERE job_id = :jobId
				)
				UPDATE jobs job SET
				  processed_count = totals.succeeded + totals.skipped + totals.failed,
				  success_count = totals.succeeded,
				  skipped_count = totals.skipped,
				  failed_count = totals.failed,
				  total_count = totals.total,
				  progress_percent = CASE WHEN totals.total = 0 THEN 0
				    ELSE (totals.succeeded + totals.skipped + totals.failed) * 100.0 / totals.total END
				FROM totals WHERE job.id = :jobId
				""").bind("jobId", jobId).fetch().rowsUpdated().then();
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
				       greatest(j.heartbeat_at, wh.last_seen_at) AS heartbeat_at,
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

	private String routingKey(String type) {
		return switch (type) {
			case "ARXIV_IMPORT_METADATA" -> "arxiv.import.metadata";
			case "ARXIV_SYNC_OAI" -> "arxiv.sync.oai";
			case "ARXIV_SYNC_TAXONOMY" -> "arxiv.sync.taxonomy";
			case "ARXIV_FETCH_AND_PARSE_SOURCE" -> "arxiv.source.extract";
			case "ARXIV_REEXTRACT_CONTACTS" -> "arxiv.source.reextract";
			default -> throw new IllegalArgumentException("Job type cannot be retried");
		};
	}

	private boolean isExtractionJob(String type) {
		return "ARXIV_FETCH_AND_PARSE_SOURCE".equals(type)
				|| "ARXIV_REEXTRACT_CONTACTS".equals(type);
	}

	private String safeTrace(String traceId) {
		return traceId != null && traceId.matches("[A-Za-z0-9_-]{8,64}")
				? traceId : UUID.randomUUID().toString().replace("-", "");
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
