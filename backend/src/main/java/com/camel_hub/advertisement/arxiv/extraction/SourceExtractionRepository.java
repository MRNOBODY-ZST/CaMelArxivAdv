package com.camel_hub.advertisement.arxiv.extraction;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class SourceExtractionRepository {

	private final DatabaseClient databaseClient;

	public SourceExtractionRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<PaperTarget> lockPapers(List<UUID> paperIds) {
		return databaseClient.sql("""
				SELECT p.id, p.arxiv_id, coalesce(a.author_names, ARRAY[]::text[]) AS author_names
				FROM papers p
				LEFT JOIN LATERAL (
				  SELECT array_agg(pa.raw_name ORDER BY pa.author_order) AS author_names
				  FROM paper_authors pa WHERE pa.paper_id = p.id
				) a ON true
				WHERE p.id = ANY(:paperIds) AND p.deleted_at IS NULL
				ORDER BY p.id
				FOR UPDATE OF p
				""").bind("paperIds", paperIds.toArray(UUID[]::new))
				.map((row, metadata) -> new PaperTarget(
						row.get("id", UUID.class), row.get("arxiv_id", String.class),
						java.util.Arrays.asList(row.get("author_names", String[].class))))
				.all();
	}

	public Mono<Boolean> hasActiveExtraction(List<UUID> paperIds) {
		String[] externalKeys = paperIds.stream().map(UUID::toString).toArray(String[]::new);
		return databaseClient.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM job_items ji JOIN jobs j ON j.id = ji.job_id
				  WHERE ji.external_key = ANY(:externalKeys)
				    AND j.type IN ('ARXIV_FETCH_AND_PARSE_SOURCE', 'ARXIV_REEXTRACT_CONTACTS')
				    AND j.status NOT IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELED')
				) AS active
				""").bind("externalKeys", externalKeys)
				.map((row, metadata) -> Boolean.TRUE.equals(row.get("active", Boolean.class))).one();
	}

	public Mono<SourceExtractionService.JobSubmission> create(Command command) {
		return insertJob(command)
				.flatMap(created -> insertItems(command).then(appendEvent(command)).then(insertOutbox(command))
						.thenReturn(created))
				.switchIfEmpty(find(command.idempotencyKey())
						.repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(20)).take(5)));
	}

	private Mono<SourceExtractionService.JobSubmission> insertJob(Command command) {
		return databaseClient.sql("""
				INSERT INTO jobs (
				  id, type, status, created_by, parameters, idempotency_key,
				  total_count, current_stage)
				VALUES (
				  :jobId, :type, 'PENDING', :actorId, CAST(:parameters AS jsonb),
				  :idempotencyKey, :totalCount, 'WAITING_FOR_WORKER')
				ON CONFLICT (idempotency_key) DO NOTHING
				RETURNING id, status
				""").bind("jobId", command.jobId()).bind("type", command.type())
				.bind("actorId", command.actorId()).bind("parameters", command.parametersJson())
				.bind("idempotencyKey", command.idempotencyKey())
				.bind("totalCount", command.targets().size())
				.map((row, metadata) -> new SourceExtractionService.JobSubmission(
						row.get("id", UUID.class), row.get("status", String.class))).one();
	}

	private Mono<Void> insertItems(Command command) {
		return Flux.fromIterable(command.targets()).concatMap(target -> databaseClient.sql("""
				INSERT INTO job_items (job_id, external_key, status)
				VALUES (:jobId, :externalKey, 'PENDING')
				""").bind("jobId", command.jobId()).bind("externalKey", target.paperId().toString())
				.fetch().rowsUpdated()).then();
	}

	private Mono<Void> appendEvent(Command command) {
		return databaseClient.sql("""
				INSERT INTO job_events (job_id, event_type, stage, message, details)
				VALUES (:jobId, 'JOB_CREATED', 'WAITING_FOR_WORKER',
				        'Source extraction job created',
				        jsonb_build_object('paperCount', :paperCount))
				""").bind("jobId", command.jobId()).bind("paperCount", command.targets().size())
				.fetch().rowsUpdated().then();
	}

	private Mono<Void> insertOutbox(Command command) {
		return databaseClient.sql("""
				INSERT INTO outbox_messages (
				  id, topic_name, routing_key, message_type, message_version,
				  aggregate_id, idempotency_key, payload, trace_id)
				VALUES (
				  :messageId, 'camel.arxiv.jobs.v1', 'arxiv.source.extract', :type, 1,
				  :jobId, :outboxKey, CAST(:envelope AS jsonb), :traceId)
				""").bind("messageId", command.messageId()).bind("type", command.type())
				.bind("jobId", command.jobId()).bind("outboxKey", "command:" + command.idempotencyKey())
				.bind("envelope", command.envelopeJson()).bind("traceId", command.traceId())
				.fetch().rowsUpdated().then();
	}

	private Mono<SourceExtractionService.JobSubmission> find(String idempotencyKey) {
		return databaseClient.sql("""
				SELECT id, status FROM jobs WHERE idempotency_key = :idempotencyKey
				""").bind("idempotencyKey", idempotencyKey)
				.map((row, metadata) -> new SourceExtractionService.JobSubmission(
						row.get("id", UUID.class), row.get("status", String.class))).one();
	}

	public record PaperTarget(UUID paperId, String arxivId, List<String> authorNames) {
		public PaperTarget {
			authorNames = List.copyOf(authorNames);
		}
	}

	public record Command(
			UUID jobId,
			UUID messageId,
			UUID actorId,
			String type,
			String idempotencyKey,
			String traceId,
			String parametersJson,
			String envelopeJson,
			List<PaperTarget> targets
	) { }
}
