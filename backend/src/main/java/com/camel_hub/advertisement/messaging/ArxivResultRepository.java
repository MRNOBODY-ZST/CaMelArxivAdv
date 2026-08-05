package com.camel_hub.advertisement.messaging;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

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
				""").bind("jobId", jobId)
				.map((row, metadata) -> new JobRecord(
						row.get("id", UUID.class), row.get("type", String.class),
						row.get("status", String.class))).one();
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
				.bind("failed", nonNegative(payload.failedCount()))
				.bind("total", nonNegative(payload.totalCount()))
				.bind("progress", boundedProgress(payload.progressPercent()))
				.bind("checkpoint", checkpointJson).bind("jobId", jobId)
				.bind("errorSummary", valueOrEmpty(safeError(payload.errorSummary())))
				.fetch().rowsUpdated().then();
	}

	public Mono<Void> applyTerminal(UUID jobId, ArxivResultMessage.Payload payload) {
		String status = terminalStatus(payload.status());
		return databaseClient.sql("""
				UPDATE jobs SET
				  status = CASE WHEN status = 'CANCELED' THEN status ELSE :status END,
				  current_stage = :stage,
				  processed_count = GREATEST(processed_count, :processed),
				  success_count = GREATEST(success_count, :success),
				  failed_count = GREATEST(failed_count, :failed),
				  total_count = GREATEST(total_count, :total),
				  progress_percent = CASE WHEN :status = 'SUCCEEDED' THEN 100
				                          ELSE GREATEST(progress_percent, :progress) END,
				  error_summary = nullif(:errorSummary, ''), heartbeat_at = now(), last_message_at = now(),
				  ended_at = coalesce(ended_at, now()), updated_at = now(), version = version + 1
				WHERE id = :jobId AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED')
				""").bind("status", status).bind("stage", safeStage(payload.stage()))
				.bind("processed", nonNegative(payload.processedCount()))
				.bind("success", nonNegative(payload.successCount()))
				.bind("failed", nonNegative(payload.failedCount()))
				.bind("total", nonNegative(payload.totalCount()))
				.bind("progress", boundedProgress(payload.progressPercent()))
				.bind("errorSummary", valueOrEmpty(safeError(payload.errorSummary()))).bind("jobId", jobId)
				.fetch().rowsUpdated().then();
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

	private String eventMessage(String type) {
		return switch (type) {
			case "ARXIV_JOB_STARTED" -> "Worker started the arXiv job";
			case "ARXIV_JOB_BATCH" -> "Worker returned an arXiv metadata batch";
			case "ARXIV_JOB_PROGRESS" -> "Worker reported arXiv job progress";
			case "ARXIV_JOB_COMPLETED" -> "Worker completed the arXiv job";
			case "ARXIV_JOB_FAILED" -> "Worker reported an arXiv job failure";
			default -> "Worker reported an arXiv job event";
		};
	}

	public record JobRecord(UUID id, String type, String status) {
	}
}
