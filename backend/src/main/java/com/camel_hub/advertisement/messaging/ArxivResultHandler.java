package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.arxiv.paper.PaperRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

public class ArxivResultHandler {

	private static final Set<String> TYPES = Set.of(
			"ARXIV_JOB_STARTED", "ARXIV_JOB_PROGRESS", "ARXIV_JOB_BATCH",
			"ARXIV_JOB_COMPLETED", "ARXIV_JOB_FAILED");
	private final ArxivResultRepository repository;
	private final PaperRepository papers;
	private final ObjectMapper objectMapper;
	private final TransactionalOperator transactions;

	public ArxivResultHandler(
			ArxivResultRepository repository,
			PaperRepository papers,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.papers = papers;
		this.objectMapper = objectMapper.copy()
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		this.transactions = transactions;
	}

	public Mono<HandleResult> handle(String body) {
		return Mono.fromCallable(() -> parse(body))
				.flatMap(message -> repository.markProcessed(
						message.messageId(), processedKey(message.idempotencyKey()))
						.flatMap(inserted -> inserted ? apply(message) : Mono.just(new HandleResult(true))))
				.as(transactions::transactional);
	}

	private Mono<HandleResult> apply(ArxivResultMessage message) {
		return repository.findJob(message.jobId())
				.switchIfEmpty(Mono.error(new IllegalArgumentException("Result job does not exist")))
				.flatMap(job -> applyForJob(message, job).thenReturn(new HandleResult(false)));
	}

	private Mono<Void> applyForJob(
			ArxivResultMessage message, ArxivResultRepository.JobRecord job
	) {
		ArxivResultMessage.Payload payload = message.payload();
		String details = details(payload);
		Mono<Void> mutation = switch (message.type()) {
			case "ARXIV_JOB_STARTED" -> repository.applyStarted(job.id(), payload);
			case "ARXIV_JOB_BATCH" -> papers.upsertBatch(
					job.id(), safePapers(payload.papers()),
					"ARXIV_SYNC_OAI".equals(job.type()) ? "OAI_PMH" : "LEGACY_API");
			case "ARXIV_JOB_PROGRESS" -> repository.applyProgress(
					job.id(), payload, checkpoint(payload.checkpoint()));
			case "ARXIV_JOB_COMPLETED", "ARXIV_JOB_FAILED" -> repository.applyTerminal(job.id(), payload);
			default -> Mono.error(new IllegalArgumentException("Result message type is unsupported"));
		};
		return mutation.then(repository.appendEvent(job.id(), message.type(), payload, details));
	}

	private ArxivResultMessage parse(String body) {
		if (body == null || body.isBlank() || body.length() > 5 * 1024 * 1024) {
			throw new IllegalArgumentException("Result message size is invalid");
		}
		try {
			ArxivResultMessage message = objectMapper.readValue(body, ArxivResultMessage.class);
			if (message.version() != 1 || message.messageId() == null || message.jobId() == null
					|| message.idempotencyKey() == null || message.idempotencyKey().isBlank()
					|| message.idempotencyKey().length() > 200 || message.traceId() == null
					|| !message.traceId().matches("[A-Za-z0-9_-]{8,64}")
					|| message.occurredAt() == null || !TYPES.contains(message.type())
					|| message.payload() == null) {
				throw new IllegalArgumentException("Result message contract is invalid");
			}
			validatePayload(message.payload());
			return message;
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Result message is malformed", exception);
		}
	}

	private void validatePayload(ArxivResultMessage.Payload payload) {
		if (payload.stage() == null || payload.stage().isBlank() || payload.stage().length() > 80
				|| payload.processedCount() < 0 || payload.successCount() < 0
				|| payload.failedCount() < 0 || payload.totalCount() < 0
				|| payload.progressPercent() < 0 || payload.progressPercent() > 100
				|| (payload.papers() != null && payload.papers().size() > 100)
				|| (payload.errorSummary() != null && payload.errorSummary().length() > 500)) {
			throw new IllegalArgumentException("Result payload is invalid");
		}
	}

	private List<ArxivResultMessage.Paper> safePapers(List<ArxivResultMessage.Paper> value) {
		return value == null ? List.of() : List.copyOf(value);
	}

	private String checkpoint(JsonNode checkpoint) {
		return checkpoint == null || checkpoint.isNull() ? "{}" : checkpoint.toString();
	}

	private String details(ArxivResultMessage.Payload payload) {
		try {
			return objectMapper.writeValueAsString(java.util.Map.of(
					"processedCount", payload.processedCount(),
					"successCount", payload.successCount(),
					"failedCount", payload.failedCount(),
					"totalCount", payload.totalCount(),
					"progressPercent", payload.progressPercent()));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Result details could not be serialized", exception);
		}
	}

	private String processedKey(String key) {
		if (key.length() <= 160) {
			return key;
		}
		return key.substring(0, 95) + ":" + Integer.toHexString(key.hashCode());
	}

	public record HandleResult(boolean duplicate) {
	}
}
