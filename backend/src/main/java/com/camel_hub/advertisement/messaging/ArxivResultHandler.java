package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.arxiv.paper.PaperRepository;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyCategory;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomySnapshot;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomySnapshotLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ArxivResultHandler {

	private static final Set<String> TYPES = Set.of(
			"ARXIV_JOB_STARTED", "ARXIV_JOB_PROGRESS", "ARXIV_JOB_BATCH",
			"ARXIV_JOB_COMPLETED", "ARXIV_JOB_FAILED",
			"WORKER_HEARTBEAT");
	private final ArxivResultRepository repository;
	private final PaperRepository papers;
	private final TaxonomyRepository taxonomy;
	private final ObjectMapper objectMapper;
	private final TransactionalOperator transactions;
	private final List<TaxonomyCategory> baselineCategories;

	public ArxivResultHandler(
			ArxivResultRepository repository,
			PaperRepository papers,
			TaxonomyRepository taxonomy,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		this(repository, papers, taxonomy, new TaxonomySnapshotLoader(objectMapper),
				objectMapper, transactions);
	}

	public ArxivResultHandler(
			ArxivResultRepository repository,
			PaperRepository papers,
			TaxonomyRepository taxonomy,
			TaxonomySnapshotLoader snapshotLoader,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.papers = papers;
		this.taxonomy = taxonomy;
		this.objectMapper = objectMapper.copy()
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		this.transactions = transactions;
		this.baselineCategories = snapshotLoader.loadDefault().categories();
	}

	public Mono<HandleResult> handle(String body) {
		return Mono.fromCallable(() -> parse(body))
				.flatMap(message -> repository.markProcessed(
						message.messageId(), processedKey(message.idempotencyKey()))
						.flatMap(inserted -> inserted ? apply(message) : Mono.just(new HandleResult(true))))
				.as(transactions::transactional);
	}

	private Mono<HandleResult> apply(ArxivResultMessage message) {
		if ("WORKER_HEARTBEAT".equals(message.type())) {
			return repository.upsertHeartbeat(message.payload(), message.occurredAt())
					.thenReturn(new HandleResult(false));
		}
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
			case "ARXIV_JOB_PROGRESS" -> "ARXIV_SYNC_OAI".equals(job.type())
					? applyOaiProgress(job.id(), payload)
					: repository.applyProgress(job.id(), payload, checkpoint(payload.checkpoint()));
			case "ARXIV_JOB_COMPLETED" -> "ARXIV_SYNC_TAXONOMY".equals(job.type())
					? applyTaxonomySnapshot(message, job)
						.then(repository.applyTerminal(job.id(), payload))
					: repository.applyTerminal(job.id(), payload)
						.then("ARXIV_SYNC_OAI".equals(job.type())
								? repository.markSyncComplete(job.id()) : Mono.empty());
			case "ARXIV_JOB_FAILED" -> repository.applyTerminal(job.id(), payload)
					.then("ARXIV_SYNC_OAI".equals(job.type())
							? repository.markSyncComplete(job.id()) : Mono.empty());
			default -> Mono.error(new IllegalArgumentException("Result message type is unsupported"));
		};
		return mutation.then(repository.appendEvent(job.id(), message.type(), payload, details));
	}

	private Mono<Void> applyOaiProgress(UUID jobId, ArxivResultMessage.Payload payload) {
		String checkpointJson = checkpoint(payload.checkpoint());
		validateOaiCheckpoint(payload.checkpoint());
		return repository.applyProgress(jobId, payload, checkpointJson)
				.then(repository.upsertSyncCursor(jobId, checkpointJson));
	}

	private void validateOaiCheckpoint(JsonNode value) {
		if (value == null || value.isNull() || value.isEmpty()) {
			return;
		}
		JsonNode token = value.get("resumptionToken");
		JsonNode responseDate = value.get("responseDate");
		if (token == null || !token.isTextual() || token.textValue().length() > 4000
				|| token.textValue().chars().anyMatch(Character::isISOControl)
				|| responseDate == null || !responseDate.isTextual()) {
			throw new IllegalArgumentException("OAI checkpoint is invalid");
		}
		try {
			java.time.Instant.parse(responseDate.textValue());
		}
		catch (java.time.format.DateTimeParseException exception) {
			throw new IllegalArgumentException("OAI checkpoint response date is invalid", exception);
		}
	}

	private Mono<Void> applyTaxonomySnapshot(
			ArxivResultMessage message, ArxivResultRepository.JobRecord job
	) {
		if (!"ARXIV_SYNC_TAXONOMY".equals(job.type())) {
			return Mono.error(new IllegalArgumentException("Taxonomy snapshot job type is invalid"));
		}
		ArxivResultMessage.Payload payload = message.payload();
		validateTaxonomyPayload(payload);
		if (!"SUCCEEDED".equals(payload.status()) || payload.progressPercent() != 100) {
			return Mono.error(new IllegalArgumentException("Taxonomy snapshot must be terminal"));
		}
		List<TaxonomyCategory> categories = payload.taxonomyCategories().stream()
				.map(value -> new TaxonomyCategory(
						value.groupId(), value.groupName(), value.archiveId(), value.archiveName(),
						value.categoryId(), value.categoryName(), value.description(),
						value.alias(), value.aliasTarget()))
				.sorted(java.util.Comparator.comparing(TaxonomyCategory::categoryId))
				.toList();
		categories = mergeBaselineMetadata(categories);
		return taxonomy.mergeWithActiveMetadata(categories)
				.flatMap(merged -> taxonomy.applySnapshot(new TaxonomySnapshot(
						payload.snapshotVersion(), "OAI_LIST_SETS",
						List.of("https://oaipmh.arxiv.org/oai"), payload.taxonomySourceUpdatedAt(),
						message.occurredAt(), sha256(merged), merged)))
				.then();
	}

	private List<TaxonomyCategory> mergeBaselineMetadata(List<TaxonomyCategory> incoming) {
		java.util.Map<String, TaxonomyCategory> baseline = new java.util.LinkedHashMap<>();
		baselineCategories.forEach(category -> baseline.put(category.categoryId(), category));
		java.util.Map<String, TaxonomyCategory> merged = new java.util.LinkedHashMap<>();
		for (TaxonomyCategory category : incoming) {
			TaxonomyCategory metadata = baseline.get(category.categoryId());
			merged.put(category.categoryId(), metadata == null ? category : new TaxonomyCategory(
					category.groupId(), metadata.groupName(), category.archiveId(), metadata.archiveName(),
					category.categoryId(), category.categoryName(), metadata.description(),
					metadata.alias(), metadata.aliasTarget()));
		}
		baselineCategories.stream()
				.filter(TaxonomyCategory::alias)
				.filter(alias -> !merged.containsKey(alias.categoryId()))
				.filter(alias -> merged.containsKey(alias.aliasTarget()))
				.forEach(alias -> merged.put(alias.categoryId(), alias));
		return merged.values().stream()
				.sorted(java.util.Comparator.comparing(TaxonomyCategory::categoryId)).toList();
	}

	private ArxivResultMessage parse(String body) {
		if (body == null || body.isBlank() || body.length() > 5 * 1024 * 1024) {
			throw new IllegalArgumentException("Result message size is invalid");
		}
		try {
			ArxivResultMessage message = objectMapper.readValue(body, ArxivResultMessage.class);
			if (message.version() != 1 || message.messageId() == null
					|| message.idempotencyKey() == null || message.idempotencyKey().isBlank()
					|| message.idempotencyKey().length() > 200 || message.traceId() == null
					|| !message.traceId().matches("[A-Za-z0-9_-]{8,64}")
					|| message.occurredAt() == null || !TYPES.contains(message.type())
					|| message.payload() == null) {
				throw new IllegalArgumentException("Result message contract is invalid");
			}
			if ("WORKER_HEARTBEAT".equals(message.type())) {
				if (message.jobId() != null) {
					throw new IllegalArgumentException("Heartbeat must not declare an envelope job");
				}
				validateHeartbeat(message.payload());
			}
			else {
				if (message.jobId() == null) {
					throw new IllegalArgumentException("Result message job is required");
				}
				validatePayload(message.payload());
			}
			return message;
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Result message is malformed", exception);
		}
	}

	private void validateHeartbeat(ArxivResultMessage.Payload payload) {
		if (payload.workerId() == null
				|| !payload.workerId().matches("[A-Za-z0-9._:-]{1,120}")
				|| payload.workerType() == null
				|| !Set.of("ARXIV", "MAIL").contains(payload.workerType())
				|| payload.version() == null || payload.version().isBlank()
				|| payload.version().length() > 50
				|| payload.version().chars().anyMatch(Character::isISOControl)
				|| payload.status() == null
				|| !Set.of("IDLE", "BUSY", "DRAINING", "UNHEALTHY").contains(payload.status())) {
			throw new IllegalArgumentException("Heartbeat payload is invalid");
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
		if (payload.taxonomyCategories() != null && payload.taxonomyCategories().size() > 500) {
			throw new IllegalArgumentException("Taxonomy snapshot is too large");
		}
	}

	private void validateTaxonomyPayload(ArxivResultMessage.Payload payload) {
		if (payload.snapshotVersion() == null
				|| !payload.snapshotVersion().matches("[A-Za-z0-9._:-]{1,80}")
				|| payload.taxonomySourceUpdatedAt() == null
				|| payload.taxonomyCategories() == null || payload.taxonomyCategories().isEmpty()) {
			throw new IllegalArgumentException("Taxonomy snapshot payload is invalid");
		}
		Set<String> identifiers = new java.util.HashSet<>();
		for (ArxivResultMessage.TaxonomyCategory category : payload.taxonomyCategories()) {
			if (category.groupId() == null || !category.groupId().matches("[A-Za-z0-9.-]{1,40}")
					|| invalidText(category.groupName(), 120)
					|| category.archiveId() == null || !category.archiveId().matches("[A-Za-z0-9.-]{1,40}")
					|| invalidText(category.archiveName(), 160)
					|| category.categoryId() == null || !category.categoryId().matches("[A-Za-z0-9.-]{1,80}")
					|| invalidText(category.categoryName(), 200)
					|| category.description() == null || category.description().length() > 20_000
					|| containsUnsafeControl(category.description())
					|| !identifiers.add(category.categoryId())) {
				throw new IllegalArgumentException("Taxonomy category payload is invalid");
			}
		}
		for (ArxivResultMessage.TaxonomyCategory category : payload.taxonomyCategories()) {
			if (category.alias()
					? category.aliasTarget() == null || !identifiers.contains(category.aliasTarget())
					: category.aliasTarget() != null) {
				throw new IllegalArgumentException("Taxonomy alias payload is invalid");
			}
		}
	}

	private boolean invalidText(String value, int maximumLength) {
		return value == null || value.isBlank() || value.length() > maximumLength
				|| containsUnsafeControl(value);
	}

	private boolean containsUnsafeControl(String value) {
		return value.chars().anyMatch(character -> Character.isISOControl(character)
				&& !Character.isWhitespace(character));
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

	private String sha256(Object value) {
		try {
			byte[] canonical = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
		}
		catch (JsonProcessingException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Taxonomy snapshot could not be hashed", exception);
		}
	}

	public record HandleResult(boolean duplicate) {
	}
}
