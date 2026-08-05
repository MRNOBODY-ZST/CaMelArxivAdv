package com.camel_hub.advertisement.arxiv.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class PaperQueryRepository {

	private static final Instant EARLIEST = Instant.parse("1900-01-01T00:00:00Z");
	private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");
	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public PaperQueryRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Flux<PaperSummaryRow> list(PaperQueryService.PaperFilter filter, int offset, int limit) {
		String order = switch (filter.sortBy()) {
			case SUBMITTED_AT -> "p.submitted_at";
			case UPDATED_AT -> "p.updated_at";
			case TITLE -> "lower(p.title)";
		};
		String direction = filter.sortOrder() == PaperQueryService.SortOrder.ASCENDING ? "ASC" : "DESC";
		return bind(databaseClient.sql(summarySql() + filtersSql()
				+ " ORDER BY " + order + " " + direction + ", p.id " + direction
				+ " OFFSET :offset LIMIT :limit"), filter)
				.bind("offset", offset).bind("limit", limit).map(this::summary).all();
	}

	public Mono<Long> count(PaperQueryService.PaperFilter filter) {
		return bind(databaseClient.sql("SELECT count(*) AS total FROM papers p " + filtersSql()), filter)
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<PaperBaseRow> find(UUID id) {
		return databaseClient.sql("""
				SELECT p.id, p.arxiv_id, p.title, p.abstract_text, p.submitted_at, p.updated_at,
				       p.doi, p.journal_reference, p.comment_text, p.license_url, p.pdf_url,
				       p.source_status, p.source_format, p.version_count,
				       CAST(p.metadata_raw AS text) AS metadata_raw,
				       p.metadata_source_updated_at, p.imported_at, c.category_id AS primary_category
				FROM papers p LEFT JOIN arxiv_categories c ON c.id = p.primary_category_id
				WHERE p.id = :id AND p.deleted_at IS NULL
				""").bind("id", id).map((row, metadata) -> new PaperBaseRow(
						row.get("id", UUID.class), row.get("arxiv_id", String.class),
						row.get("title", String.class), row.get("abstract_text", String.class),
						row.get("primary_category", String.class), row.get("submitted_at", Instant.class),
						row.get("updated_at", Instant.class), row.get("doi", String.class),
						row.get("journal_reference", String.class), row.get("comment_text", String.class),
						row.get("license_url", String.class), row.get("pdf_url", String.class),
						row.get("source_status", String.class), row.get("source_format", String.class),
						row.get("version_count", Integer.class), jsonNode(row.get("metadata_raw", String.class)),
						row.get("metadata_source_updated_at", Instant.class), row.get("imported_at", Instant.class)))
				.one();
	}

	public Flux<AuthorRow> authors(UUID paperId) {
		return databaseClient.sql("""
				SELECT pa.author_order, pa.raw_name, CAST(pa.affiliation_data AS text) AS affiliations
				FROM paper_authors pa WHERE pa.paper_id = :paperId ORDER BY pa.author_order
				""").bind("paperId", paperId).map((row, metadata) -> new AuthorRow(
						row.get("author_order", Integer.class), row.get("raw_name", String.class),
						strings(row.get("affiliations", String.class)))).all();
	}

	public Flux<CategoryRow> categories(UUID paperId) {
		return databaseClient.sql("""
				SELECT c.category_id, c.category_name, pc.relation_type
				FROM paper_categories pc JOIN arxiv_categories c ON c.id = pc.category_id
				WHERE pc.paper_id = :paperId
				ORDER BY CASE pc.relation_type WHEN 'PRIMARY' THEN 0 ELSE 1 END, c.category_id
				""").bind("paperId", paperId).map((row, metadata) -> new CategoryRow(
						row.get("category_id", String.class), row.get("category_name", String.class),
						row.get("relation_type", String.class))).all();
	}

	public Flux<VersionRow> versions(UUID paperId) {
		return databaseClient.sql("""
				SELECT version_number, submitted_at, size_bytes, source_format
				FROM paper_versions WHERE paper_id = :paperId ORDER BY version_number
				""").bind("paperId", paperId).map((row, metadata) -> new VersionRow(
						row.get("version_number", Integer.class), row.get("submitted_at", Instant.class),
						row.get("size_bytes", Long.class), row.get("source_format", String.class))).all();
	}

	public Flux<ImportRow> imports(UUID paperId) {
		return databaseClient.sql("""
				SELECT pi.job_id, pi.metadata_source, pi.source_datestamp, pi.imported_at
				FROM paper_imports pi WHERE pi.paper_id = :paperId ORDER BY pi.imported_at DESC, pi.job_id
				""").bind("paperId", paperId).map((row, metadata) -> new ImportRow(
						row.get("job_id", UUID.class), row.get("metadata_source", String.class),
						row.get("source_datestamp", Instant.class), row.get("imported_at", Instant.class))).all();
	}

	private String summarySql() {
		return """
				SELECT p.id, p.arxiv_id, p.title, p.submitted_at, p.updated_at,
				       p.doi, p.journal_reference, p.source_status, p.version_count,
				       c.category_id AS primary_category,
				       coalesce((SELECT jsonb_agg(pa.raw_name ORDER BY pa.author_order)::text
				                 FROM paper_authors pa WHERE pa.paper_id = p.id), '[]') AS authors
				FROM papers p LEFT JOIN arxiv_categories c ON c.id = p.primary_category_id
				""";
	}

	private String filtersSql() {
		return """
				WHERE p.deleted_at IS NULL
				  AND (:categoryEmpty OR EXISTS (
				    SELECT 1 FROM paper_categories pc JOIN arxiv_categories fc ON fc.id = pc.category_id
				    WHERE pc.paper_id = p.id AND fc.category_id = :category))
				  AND (:titleEmpty OR p.title ILIKE :title ESCAPE '\\')
				  AND (:authorEmpty OR EXISTS (
				    SELECT 1 FROM paper_authors pa WHERE pa.paper_id = p.id AND pa.raw_name ILIKE :author ESCAPE '\\'))
				  AND (:sourceEmpty OR p.source_status = :sourceStatus)
				  AND p.submitted_at >= :submittedFrom AND p.submitted_at <= :submittedTo
				  AND p.updated_at >= :updatedFrom AND p.updated_at <= :updatedTo
				  AND (:hasDoiEmpty OR (:hasDoi = (p.doi IS NOT NULL)))
				  AND (:hasJournalEmpty OR (:hasJournal = (p.journal_reference IS NOT NULL)))
				""";
	}

	private DatabaseClient.GenericExecuteSpec bind(
			DatabaseClient.GenericExecuteSpec statement, PaperQueryService.PaperFilter filter
	) {
		return statement.bind("categoryEmpty", filter.category() == null)
				.bind("category", value(filter.category())).bind("titleEmpty", filter.title() == null)
				.bind("title", like(filter.title())).bind("authorEmpty", filter.author() == null)
				.bind("author", like(filter.author())).bind("sourceEmpty", filter.sourceStatus() == null)
				.bind("sourceStatus", value(filter.sourceStatus()))
				.bind("submittedFrom", filter.submittedFrom() == null ? EARLIEST : filter.submittedFrom())
				.bind("submittedTo", filter.submittedTo() == null ? LATEST : filter.submittedTo())
				.bind("updatedFrom", filter.updatedFrom() == null ? EARLIEST : filter.updatedFrom())
				.bind("updatedTo", filter.updatedTo() == null ? LATEST : filter.updatedTo())
				.bind("hasDoiEmpty", filter.hasDoi() == null).bind("hasDoi", Boolean.TRUE.equals(filter.hasDoi()))
				.bind("hasJournalEmpty", filter.hasJournalReference() == null)
				.bind("hasJournal", Boolean.TRUE.equals(filter.hasJournalReference()));
	}

	private PaperSummaryRow summary(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new PaperSummaryRow(
				row.get("id", UUID.class), row.get("arxiv_id", String.class), row.get("title", String.class),
				row.get("primary_category", String.class), strings(row.get("authors", String.class)),
				row.get("submitted_at", Instant.class), row.get("updated_at", Instant.class),
				row.get("doi", String.class), row.get("journal_reference", String.class),
				row.get("source_status", String.class), row.get("version_count", Integer.class));
	}

	private String value(String value) {
		return value == null ? "" : value;
	}

	private String like(String value) {
		if (value == null) {
			return "";
		}
		return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	private List<String> strings(String json) {
		try {
			return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() { });
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Stored paper list could not be read", exception);
		}
	}

	private JsonNode jsonNode(String json) {
		try {
			return objectMapper.readTree(json == null ? "{}" : json);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Stored paper metadata could not be read", exception);
		}
	}

	public record PaperSummaryRow(
			UUID id, String arxivId, String title, String primaryCategory, List<String> authors,
			Instant submittedAt, Instant updatedAt, String doi, String journalReference,
			String sourceStatus, int versionCount
	) {
	}

	public record PaperBaseRow(
			UUID id, String arxivId, String title, String abstractText, String primaryCategory,
			Instant submittedAt, Instant updatedAt, String doi, String journalReference,
			String comment, String licenseUrl, String pdfUrl, String sourceStatus, String sourceFormat,
			int versionCount, JsonNode rawMetadata, Instant metadataSourceUpdatedAt, Instant importedAt
	) {
	}

	public record AuthorRow(int order, String name, List<String> affiliations) { }
	public record CategoryRow(String categoryId, String categoryName, String relationType) { }
	public record VersionRow(int version, Instant submittedAt, Long sizeBytes, String sourceFormat) { }
	public record ImportRow(UUID jobId, String metadataSource, Instant sourceDatestamp, Instant importedAt) { }
}
