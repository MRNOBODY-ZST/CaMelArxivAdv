package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionResultRepository;
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
			"ARXIV_EXTRACTION_RESULT",
			"ARXIV_JOB_COMPLETED", "ARXIV_JOB_FAILED",
			"WORKER_HEARTBEAT");
	private final ArxivResultRepository repository;
	private final PaperRepository papers;
	private final TaxonomyRepository taxonomy;
	private final SourceExtractionResultRepository extractions;
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
				null, objectMapper, transactions);
	}

	public ArxivResultHandler(
			ArxivResultRepository repository,
			PaperRepository papers,
			TaxonomyRepository taxonomy,
			SourceExtractionResultRepository extractions,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		this(repository, papers, taxonomy, new TaxonomySnapshotLoader(objectMapper),
				extractions, objectMapper, transactions);
	}

	public ArxivResultHandler(
			ArxivResultRepository repository,
			PaperRepository papers,
			TaxonomyRepository taxonomy,
			TaxonomySnapshotLoader snapshotLoader,
			SourceExtractionResultRepository extractions,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.papers = papers;
		this.taxonomy = taxonomy;
		this.extractions = extractions;
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
		if (Set.of("ARXIV_JOB_COMPLETED", "ARXIV_JOB_FAILED").contains(message.type())) {
			if (isTerminalStatus(job.status())) {
				return isCanceledExtractionCompletion(message, job)
						? applyCanceledSourceCompletion(message, job, false) : Mono.empty();
			}
			return applyTerminalForJob(message, job, details);
		}
		if (isTerminalStatus(job.status())) {
			return Mono.empty();
		}
		Mono<Void> mutation = switch (message.type()) {
			case "ARXIV_JOB_STARTED" -> repository.applyStarted(job.id(), payload);
			case "ARXIV_JOB_BATCH" -> papers.upsertBatch(
					job.id(), safePapers(payload.papers()),
					"ARXIV_SYNC_OAI".equals(job.type()) ? "OAI_PMH" : "LEGACY_API");
			case "ARXIV_EXTRACTION_RESULT" -> applyExtraction(message, job)
					.then(repository.applyProgress(
							job.id(), payload, checkpoint(payload.checkpoint())));
			case "ARXIV_JOB_PROGRESS" -> applyProgressForJob(job, payload);
			default -> Mono.error(new IllegalArgumentException("Result message type is unsupported"));
		};
		Mono<Void> persisted = mutation.then(
				repository.appendEvent(job.id(), message.type(), payload, details));
		return "ARXIV_EXTRACTION_RESULT".equals(message.type())
				? persisted.then(completeDeferredSourceJob(message, job)) : persisted;
	}

	private Mono<Void> applyTerminalForJob(
			ArxivResultMessage message,
			ArxivResultRepository.JobRecord job,
			String details
	) {
		ArxivResultMessage.Payload payload = message.payload();
		if (isExtractionJob(job.type())) {
			return "CANCELED".equals(payload.status())
					? applyCanceledSourceCompletion(message, job, true)
					: applySourceTerminalForJob(message, job);
		}
		Mono<Void> preparation = Mono.empty();
		if ("ARXIV_JOB_COMPLETED".equals(message.type())) {
			if ("ARXIV_SYNC_TAXONOMY".equals(job.type())) {
				preparation = applyTaxonomySnapshot(message, job);
			}
		}
		return preparation.then(repository.applyTerminal(job.id(), payload))
				.flatMap(applied -> {
					if (!applied) {
						return Mono.empty();
					}
					Mono<Void> completion = "ARXIV_SYNC_OAI".equals(job.type())
							&& "ARXIV_JOB_COMPLETED".equals(message.type())
							&& Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED").contains(payload.status())
							? repository.markSyncComplete(job.id()) : Mono.empty();
					return completion.then(repository.appendEvent(
							job.id(), message.type(), payload, details));
				});
	}

	private Mono<Void> applySourceTerminalForJob(
			ArxivResultMessage message,
			ArxivResultRepository.JobRecord job
	) {
		return repository.extractionItemTotals(job.id()).flatMap(totals -> {
			boolean completed = "ARXIV_JOB_COMPLETED".equals(message.type());
			boolean incomplete = completed
					&& (totals.total() == 0 || totals.processed() != totals.total());
			if (incomplete) {
				ArxivResultMessage.Payload waiting = sourceTerminalPayload(
						message.payload(), totals, "RUNNING", "AWAITING_ITEM_RESULTS", null, null);
				return repository.applySourceWaitingExact(job.id(), waiting)
						.then(repository.appendEvent(job.id(),
								"ARXIV_JOB_COMPLETION_DEFERRED", waiting, details(waiting)));
			}
			String status = completed ? sourceStatus(totals) : "FAILED";
			ArxivResultMessage.Payload authoritative = sourceTerminalPayload(
					message.payload(), totals, status, message.payload().stage(),
					message.payload().errorCode(), message.payload().errorSummary());
			return repository.applySourceTerminalExact(job.id(), authoritative)
					.flatMap(applied -> applied
							? repository.appendEvent(
									job.id(), message.type(), authoritative, details(authoritative))
							: Mono.empty());
		});
	}

	private Mono<Void> completeDeferredSourceJob(
			ArxivResultMessage message,
			ArxivResultRepository.JobRecord job
	) {
		if (!isExtractionJob(job.type())) {
			return Mono.empty();
		}
		return Mono.zip(
				repository.hasDeferredSourceCompletion(job.id()),
				repository.extractionItemTotals(job.id()))
				.flatMap(state -> {
					ArxivResultRepository.ItemTotals totals = state.getT2();
					if (!state.getT1()) {
						return Mono.empty();
					}
					if (totals.total() == 0 || totals.processed() != totals.total()) {
						ArxivResultMessage.Payload waiting = sourceTerminalPayload(
								message.payload(), totals, "RUNNING",
								"AWAITING_ITEM_RESULTS", null, null);
						return repository.applySourceWaitingExact(job.id(), waiting);
					}
					ArxivResultMessage.Payload authoritative = sourceTerminalPayload(
							message.payload(), totals, sourceStatus(totals), "COMPLETED", null, null);
					return repository.applySourceTerminalExact(job.id(), authoritative)
							.flatMap(applied -> applied
									? repository.appendEvent(job.id(), "ARXIV_JOB_COMPLETED",
											authoritative, details(authoritative))
									: Mono.empty());
				});
	}

	private Mono<Void> applyCanceledSourceCompletion(
			ArxivResultMessage message,
			ArxivResultRepository.JobRecord job,
			boolean appendEvent
	) {
		return repository.cancelOpenExtractionItems(job.id())
				.then(repository.extractionItemTotals(job.id()))
				.flatMap(totals -> {
					ArxivResultMessage.Payload authoritative = sourceTerminalPayload(
							message.payload(), totals, "CANCELED", message.payload().stage(),
							message.payload().errorCode(), message.payload().errorSummary());
					return repository.applySourceCanceledExact(job.id(), authoritative)
							.flatMap(applied -> applied && appendEvent
									? repository.appendEvent(job.id(), message.type(), authoritative,
											details(authoritative))
									: Mono.empty());
				});
	}

	private String sourceStatus(ArxivResultRepository.ItemTotals totals) {
		if (totals.failed() == 0) {
			return "SUCCEEDED";
		}
		return totals.succeeded() + totals.skipped() > 0
				? "PARTIALLY_SUCCEEDED" : "FAILED";
	}

	private ArxivResultMessage.Payload sourceTerminalPayload(
			ArxivResultMessage.Payload source,
			ArxivResultRepository.ItemTotals totals,
			String status,
			String stage,
			String errorCode,
			String errorSummary
	) {
		double progress = totals.total() == 0 ? 0
				: totals.processed() * 100.0 / totals.total();
		return new ArxivResultMessage.Payload(
				status, stage, totals.processed(), totals.succeeded(), totals.skipped(),
				totals.failed(), totals.total(), progress, source.checkpoint(), source.papers(),
				errorCode, errorSummary, source.workerId(), source.workerType(), source.version(),
				source.currentJobId(), source.snapshotVersion(), source.taxonomySourceUpdatedAt(),
				source.taxonomyCategories(), source.extractions());
	}

	private boolean isCanceledExtractionCompletion(
			ArxivResultMessage message,
			ArxivResultRepository.JobRecord job
	) {
		return "CANCELED".equals(job.status())
				&& "ARXIV_JOB_COMPLETED".equals(message.type())
				&& "CANCELED".equals(message.payload().status())
				&& isExtractionJob(job.type());
	}

	private boolean isTerminalStatus(String status) {
		return Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELED")
				.contains(status);
	}

	private boolean isExtractionJob(String jobType) {
		return Set.of("ARXIV_FETCH_AND_PARSE_SOURCE", "ARXIV_REEXTRACT_CONTACTS")
				.contains(jobType);
	}

	private Mono<Void> applyExtraction(
			ArxivResultMessage message,
			ArxivResultRepository.JobRecord job
	) {
		if (!Set.of("ARXIV_FETCH_AND_PARSE_SOURCE", "ARXIV_REEXTRACT_CONTACTS")
				.contains(job.type()) || extractions == null) {
			return Mono.error(new IllegalArgumentException("Extraction result job type is invalid"));
		}
		List<ArxivResultMessage.SourceExtraction> values = message.payload().extractions();
		if (values == null || values.size() != 1) {
			return Mono.error(new IllegalArgumentException("Extraction result batch is invalid"));
		}
		validateExtraction(values.getFirst());
		return extractions.apply(message, values.getFirst());
	}

	private Mono<Void> applyOaiProgress(UUID jobId, ArxivResultMessage.Payload payload) {
		String checkpointJson = checkpoint(payload.checkpoint());
		validateOaiCheckpoint(payload.checkpoint());
		Mono<Void> progress = repository.applyProgress(jobId, payload, checkpointJson);
		return payload.checkpoint() == null || payload.checkpoint().isNull()
				|| payload.checkpoint().isEmpty()
				? progress : progress.then(repository.upsertSyncCursor(jobId, checkpointJson));
	}

	private Mono<Void> applyProgressForJob(
			ArxivResultRepository.JobRecord job,
			ArxivResultMessage.Payload payload
	) {
		if ("ARXIV_SYNC_OAI".equals(job.type())) {
			return applyOaiProgress(job.id(), payload);
		}
		if (isExtractionJob(job.type())) {
			return repository.hasDeferredSourceCompletion(job.id())
					.flatMap(deferred -> deferred ? Mono.empty() : repository.applyProgress(
							job.id(), payload, checkpoint(payload.checkpoint())));
		}
		return repository.applyProgress(job.id(), payload, checkpoint(payload.checkpoint()));
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
				validateMessageStatus(message.type(), message.payload().status());
			}
			return message;
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Result message is malformed", exception);
		}
	}

	private void validateMessageStatus(String type, String status) {
		boolean valid = switch (type) {
			case "ARXIV_JOB_STARTED", "ARXIV_JOB_BATCH", "ARXIV_EXTRACTION_RESULT" ->
					"RUNNING".equals(status);
			case "ARXIV_JOB_PROGRESS" -> Set.of("RUNNING", "PAUSED").contains(status);
			case "ARXIV_JOB_COMPLETED" -> Set.of(
					"SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELED").contains(status);
			case "ARXIV_JOB_FAILED" -> "FAILED".equals(status);
			default -> false;
		};
		if (!valid) {
			throw new IllegalArgumentException("Result message type and status are inconsistent");
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
				|| payload.skippedCount() < 0 || payload.failedCount() < 0 || payload.totalCount() < 0
				|| payload.progressPercent() < 0 || payload.progressPercent() > 100
				|| (payload.papers() != null && payload.papers().size() > 100)
				|| (payload.errorCode() != null
						&& !payload.errorCode().matches("[A-Z0-9_]{1,80}"))
				|| (payload.errorSummary() != null && (payload.errorSummary().length() > 500
						|| containsEmailLikeText(payload.errorSummary())))) {
			throw new IllegalArgumentException("Result payload is invalid");
		}
		if (payload.taxonomyCategories() != null && payload.taxonomyCategories().size() > 500) {
			throw new IllegalArgumentException("Taxonomy snapshot is too large");
		}
		if (payload.extractions() != null && payload.extractions().size() > 10) {
			throw new IllegalArgumentException("Extraction result batch is too large");
		}
	}

	private void validateExtraction(ArxivResultMessage.SourceExtraction result) {
		if (result == null || result.paperId() == null || result.arxivId() == null
				|| !result.arxivId().matches("(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})")
				|| result.parserVersion() == null
				|| !result.parserVersion().matches("[A-Za-z0-9._-]{1,50}")
				|| !Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED",
						"SECURITY_REJECTED", "SOURCE_UNAVAILABLE").contains(result.status())
				|| !result.cleanupConfirmed() || result.archiveSizeBytes() < 0
				|| result.extractedSizeBytes() < 0 || result.filesInspected() < 0
				|| result.filesInspected() > 5000 || result.durationMs() < 0
				|| invalidOptionalText(result.documentClass(), 100)
				|| invalidOptionalText(result.sourceFormat(), 50)
				|| invalidOptionalText(result.errorSummary(), 500)
				|| containsEmailLikeText(result.documentClass())
				|| containsEmailLikeText(result.sourceFormat())
				|| containsEmailLikeText(result.errorSummary())) {
			throw new IllegalArgumentException("Extraction result contract is invalid");
		}
		boolean successful = Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED").contains(result.status());
		if (successful != (result.sourceFormat() != null && result.filesInspected() > 0
				&& result.errorCode() == null)
				|| (!successful && (result.errorCode() == null
				|| !result.errorCode().matches("[A-Z0-9_]{1,80}")))) {
			throw new IllegalArgumentException("Extraction result status is inconsistent");
		}
		List<ArxivResultMessage.SourceAuthor> authors = result.authors() == null
				? List.of() : result.authors();
		List<ArxivResultMessage.SourceContact> contacts = result.contacts() == null
				? List.of() : result.contacts();
		if (authors.size() > 500 || contacts.size() > 500
				|| (!successful && (!authors.isEmpty() || !contacts.isEmpty()))) {
			throw new IllegalArgumentException("Extraction result collections are invalid");
		}
		Set<Integer> orders = new java.util.HashSet<>();
		Set<String> normalizedNames = new java.util.HashSet<>();
		for (ArxivResultMessage.SourceAuthor author : authors) {
			if (author == null || author.order() < 1 || author.order() > 500
					|| !orders.add(author.order()) || invalidText(author.name(), 300)
					|| containsEmailLikeText(author.name())
					|| !normalizedNames.add(normalizedAuthorName(author.name()))
					|| author.affiliations() == null || author.affiliations().size() > 100
					|| author.affiliations().stream().anyMatch(value -> invalidText(value, 2000)
							|| containsEmailLikeText(value))) {
				throw new IllegalArgumentException("Extraction author is invalid");
			}
		}
		for (ArxivResultMessage.SourceContact contact : contacts) {
			validateContact(contact, orders);
		}
	}

	private String normalizedAuthorName(String value) {
		return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
				.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
	}

	private void validateContact(ArxivResultMessage.SourceContact contact, Set<Integer> orders) {
		if (contact == null || !validNormalizedEmail(contact.normalizedEmail(), contact.domain())
				|| invalidText(contact.displayEmail(), 320) || !contact.syntaxValid()
				|| (contact.authorOrder() != null && !orders.contains(contact.authorOrder()))
				|| !Set.of("HIGH", "MEDIUM", "LOW", "UNMAPPED").contains(contact.confidence())
				|| contact.evidence() == null || contact.evidence().isEmpty()
				|| contact.evidence().size() > 20) {
			throw new IllegalArgumentException("Extraction contact is invalid");
		}
		for (ArxivResultMessage.SourceEvidence evidence : contact.evidence()) {
			if (evidence == null || unsafePath(evidence.sourceRelativePath())
					|| evidence.ruleName() == null || !evidence.ruleName().matches("[A-Z0-9_]{1,120}")
					|| evidence.lineNumber() != null && evidence.lineNumber() < 1
					|| evidence.logicalLocation() == null
					|| !evidence.logicalLocation().matches("[A-Z0-9_]{1,120}")
					|| invalidText(evidence.maskedContext(), 600)
					|| containsEmailLikeText(evidence.maskedContext())
					|| evidence.maskedContext().toLowerCase(java.util.Locale.ROOT)
							.contains(contact.normalizedEmail().toLowerCase(java.util.Locale.ROOT))
					|| evidence.maskedContext().toLowerCase(java.util.Locale.ROOT)
							.contains(contact.displayEmail().toLowerCase(java.util.Locale.ROOT))) {
				throw new IllegalArgumentException("Extraction evidence is invalid");
			}
		}
	}

	private boolean validNormalizedEmail(String email, String domain) {
		if (email == null || domain == null || email.length() > 320 || domain.length() > 255
				|| !email.equals(email.toLowerCase(java.util.Locale.ROOT))
				|| !email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`|~-]{1,64}@[A-Za-z0-9.-]{3,255}")) {
			return false;
		}
		String local = email.substring(0, email.lastIndexOf('@'));
		if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
			return false;
		}
		String actualDomain = email.substring(email.lastIndexOf('@') + 1);
		return actualDomain.equals(domain) && domain.contains(".")
				&& java.util.Arrays.stream(domain.split("\\."))
						.allMatch(label -> label.matches(
								"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"));
	}

	private boolean unsafePath(String value) {
		if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")
				|| containsUnsafeControl(value) || containsEmailLikeText(value)) {
			return true;
		}
		return java.util.Arrays.stream(value.split("/", -1))
				.anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
	}

	private boolean containsEmailLikeText(String value) {
		return value != null && java.text.Normalizer.normalize(
				value, java.text.Normalizer.Form.NFKC).contains("@");
	}

	private boolean invalidOptionalText(String value, int maximumLength) {
		return value != null && (value.isBlank() || value.length() > maximumLength
				|| containsUnsafeControl(value));
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
			java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
			details.put("processedCount", payload.processedCount());
			details.put("successCount", payload.successCount());
			details.put("skippedCount", payload.skippedCount());
			details.put("failedCount", payload.failedCount());
			details.put("totalCount", payload.totalCount());
			details.put("progressPercent", payload.progressPercent());
			if (payload.errorCode() != null) {
				details.put("errorCode", payload.errorCode());
			}
			return objectMapper.writeValueAsString(details);
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
