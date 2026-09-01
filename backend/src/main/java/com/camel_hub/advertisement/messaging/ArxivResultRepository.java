package com.camel_hub.advertisement.messaging;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public class ArxivResultRepository {

	private final DatabaseClient databaseClient;

	public ArxivResultRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Mono<Boolean> markProcessed(UUID messageId, String idempotencyKey) {
		return databaseClient.sql("""
				INSERT INTO processed_messages (message_id, consumer_name, idempotency_key, result)
				VALUES (:messageId, 'spring-arxiv-results-v1', :idempotencyKey, 'SUCCEEDED')
				ON CONFLICT DO NOTHING
				""").bind("messageId", messageId).bind("idempotencyKey", idempotencyKey)
				.fetch().rowsUpdated().map(rows -> rows == 1);
	}

	public Mono<JobRecord> findJob(UUID jobId) {
		return databaseClient.sql("""
				SELECT id, type, status FROM jobs
				WHERE id = :jobId AND type LIKE 'ARXIV_%'
				FOR UPDATE
				""").bind("jobId", jobId)
				.map((row, metadata) -> new JobRecord(
						row.get("id", UUID.class), row.get("type", String.class),
						row.get("status", String.class))).one();
	}

	public Mono<Void> upsertHeartbeat(ArxivResultMessage.Payload payload, Instant occurredAt) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO worker_heartbeats (
				  worker_id, worker_type, version, status, current_job_id, last_seen_at, details
				) VALUES (
				  :workerId, :workerType, :version, :status,
				  (SELECT id FROM jobs WHERE id = :currentJobId), :occurredAt, '{}'::jsonb
				)
				ON CONFLICT (worker_id) DO UPDATE SET
				  worker_type = EXCLUDED.worker_type,
				  version = EXCLUDED.version,
				  status = EXCLUDED.status,
				  current_job_id = EXCLUDED.current_job_id,
				  last_seen_at = EXCLUDED.last_seen_at
				WHERE worker_heartbeats.last_seen_at <= EXCLUDED.last_seen_at
				""").bind("workerId", payload.workerId()).bind("workerType", payload.workerType())
				.bind("version", payload.version()).bind("status", payload.status())
				.bind("occurredAt", occurredAt);
		statement = payload.currentJobId() == null
				? statement.bindNull("currentJobId", UUID.class)
				: statement.bind("currentJobId", payload.currentJobId());
		return statement.fetch().rowsUpdated().then();
	}

	public Mono<Void> applyStarted(UUID jobId, ArxivResultMessage.Payload payload) {
		return databaseClient.sql("""
				UPDATE jobs SET
				  status = CASE WHEN status IN ('PENDING','QUEUED','RUNNING') THEN 'RUNNING' ELSE status END,
				  started_at = coalesce(started_at, now()), heartbeat_at = now(), last_message_at = now(),
				  current_stage = :stage, updated_at = now(), version = version + 1
				WHERE id = :jobId AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED')
				""").bind("jobId", jobId).bind("stage", safeStage(payload.stage()))
				.fetch().rowsUpdated().then();
	}

	public Mono<Void> applyProgress(UUID jobId, ArxivResultMessage.Payload payload, String checkpointJson) {
		return databaseClient.sql("""
				UPDATE jobs SET
				  status = CASE
				    WHEN :requestedStatus = 'PAUSED' AND status IN ('PENDING','QUEUED','RUNNING','PAUSED') THEN 'PAUSED'
				    WHEN :requestedStatus = 'RUNNING' AND status IN ('PENDING','QUEUED','RUNNING') THEN 'RUNNING'
				    ELSE status END,
				  started_at = coalesce(started_at, now()), heartbeat_at = now(), last_message_at = now(),
				  current_stage = :stage,
				  processed_count = GREATEST(processed_count, :processed),
				  success_count = GREATEST(success_count, :success),
				  skipped_count = GREATEST(skipped_count, :skipped),
				  failed_count = GREATEST(failed_count, :failed),
				  total_count = GREATEST(total_count, :total),
				  progress_percent = GREATEST(progress_percent, :progress),
				  checkpoint = CASE WHEN CAST(:checkpoint AS jsonb) = '{}'::jsonb
				                    THEN checkpoint ELSE CAST(:checkpoint AS jsonb) END,
				  error_summary = coalesce(nullif(:errorSummary, ''), error_summary),
				  updated_at = now(), version = version + 1
				WHERE id = :jobId AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED')
				""").bind("requestedStatus", safeStatus(payload.status()))
				.bind("stage", safeStage(payload.stage()))
				.bind("processed", nonNegative(payload.processedCount()))
				.bind("success", nonNegative(payload.successCount()))
				.bind("skipped", nonNegative(payload.skippedCount()))
				.bind("failed", nonNegative(payload.failedCount()))
				.bind("total", nonNegative(payload.totalCount()))
				.bind("progress", boundedProgress(payload.progressPercent()))
				.bind("checkpoint", checkpointJson).bind("jobId", jobId)
				.bind("errorSummary", valueOrEmpty(safeError(payload.errorSummary())))
				.fetch().rowsUpdated().then();
	}

	public Mono<Boolean> applyTerminal(UUID jobId, ArxivResultMessage.Payload payload) {
		String status = terminalStatus(payload.status());
		return databaseClient.sql("""
				UPDATE jobs SET
				  status = :status,
				  current_stage = :stage,
				  processed_count = GREATEST(processed_count, :processed),
				  success_count = GREATEST(success_count, :success),
				  skipped_count = GREATEST(skipped_count, :skipped),
				  failed_count = GREATEST(failed_count, :failed),
				  total_count = GREATEST(total_count, :total),
				  progress_percent = CASE WHEN :status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED') THEN 100
				                          ELSE GREATEST(progress_percent, :progress) END,
				  error_summary = nullif(:errorSummary, ''), heartbeat_at = now(), last_message_at = now(),
				  ended_at = coalesce(ended_at, now()), updated_at = now(), version = version + 1
				WHERE id = :jobId
				  AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED')
				""").bind("status", status).bind("stage", safeStage(payload.stage()))
				.bind("processed", nonNegative(payload.processedCount()))
				.bind("success", nonNegative(payload.successCount()))
				.bind("skipped", nonNegative(payload.skippedCount()))
				.bind("failed", nonNegative(payload.failedCount()))
				.bind("total", nonNegative(payload.totalCount()))
				.bind("progress", boundedProgress(payload.progressPercent()))
				.bind("errorSummary", valueOrEmpty(safeError(payload.errorSummary()))).bind("jobId", jobId)
				.fetch().rowsUpdated().map(rows -> rows == 1);
	}

	public Mono<Boolean> applySourceTerminalExact(
			UUID jobId,
			ArxivResultMessage.Payload payload
	) {
		String status = terminalStatus(payload.status());
		return databaseClient.sql("""
				UPDATE jobs SET
				  status = :status, current_stage = :stage,
				  processed_count = :processed, success_count = :success,
				  skipped_count = :skipped, failed_count = :failed, total_count = :total,
				  progress_percent = :progress,
				  error_summary = nullif(:errorSummary, ''),
				  heartbeat_at = now(), last_message_at = now(),
				  ended_at = coalesce(ended_at, now()), updated_at = now(), version = version + 1
				WHERE id = :jobId
				  AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED')
				""").bind("status", status).bind("stage", safeStage(payload.stage()))
				.bind("processed", nonNegative(payload.processedCount()))
				.bind("success", nonNegative(payload.successCount()))
				.bind("skipped", nonNegative(payload.skippedCount()))
				.bind("failed", nonNegative(payload.failedCount()))
				.bind("total", nonNegative(payload.totalCount()))
				.bind("progress", boundedProgress(payload.progressPercent()))
				.bind("errorSummary", valueOrEmpty(safeError(payload.errorSummary())))
				.bind("jobId", jobId).fetch().rowsUpdated().map(rows -> rows == 1);
	}

	public Mono<Void> applySourceWaitingExact(
			UUID jobId,
			ArxivResultMessage.Payload payload
	) {
		return databaseClient.sql("""
				UPDATE jobs SET
				  current_stage = :stage,
				  processed_count = :processed, success_count = :success,
				  skipped_count = :skipped, failed_count = :failed, total_count = :total,
				  progress_percent = :progress,
				  heartbeat_at = now(), last_message_at = now(),
				  updated_at = now(), version = version + 1
				WHERE id = :jobId
				  AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED')
				""").bind("stage", safeStage(payload.stage()))
				.bind("processed", nonNegative(payload.processedCount()))
				.bind("success", nonNegative(payload.successCount()))
				.bind("skipped", nonNegative(payload.skippedCount()))
				.bind("failed", nonNegative(payload.failedCount()))
				.bind("total", nonNegative(payload.totalCount()))
				.bind("progress", boundedProgress(payload.progressPercent()))
				.bind("jobId", jobId).fetch().rowsUpdated().then();
	}

	public Mono<Boolean> applySourceCanceledExact(
			UUID jobId,
			ArxivResultMessage.Payload payload
	) {
		return databaseClient.sql("""
				UPDATE jobs SET
				  status = 'CANCELED',
				  current_stage = CASE WHEN status = 'CANCELED' THEN current_stage ELSE :stage END,
				  processed_count = :processed, success_count = :success,
				  skipped_count = :skipped, failed_count = :failed, total_count = :total,
				  progress_percent = :progress,
				  error_summary = CASE WHEN status = 'CANCELED' THEN error_summary
				                       ELSE nullif(:errorSummary, '') END,
				  heartbeat_at = now(), last_message_at = now(),
				  ended_at = coalesce(ended_at, now()), updated_at = now(), version = version + 1
				WHERE id = :jobId
				  AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED')
				""").bind("stage", safeStage(payload.stage()))
				.bind("processed", nonNegative(payload.processedCount()))
				.bind("success", nonNegative(payload.successCount()))
				.bind("skipped", nonNegative(payload.skippedCount()))
				.bind("failed", nonNegative(payload.failedCount()))
				.bind("total", nonNegative(payload.totalCount()))
				.bind("progress", boundedProgress(payload.progressPercent()))
				.bind("errorSummary", valueOrEmpty(safeError(payload.errorSummary())))
				.bind("jobId", jobId).fetch().rowsUpdated().map(rows -> rows == 1);
	}

	public Mono<Boolean> hasDeferredSourceCompletion(UUID jobId) {
		return databaseClient.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM job_events
				  WHERE job_id = :jobId AND event_type = 'ARXIV_JOB_COMPLETION_DEFERRED'
				) AS deferred
				""").bind("jobId", jobId)
				.map((row, metadata) -> Boolean.TRUE.equals(row.get("deferred", Boolean.class))).one();
	}

	public Mono<Long> reconcileStaleDeferredSourceCompletions(
			Instant cutoff,
			Instant completedAt
	) {
		return databaseClient.sql("""
				WITH item_totals AS (
				  SELECT job_id, count(*) AS total,
				         count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
				         count(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
				         count(*) FILTER (WHERE status = 'FAILED') AS failed
				  FROM job_items
				  GROUP BY job_id
				), candidates AS (
				  SELECT j.id,
				         coalesce(totals.total, 0) AS total,
				         coalesce(totals.succeeded, 0) AS succeeded,
				         coalesce(totals.skipped, 0) AS skipped,
				         coalesce(totals.failed, 0) AS failed,
				         coalesce(totals.succeeded, 0) + coalesce(totals.skipped, 0)
				           + coalesce(totals.failed, 0) AS processed
				  FROM jobs j
				  LEFT JOIN item_totals totals ON totals.job_id = j.id
				  WHERE j.type IN ('ARXIV_FETCH_AND_PARSE_SOURCE', 'ARXIV_REEXTRACT_CONTACTS')
				    AND j.status IN ('PENDING','QUEUED','RUNNING','PAUSED')
				    AND coalesce(j.last_message_at, j.updated_at, j.created_at) < :cutoff
				    AND (coalesce(totals.total, 0) = 0
				      OR coalesce(totals.succeeded, 0) + coalesce(totals.skipped, 0)
				        + coalesce(totals.failed, 0) <> totals.total)
				    AND EXISTS (
				      SELECT 1 FROM job_events event
				      WHERE event.job_id = j.id
				        AND event.event_type = 'ARXIV_JOB_COMPLETION_DEFERRED'
				    )
				  ORDER BY coalesce(j.last_message_at, j.updated_at, j.created_at), j.id
				  FOR UPDATE OF j SKIP LOCKED
				  LIMIT 100
				), updated AS (
				  UPDATE jobs job SET
				    status = 'FAILED', current_stage = 'FAILED',
				    processed_count = candidate.processed,
				    success_count = candidate.succeeded,
				    skipped_count = candidate.skipped,
				    failed_count = candidate.failed,
				    total_count = candidate.total,
				    progress_percent = CASE WHEN candidate.total = 0 THEN 0
				      ELSE candidate.processed * 100.0 / candidate.total END,
				    error_summary = 'Source extraction completion timed out while waiting for item results',
				    heartbeat_at = :completedAt, last_message_at = :completedAt,
				    ended_at = :completedAt, updated_at = :completedAt, version = version + 1
				  FROM candidates candidate
				  WHERE job.id = candidate.id
				    AND job.status IN ('PENDING','QUEUED','RUNNING','PAUSED')
				    AND coalesce(job.last_message_at, job.updated_at, job.created_at) < :cutoff
				  RETURNING job.id, candidate.total, candidate.succeeded, candidate.skipped,
				            candidate.failed, candidate.processed
				), events AS (
				  INSERT INTO job_events (
				    job_id, event_type, stage, message, details, occurred_at
				  )
				  SELECT id, 'ARXIV_JOB_FAILED', 'FAILED',
				         'Source extraction completion timed out while waiting for item results',
				         jsonb_build_object(
				           'processedCount', processed, 'successCount', succeeded,
				           'skippedCount', skipped, 'failedCount', failed,
				           'totalCount', total,
				           'progressPercent', CASE WHEN total = 0 THEN 0
				             ELSE processed * 100.0 / total END,
				           'errorCode', 'SOURCE_RESULTS_INCOMPLETE'),
				         :completedAt
				  FROM updated
				  RETURNING id
				)
				SELECT count(*) AS total FROM events
				""").bind("cutoff", cutoff).bind("completedAt", completedAt)
				.map((row, metadata) -> value(row.get("total", Long.class))).one();
	}

	public Mono<Void> cancelOpenExtractionItems(UUID jobId) {
		return databaseClient.sql("""
				UPDATE job_items SET status = 'CANCELED', completed_at = coalesce(completed_at, now())
				WHERE job_id = :jobId AND status IN ('PENDING','RUNNING')
				""").bind("jobId", jobId).fetch().rowsUpdated().then();
	}

	public Mono<ItemTotals> extractionItemTotals(UUID jobId) {
		return databaseClient.sql("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
				       count(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
				       count(*) FILTER (WHERE status = 'FAILED') AS failed,
				       count(*) FILTER (WHERE status IN ('PENDING','RUNNING')) AS pending
				FROM job_items WHERE job_id = :jobId
				""").bind("jobId", jobId).map((row, metadata) -> new ItemTotals(
						value(row.get("total", Long.class)),
						value(row.get("succeeded", Long.class)),
						value(row.get("skipped", Long.class)),
						value(row.get("failed", Long.class)),
						value(row.get("pending", Long.class)))).one();
	}

	public Mono<Void> upsertSyncCursor(UUID jobId, String checkpointJson) {
		return databaseClient.sql("""
				INSERT INTO arxiv_sync_cursors (
				  cursor_key, sync_type, set_spec, from_datestamp, resumption_token,
				  token_received_at, last_response_date, last_job_id
				)
				SELECT 'oai:job:' || id, 'OAI_METADATA', parameters->>'setSpec',
				       CAST(NULLIF(parameters->>'from', '') AS date),
				       NULLIF(CAST(:checkpoint AS jsonb)->>'resumptionToken', ''),
				       CASE WHEN NULLIF(CAST(:checkpoint AS jsonb)->>'resumptionToken', '') IS NULL
				            THEN NULL ELSE now() END,
				       CAST(CAST(:checkpoint AS jsonb)->>'responseDate' AS timestamptz), id
				FROM jobs WHERE id = :jobId AND type = 'ARXIV_SYNC_OAI'
				ON CONFLICT (cursor_key) DO UPDATE SET
				  resumption_token = EXCLUDED.resumption_token,
				  token_received_at = EXCLUDED.token_received_at,
				  last_response_date = EXCLUDED.last_response_date,
				  last_job_id = EXCLUDED.last_job_id,
				  version = arxiv_sync_cursors.version + 1, updated_at = now()
				""").bind("checkpoint", checkpointJson).bind("jobId", jobId)
				.fetch().rowsUpdated().then();
	}

	public Mono<Void> markSyncComplete(UUID jobId) {
		return databaseClient.sql("""
				UPDATE arxiv_sync_cursors SET
				  resumption_token = NULL, token_received_at = NULL,
				  last_completed_datestamp = coalesce(last_response_date::date, current_date),
				  version = version + 1, updated_at = now()
				WHERE last_job_id = :jobId
				""").bind("jobId", jobId).fetch().rowsUpdated().then();
	}

	public Mono<Void> appendEvent(UUID jobId, String type, ArxivResultMessage.Payload payload, String details) {
		return databaseClient.sql("""
				INSERT INTO job_events (job_id, event_type, stage, message, details)
				VALUES (:jobId, :eventType, :stage, :message, CAST(:details AS jsonb))
				""").bind("jobId", jobId).bind("eventType", type)
				.bind("stage", safeStage(payload.stage()))
				.bind("message", eventMessage(type)).bind("details", details)
				.fetch().rowsUpdated().then();
	}

	private String safeStatus(String value) {
		return value == null || !java.util.Set.of("RUNNING", "PAUSED").contains(value)
				? "RUNNING" : value;
	}

	private String terminalStatus(String value) {
		return value != null && java.util.Set.of(
				"SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELED").contains(value)
				? value : "FAILED";
	}

	private String safeStage(String value) {
		if (value == null || value.isBlank()) {
			return "UNKNOWN";
		}
		String safe = value.replaceAll("[^A-Za-z0-9_-]", "_");
		return safe.substring(0, Math.min(80, safe.length()));
	}

	private String safeError(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String safe = value.replaceAll("[\\p{Cntrl}]", " ").strip();
		return safe.substring(0, Math.min(500, safe.length()));
	}

	private String valueOrEmpty(String value) {
		return value == null ? "" : value;
	}

	private long nonNegative(long value) {
		return Math.max(0, value);
	}

	private double boundedProgress(double value) {
		return Math.max(0, Math.min(100, value));
	}

	private long value(Long value) {
		return value == null ? 0 : value;
	}

	private String eventMessage(String type) {
		return switch (type) {
			case "ARXIV_JOB_STARTED" -> "Worker started the arXiv job";
			case "ARXIV_JOB_BATCH" -> "Worker returned an arXiv metadata batch";
			case "ARXIV_EXTRACTION_RESULT" -> "Worker returned a Source extraction result";
			case "ARXIV_JOB_PROGRESS" -> "Worker reported arXiv job progress";
			case "ARXIV_JOB_COMPLETED" -> "Worker completed the arXiv job";
			case "ARXIV_JOB_COMPLETION_DEFERRED" ->
					"Worker completed the arXiv job before every item result arrived";
			case "ARXIV_JOB_FAILED" -> "Worker reported an arXiv job failure";
			default -> "Worker reported an arXiv job event";
		};
	}

	public record JobRecord(UUID id, String type, String status) {
	}

	public record ItemTotals(long total, long succeeded, long skipped, long failed, long pending) {
		public long processed() {
			return succeeded + skipped + failed;
		}
	}
}
