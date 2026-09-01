package com.camel_hub.advertisement.arxiv.extraction;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SourceExtractionService {

	private static final String TYPE = "ARXIV_FETCH_AND_PARSE_SOURCE";
	private static final int MAXIMUM_BATCH = 100;
	private static final int MAXIMUM_METADATA_AUTHORS_BYTES = 256 * 1024;
	private static final int MAXIMUM_COMMAND_ENVELOPE_BYTES = 768 * 1024;

	private final SourceExtractionRepository repository;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final ObjectMapper objectMapper;
	private final String parserVersion;
	private final TransactionalOperator transactions;

	public SourceExtractionService(
			SourceExtractionRepository repository,
			AuditService auditService,
			SensitiveValueHasher hasher,
			ObjectMapper objectMapper,
			String parserVersion,
			TransactionalOperator transactions
	) {
		if (parserVersion == null || !parserVersion.matches("[A-Za-z0-9._-]{1,50}")) {
			throw new IllegalArgumentException("Source parser version is invalid");
		}
		this.repository = repository;
		this.auditService = auditService;
		this.hasher = hasher;
		this.objectMapper = objectMapper;
		this.parserVersion = parserVersion;
		this.transactions = transactions;
	}

	public Mono<JobSubmission> create(
			UUID actorId,
			List<UUID> paperIds,
			AuthenticationRequestContext context
	) {
		List<UUID> validated = validate(paperIds);
		return repository.lockPapers(validated).collectList()
				.flatMap(targets -> targets.size() == validated.size()
						? repository.hasActiveExtraction(validated)
								.flatMap(active -> active
										? Mono.error(new SourceExtractionConflictException(
												"A selected paper already has a nonterminal extraction job"))
										: createLocked(actorId, targets, context))
						: Mono.error(new SourceExtractionNotFoundException()))
				.as(transactions::transactional);
	}

	private Mono<JobSubmission> createLocked(
			UUID actorId,
			List<SourceExtractionRepository.PaperTarget> targets,
			AuthenticationRequestContext context
	) {
		SourceExtractionRepository.Command command = command(actorId, targets, context);
		return repository.create(command)
				.flatMap(job -> auditService.record(new AuditEvent(
						actorId, "PAPER_EXTRACTION_CREATED", "JOB", job.jobId().toString(),
						hasher.hash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
						Map.of(), Map.of("paperCount", targets.size(), "status", job.status()),
						AuditResult.SUCCESS, null)).thenReturn(job));
	}

	private SourceExtractionRepository.Command command(
			UUID actorId,
			List<SourceExtractionRepository.PaperTarget> targets,
			AuthenticationRequestContext context
	) {
		UUID jobId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		String idempotencyKey = "source:" + jobId;
		List<Map<String, Object>> payloadTargets = payloadTargets(targets);
		Map<String, Object> payload = Map.of(
				"targets", payloadTargets, "parserVersion", parserVersion);
		Map<String, Object> envelope = Map.of(
				"version", 1,
				"messageId", messageId,
				"type", TYPE,
				"jobId", jobId,
				"idempotencyKey", idempotencyKey,
				"traceId", safeTrace(context.traceId()),
				"occurredAt", Instant.now(),
				"payload", payload);
		String envelopeJson = json(envelope);
		if (utf8Length(envelopeJson) > MAXIMUM_COMMAND_ENVELOPE_BYTES) {
			throw new IllegalArgumentException("Source extraction command exceeds messaging size limit");
		}
		return new SourceExtractionRepository.Command(
				jobId, messageId, actorId, TYPE, idempotencyKey, safeTrace(context.traceId()),
				json(payload), envelopeJson, targets);
	}

	private List<Map<String, Object>> payloadTargets(
			List<SourceExtractionRepository.PaperTarget> targets
	) {
		int remainingAuthorBytes = MAXIMUM_METADATA_AUTHORS_BYTES;
		List<Map<String, Object>> payloadTargets = new ArrayList<>(targets.size());
		for (SourceExtractionRepository.PaperTarget target : targets) {
			List<String> authors = List.of();
			if (!target.authorNames().isEmpty()) {
				int authorBytes = utf8Length(json(target.authorNames()));
				if (authorBytes <= remainingAuthorBytes) {
					authors = target.authorNames();
					remainingAuthorBytes -= authorBytes;
				}
			}
			payloadTargets.add(Map.of(
					"paperId", target.paperId(),
					"arxivId", target.arxivId(),
					"metadataAuthors", authors));
		}
		return List.copyOf(payloadTargets);
	}

	private List<UUID> validate(List<UUID> paperIds) {
		if (paperIds == null || paperIds.isEmpty() || paperIds.size() > MAXIMUM_BATCH) {
			throw new SourceExtractionValidationException(
					"Source extraction requires between one and 100 papers");
		}
		if (paperIds.stream().anyMatch(java.util.Objects::isNull)) {
			throw new SourceExtractionValidationException("Source extraction paper IDs are invalid");
		}
		LinkedHashSet<UUID> unique = new LinkedHashSet<>(paperIds);
		if (unique.size() != paperIds.size()) {
			throw new SourceExtractionValidationException("Source extraction paper IDs must be unique");
		}
		return unique.stream().sorted().toList();
	}

	private String safeTrace(String traceId) {
		return traceId != null && traceId.matches("[A-Za-z0-9_-]{8,64}")
				? traceId : UUID.randomUUID().toString().replace("-", "");
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Source extraction command could not be serialized", exception);
		}
	}

	private int utf8Length(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	public record JobSubmission(UUID jobId, String status) { }
}
