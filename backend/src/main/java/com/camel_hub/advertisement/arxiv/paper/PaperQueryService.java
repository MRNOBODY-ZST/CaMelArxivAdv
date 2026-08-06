package com.camel_hub.advertisement.arxiv.paper;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class PaperQueryService {

	private static final Set<String> SOURCE_STATUSES = Set.of(
			"UNKNOWN", "AVAILABLE", "UNAVAILABLE", "DOWNLOADED", "SECURITY_REJECTED",
			"PARSED", "PARTIALLY_PARSED", "PARSE_FAILED");
	private final PaperQueryRepository repository;

	public PaperQueryService(PaperQueryRepository repository) {
		this.repository = repository;
	}

	public Mono<PageResponse<PaperSummary>> list(int page, int pageSize, PaperFilter input) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			return Mono.error(new IllegalArgumentException("Paper page is invalid"));
		}
		PaperFilter filter = normalize(input);
		int offset = Math.multiplyExact(page - 1, pageSize);
		return Mono.zip(
				repository.list(filter, offset, pageSize).map(this::summary).collectList(),
				repository.count(filter))
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<PaperDetail> get(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new PaperNotFoundException()))
				.flatMap(base -> Mono.zip(
						repository.authors(id).collectList(), repository.categories(id).collectList(),
						repository.versions(id).collectList(), repository.imports(id).collectList(),
						repository.extractionRuns(id).collectList())
						.map(tuple -> new PaperDetail(
								base.id(), base.arxivId(), base.title(), base.abstractText(), base.primaryCategory(),
								base.submittedAt(), base.updatedAt(), base.doi(), base.journalReference(),
								base.comment(), base.licenseUrl(), base.pdfUrl(), base.sourceStatus(),
								base.sourceFormat(), base.versionCount(),
								tuple.getT1().stream().map(row -> new AuthorView(
										row.order(), row.name(), row.corresponding(), row.affiliations())).toList(),
								tuple.getT2().stream().map(row -> new CategoryView(
										row.categoryId(), row.categoryName(), row.relationType())).toList(),
								tuple.getT3().stream().map(row -> new VersionView(
										row.version(), row.submittedAt(), row.sizeBytes(), row.sourceFormat())).toList(),
								tuple.getT4().stream().map(row -> new ImportView(
										row.jobId(), row.metadataSource(), row.sourceDatestamp(), row.importedAt())).toList(),
								tuple.getT5().stream().map(row -> new ExtractionRunView(
										row.id(), row.jobId(), row.parserVersion(), row.status(),
										row.documentClass(), row.sourceFormat(), row.filesInspected(),
										row.contactsFound(), row.durationMs(), row.archiveSizeBytes(),
										row.extractedSizeBytes(), row.cleanupConfirmed(), row.startedAt(),
										row.completedAt(), row.errorCode(), row.errorSummary())).toList(),
								sanitize(base.rawMetadata()), base.metadataSourceUpdatedAt(), base.importedAt())));
	}

	private PaperFilter normalize(PaperFilter input) {
		if (input == null) {
			return new PaperFilter(null, null, null, null, null, null, null, null, null, null,
					SortBy.UPDATED_AT, SortOrder.DESCENDING);
		}
		String category = text(input.category(), 80, "category");
		String title = text(input.title(), 200, "title");
		String author = text(input.author(), 200, "author");
		String source = text(input.sourceStatus(), 30, "source status");
		if (source != null) {
			source = source.toUpperCase(Locale.ROOT);
			if (!SOURCE_STATUSES.contains(source)) {
				throw new IllegalArgumentException("Paper source status is invalid");
			}
		}
		validateRange(input.submittedFrom(), input.submittedTo());
		validateRange(input.updatedFrom(), input.updatedTo());
		return new PaperFilter(category, input.submittedFrom(), input.submittedTo(),
				input.updatedFrom(), input.updatedTo(), title, author, source,
				input.hasDoi(), input.hasJournalReference(),
				input.sortBy() == null ? SortBy.UPDATED_AT : input.sortBy(),
				input.sortOrder() == null ? SortOrder.DESCENDING : input.sortOrder());
	}

	private String text(String value, int maximum, String name) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.strip();
		if (normalized.length() > maximum || normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Paper " + name + " filter is invalid");
		}
		return normalized;
	}

	private void validateRange(Instant from, Instant to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalArgumentException("Paper date range is invalid");
		}
	}

	private JsonNode sanitize(JsonNode value) {
		JsonNode copy = value.deepCopy();
		if (copy instanceof ObjectNode object) {
			List<String> sensitive = new java.util.ArrayList<>();
			object.fieldNames().forEachRemaining(name -> {
				String lower = name.toLowerCase(Locale.ROOT);
				if (lower.contains("email") || lower.contains("token") || lower.contains("secret")) {
					sensitive.add(name);
				}
			});
			sensitive.forEach(object::remove);
		}
		return copy;
	}

	private PaperSummary summary(PaperQueryRepository.PaperSummaryRow row) {
		return new PaperSummary(
				row.id(), row.arxivId(), row.title(), row.primaryCategory(), row.authors(),
				row.submittedAt(), row.updatedAt(), row.doi(), row.journalReference(),
				row.sourceStatus(), row.versionCount());
	}

	public enum SortBy { SUBMITTED_AT, UPDATED_AT, TITLE }
	public enum SortOrder { ASCENDING, DESCENDING }

	public record PaperFilter(
			String category, Instant submittedFrom, Instant submittedTo,
			Instant updatedFrom, Instant updatedTo, String title, String author,
			String sourceStatus, Boolean hasDoi, Boolean hasJournalReference,
			SortBy sortBy, SortOrder sortOrder
	) { }

	public record PaperSummary(
			UUID id, String arxivId, String title, String primaryCategory, List<String> authors,
			Instant submittedAt, Instant updatedAt, String doi, String journalReference,
			String sourceStatus, int versionCount
	) { }

	public record PaperDetail(
			UUID id, String arxivId, String title, String abstractText, String primaryCategory,
			Instant submittedAt, Instant updatedAt, String doi, String journalReference,
			String comment, String licenseUrl, String pdfUrl, String sourceStatus, String sourceFormat,
			int versionCount, List<AuthorView> authors, List<CategoryView> categories,
			List<VersionView> versions, List<ImportView> imports,
			List<ExtractionRunView> extractionRuns, JsonNode rawMetadata,
			Instant metadataSourceUpdatedAt, Instant importedAt
	) { }

	public record AuthorView(int order, String name, boolean corresponding, List<String> affiliations) { }
	public record CategoryView(String categoryId, String categoryName, String relationType) { }
	public record VersionView(int version, Instant submittedAt, Long sizeBytes, String sourceFormat) { }
	public record ImportView(UUID jobId, String metadataSource, Instant sourceDatestamp, Instant importedAt) { }
	public record ExtractionRunView(
			UUID id, UUID jobId, String parserVersion, String status, String documentClass,
			String sourceFormat, int filesInspected, int contactsFound, Long durationMs,
			Long archiveSizeBytes, Long extractedSizeBytes, boolean cleanupConfirmed,
			Instant startedAt, Instant completedAt, String errorCode, String errorSummary
	) { }
}
