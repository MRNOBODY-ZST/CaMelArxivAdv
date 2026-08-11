package com.camel_hub.advertisement.arxiv.importing;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

public class ArxivImportRepository {

	private final DatabaseClient databaseClient;

	public ArxivImportRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Mono<ArxivImportService.JobSubmission> createOrFind(CommandRecord command) {
		return insertJob(command)
				.flatMap(created -> appendCreatedEvent(command.jobId(), command.type())
						.then(insertOutbox(command))
						.thenReturn(created))
				.switchIfEmpty(find(command.idempotencyKey())
						.repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(20)).take(5)));
	}

	private Mono<ArxivImportService.JobSubmission> insertJob(CommandRecord command) {
		return databaseClient.sql("""
				INSERT INTO jobs (
				    id, type, status, created_by, parameters, idempotency_key,
				    total_count, current_stage
				)
				VALUES (
				    :id, :type, 'PENDING', :actorId, CAST(:parameters AS jsonb), :idempotencyKey,
				    :totalCount, 'WAITING_FOR_WORKER'
				)
				ON CONFLICT (idempotency_key) DO NOTHING
				RETURNING id, status
				""").bind("id", command.jobId()).bind("type", command.type())
				.bind("actorId", command.actorId()).bind("parameters", command.parametersJson())
				.bind("idempotencyKey", command.idempotencyKey()).bind("totalCount", command.totalCount())
				.map((row, metadata) -> new ArxivImportService.JobSubmission(
						row.get("id", UUID.class), row.get("status", String.class), true,
						command.idempotencyKey()))
				.one();
	}

	private Mono<ArxivImportService.JobSubmission> find(String idempotencyKey) {
		return databaseClient.sql("""
				SELECT id, status FROM jobs
				WHERE idempotency_key = :idempotencyKey AND type LIKE 'ARXIV_%'
				""").bind("idempotencyKey", idempotencyKey)
				.map((row, metadata) -> new ArxivImportService.JobSubmission(
						row.get("id", UUID.class), row.get("status", String.class), false, idempotencyKey))
				.one();
	}

	private Mono<Void> appendCreatedEvent(UUID jobId, String type) {
		return databaseClient.sql("""
				INSERT INTO job_events (job_id, event_type, stage, message, details)
				VALUES (:jobId, 'JOB_CREATED', 'WAITING_FOR_WORKER',
				        'arXiv job created', jsonb_build_object('type', :type))
				""").bind("jobId", jobId).bind("type", type).fetch().rowsUpdated().then();
	}

	private Mono<Void> insertOutbox(CommandRecord command) {
		return databaseClient.sql("""
				INSERT INTO outbox_messages (
				    id, topic_name, routing_key, message_type, message_version,
				    aggregate_id, idempotency_key, payload, trace_id
				)
				VALUES (
				    :messageId, 'camel.arxiv.jobs.v1', :routingKey, :type, 1,
				    :jobId, :outboxKey, CAST(:payload AS jsonb), :traceId
				)
				""").bind("messageId", command.messageId()).bind("routingKey", command.routingKey())
				.bind("type", command.type()).bind("jobId", command.jobId())
				.bind("outboxKey", "command:" + command.idempotencyKey())
				.bind("payload", command.envelopeJson()).bind("traceId", command.traceId())
				.fetch().rowsUpdated().then();
	}

	public record CommandRecord(
			UUID jobId, UUID messageId, UUID actorId, String type, String routingKey,
			String idempotencyKey, String traceId, String parametersJson,
			String envelopeJson, long totalCount
	) {
	}
}
