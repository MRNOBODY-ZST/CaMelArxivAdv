package com.camel_hub.advertisement.arxiv.importing;

import com.camel_hub.advertisement.arxiv.search.ArxivQueryNormalizer;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

public class ArxivImportService {

	private static final Pattern ARXIV_ID = Pattern.compile(
			"^(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})(?:v[0-9]+)?$");
	private static final Pattern VERSION_SUFFIX = Pattern.compile("v[0-9]+$");
	private static final Pattern TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
	private final ArxivImportRepository repository;
	private final ArxivQueryNormalizer normalizer;
	private final ArxivImportCatalog catalog;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final ObjectMapper objectMapper;
	private final int maximumPapers;
	private final TransactionalOperator transactions;

	public ArxivImportService(
			ArxivImportRepository repository,
			ArxivQueryNormalizer normalizer,
			ArxivImportCatalog catalog,
			AuditService auditService,
			SensitiveValueHasher hasher,
			ObjectMapper objectMapper,
			int maximumPapers,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.normalizer = normalizer;
		this.catalog = catalog;
		this.auditService = auditService;
		this.hasher = hasher;
		this.objectMapper = objectMapper.copy()
				.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
				.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		this.maximumPapers = maximumPapers;
		this.transactions = transactions;
	}

	public Mono<JobSubmission> createImport(
			UUID actorId, ImportCommand command, AuthenticationRequestContext context
	) {
		return Mono.defer(() -> {
			List<String> selected = normalizeIds(command.arxivIds());
			boolean hasSelected = !selected.isEmpty();
			boolean hasCriteria = command.criteria() != null;
			if (hasSelected == hasCriteria) {
				return Mono.error(new ArxivImportValidationException(
						"Provide either selected arXiv IDs or search criteria"));
			}
			if (hasSelected) {
				if (selected.size() > maximumPapers) {
					return Mono.error(new ArxivImportValidationException("Selected import exceeds the configured ceiling"));
				}
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("mode", "SELECTED");
				payload.put("arxivIds", selected);
				payload.put("maxPapers", selected.size());
				return submit(actorId, "ARXIV_IMPORT_METADATA", "arxiv.import.metadata",
						payload, selected.size(), context);
			}
			if (command.maxPapers() == null) {
				return Mono.error(new ArxivImportValidationException(
						"A bounded import ceiling is required for criteria imports"));
			}
			validateCeiling(command.maxPapers());
			return catalog.activeIdentifiers()
					.map(active -> active.stream().filter(value -> value.indexOf(':') < 0)
							.collect(java.util.stream.Collectors.toUnmodifiableSet()))
					.map(active -> normalizer.normalize(command.criteria(), active))
					.flatMap(query -> {
						Map<String, Object> payload = new LinkedHashMap<>();
						payload.put("mode", "CRITERIA");
						payload.put("criteria", query.criteria());
						payload.put("criteriaHash", query.queryHash());
						payload.put("maxPapers", command.maxPapers());
						return submit(actorId, "ARXIV_IMPORT_METADATA", "arxiv.import.metadata",
								payload, command.maxPapers(), context);
					});
		}).onErrorMap(IllegalArgumentException.class,
				exception -> new ArxivImportValidationException(exception.getMessage()));
	}

	public Mono<JobSubmission> createOaiSync(
			UUID actorId, OaiSyncCommand command, AuthenticationRequestContext context
	) {
		return Mono.defer(() -> {
			String setSpec = normalizeSet(command.setSpec());
			if (command.from() != null && command.from().isAfter(LocalDate.now())) {
				return Mono.error(new ArxivImportValidationException("OAI from date cannot be in the future"));
			}
			return catalog.activeIdentifiers().flatMap(identifiers -> {
				if (!identifiers.contains(setSpec)) {
					return Mono.error(new ArxivImportValidationException("OAI set is inactive or unknown"));
				}
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("setSpec", setSpec);
				if (command.from() != null) {
					payload.put("from", command.from());
				}
				return submit(actorId, "ARXIV_SYNC_OAI", "arxiv.sync.oai", payload, 0, context);
			});
		});
	}

	private Mono<JobSubmission> submit(
			UUID actorId, String type, String routingKey, Map<String, Object> payload,
			long totalCount, AuthenticationRequestContext context
	) {
		String parametersJson = json(payload);
		String idempotencyKey = "arxiv:" + type.toLowerCase(java.util.Locale.ROOT) + ":"
				+ sha256(actorId + ":" + type + ":" + parametersJson);
		UUID jobId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		String traceId = safeTrace(context.traceId());
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("version", 1);
		envelope.put("messageId", messageId);
		envelope.put("type", type);
		envelope.put("jobId", jobId);
		envelope.put("idempotencyKey", idempotencyKey);
		envelope.put("traceId", traceId);
		envelope.put("occurredAt", Instant.now());
		envelope.put("payload", payload);
		var record = new ArxivImportRepository.CommandRecord(
				jobId, messageId, actorId, type, routingKey, idempotencyKey, traceId,
				parametersJson, json(envelope), totalCount);
		return repository.createOrFind(record)
				.switchIfEmpty(Mono.error(new IllegalStateException("Idempotent job could not be resolved")))
				.flatMap(result -> audit(result, actorId, type, context).thenReturn(result))
				.as(transactions::transactional);
	}

	private Mono<Void> audit(
			JobSubmission result, UUID actorId, String type, AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				actorId, type + "_REQUESTED", "JOB", result.jobId().toString(),
				hasher.hash(context.ipAddress()), context.userAgentSummary(), context.traceId(), Map.of(),
				Map.of("type", type, "created", result.created()), AuditResult.SUCCESS, null));
	}

	private List<String> normalizeIds(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		TreeSet<String> normalized = new TreeSet<>();
		for (String raw : ids) {
			String value = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
			if (!ARXIV_ID.matcher(value).matches()) {
				throw new ArxivImportValidationException("arXiv ID is invalid");
			}
			normalized.add(VERSION_SUFFIX.matcher(value).replaceFirst(""));
		}
		return List.copyOf(normalized);
	}

	private String normalizeSet(String raw) {
		String value = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
		if (!value.matches("^[A-Za-z0-9.-]{1,60}(?::[A-Za-z0-9.-]{1,60}){0,2}$")) {
			throw new ArxivImportValidationException("OAI set is invalid");
		}
		return value;
	}

	private void validateCeiling(int ceiling) {
		if (ceiling < 1 || ceiling > maximumPapers) {
			throw new ArxivImportValidationException(
					"Import ceiling must be between one and " + maximumPapers);
		}
	}

	private String safeTrace(String traceId) {
		return traceId != null && TRACE_ID.matcher(traceId).matches()
				? traceId : sha256(String.valueOf(traceId)).substring(0, 16);
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("arXiv command could not be serialized", exception);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	public record ImportCommand(List<String> arxivIds, ArxivSearchCriteria criteria, Integer maxPapers) {
	}

	public record OaiSyncCommand(String setSpec, LocalDate from) {
	}

	public record JobSubmission(UUID jobId, String status, boolean created, String idempotencyKey) {
	}
}
