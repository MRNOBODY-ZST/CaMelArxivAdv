package com.camel_hub.advertisement.arxiv.paper;

import com.camel_hub.advertisement.messaging.ArxivResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PaperRepository {

	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public PaperRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Mono<Void> upsertBatch(
			UUID jobId, List<ArxivResultMessage.Paper> papers, String metadataSource
	) {
		return Flux.fromIterable(papers).concatMap(paper -> upsert(jobId, paper, metadataSource)).then();
	}

	private Mono<Void> upsert(
			UUID jobId, ArxivResultMessage.Paper paper, String metadataSource
	) {
		validate(paper);
		Instant submitted = temporal(paper.publishedAt());
		Instant updated = temporal(paper.updatedAt());
		if (updated.isBefore(submitted)) {
			return Mono.error(new IllegalArgumentException("Paper update date precedes submission date"));
		}
		return categoryId(paper.primaryCategory())
				.flatMap(primaryId -> upsertPaper(paper, primaryId, submitted, updated)
						.flatMap(result -> result.updated()
								? replaceRelations(result.id(), paper, submitted)
										.then(recordImport(result.id(), jobId, metadataSource, updated))
								: recordImport(result.id(), jobId, metadataSource, updated)));
	}

	private Mono<PaperUpsert> upsertPaper(
			ArxivResultMessage.Paper paper, UUID primaryCategoryId, Instant submitted, Instant updated
	) {
		int version = paper.version() == null ? 1 : paper.version();
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO papers (
				  arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at,
				  doi, journal_reference, comment_text, license_url, pdf_url,
				  version_count, metadata_raw, metadata_source_updated_at, imported_at, deleted_at
				)
				VALUES (
				  :arxivId, :title, :abstractText, :primaryCategoryId, :submittedAt, :updatedAt,
				  :doi, :journalReference, :commentText, :licenseUrl, :pdfUrl,
				  :version, CAST(:metadataRaw AS jsonb), :updatedAt, now(), NULL
				)
				ON CONFLICT (arxiv_id) DO UPDATE SET
				  title = EXCLUDED.title, abstract_text = EXCLUDED.abstract_text,
				  primary_category_id = EXCLUDED.primary_category_id,
				  submitted_at = EXCLUDED.submitted_at, updated_at = EXCLUDED.updated_at,
				  doi = EXCLUDED.doi, journal_reference = EXCLUDED.journal_reference,
				  comment_text = EXCLUDED.comment_text, license_url = EXCLUDED.license_url,
				  pdf_url = EXCLUDED.pdf_url,
				  version_count = GREATEST(papers.version_count, EXCLUDED.version_count),
				  metadata_raw = EXCLUDED.metadata_raw,
				  metadata_source_updated_at = EXCLUDED.metadata_source_updated_at,
				  imported_at = now(), deleted_at = NULL
				WHERE papers.metadata_source_updated_at IS NULL
				   OR papers.metadata_source_updated_at <= EXCLUDED.metadata_source_updated_at
				RETURNING id
				""").bind("arxivId", paper.arxivId()).bind("title", paper.title().strip())
				.bind("abstractText", paper.abstractText().strip()).bind("primaryCategoryId", primaryCategoryId)
				.bind("submittedAt", submitted).bind("updatedAt", updated)
				.bind("version", version).bind("metadataRaw", json(paper))
				.bind("pdfUrl", paper.pdfUrl());
		statement = bindNullable(statement, "doi", paper.doi());
		statement = bindNullable(statement, "journalReference", paper.journalReference());
		statement = bindNullable(statement, "commentText", paper.comment());
		statement = bindNullable(statement, "licenseUrl", paper.licenseUrl());
		return statement
				.map((row, metadata) -> new PaperUpsert(row.get("id", UUID.class), true)).one()
				.switchIfEmpty(findPaper(paper.arxivId()).map(id -> new PaperUpsert(id, false)));
	}

	private Mono<Void> replaceRelations(UUID paperId, ArxivResultMessage.Paper paper, Instant submitted) {
		return databaseClient.sql("DELETE FROM paper_authors WHERE paper_id = :paperId")
				.bind("paperId", paperId).fetch().rowsUpdated()
				.then(databaseClient.sql("DELETE FROM paper_categories WHERE paper_id = :paperId")
						.bind("paperId", paperId).fetch().rowsUpdated())
				.then(upsertAuthors(paperId, paper.authors()))
				.then(upsertCategories(paperId, paper.primaryCategory(), paper.categories()))
				.then(upsertVersion(paperId, paper.version() == null ? 1 : paper.version(), submitted));
	}

	private Mono<Void> upsertAuthors(UUID paperId, List<ArxivResultMessage.Author> authors) {
		return Flux.range(0, authors.size()).concatMap(index -> {
			ArxivResultMessage.Author author = authors.get(index);
			String name = normalizeDisplay(author.name());
			String normalized = name.toLowerCase(Locale.ROOT);
			return findOrCreateAuthor(normalized, name)
					.flatMap(authorId -> databaseClient.sql("""
							INSERT INTO paper_authors (
							  paper_id, author_id, author_order, raw_name, affiliation_text, affiliation_data
							)
							VALUES (
							  :paperId, :authorId, :authorOrder, :rawName, :affiliationText,
							  CAST(:affiliations AS jsonb)
							)
							""").bind("paperId", paperId).bind("authorId", authorId)
							.bind("authorOrder", index + 1).bind("rawName", name)
							.bind("affiliationText", String.join("; ", author.affiliations()))
							.bind("affiliations", json(author.affiliations()))
							.fetch().rowsUpdated());
		}).then();
	}

	private Mono<Void> upsertCategories(UUID paperId, String primary, List<String> categories) {
		LinkedHashSet<String> unique = new LinkedHashSet<>(categories);
		unique.add(primary);
		return Flux.fromIterable(unique).concatMap(category -> categoryId(category)
				.flatMap(categoryId -> databaseClient.sql("""
						INSERT INTO paper_categories (paper_id, category_id, relation_type)
						VALUES (:paperId, :categoryId, :relationType)
						""").bind("paperId", paperId).bind("categoryId", categoryId)
						.bind("relationType", category.equals(primary) ? "PRIMARY" : "CROSS_LIST")
						.fetch().rowsUpdated())).then();
	}

	private Mono<Void> upsertVersion(UUID paperId, int version, Instant submitted) {
		return databaseClient.sql("""
				INSERT INTO paper_versions (paper_id, version_number, submitted_at)
				VALUES (:paperId, :version, :submittedAt)
				ON CONFLICT (paper_id, version_number) DO UPDATE
				SET submitted_at = LEAST(paper_versions.submitted_at, EXCLUDED.submitted_at)
				""").bind("paperId", paperId).bind("version", version).bind("submittedAt", submitted)
				.fetch().rowsUpdated().then();
	}

	private Mono<Void> recordImport(
			UUID paperId, UUID jobId, String source, Instant sourceDatestamp
	) {
		return databaseClient.sql("""
				INSERT INTO paper_imports (paper_id, job_id, metadata_source, source_datestamp)
				VALUES (:paperId, :jobId, :source, :sourceDatestamp)
				ON CONFLICT (paper_id, job_id) DO NOTHING
				""").bind("paperId", paperId).bind("jobId", jobId).bind("source", source)
				.bind("sourceDatestamp", sourceDatestamp).fetch().rowsUpdated().then();
	}

	private Mono<UUID> categoryId(String category) {
		return databaseClient.sql("SELECT id FROM arxiv_categories WHERE category_id = :category AND active = true")
				.bind("category", category).map((row, metadata) -> row.get("id", UUID.class)).one()
				.switchIfEmpty(Mono.error(new IllegalArgumentException(
						"Paper contains an inactive or unknown category")));
	}

	private Mono<UUID> findOrCreateAuthor(String normalized, String display) {
		return databaseClient.sql("""
				SELECT id FROM authors WHERE normalized_name = :normalized ORDER BY created_at, id LIMIT 1
				""").bind("normalized", normalized).map((row, metadata) -> row.get("id", UUID.class)).one()
				.switchIfEmpty(databaseClient.sql("""
						INSERT INTO authors (normalized_name, display_name) VALUES (:normalized, :display)
						RETURNING id
						""").bind("normalized", normalized).bind("display", display)
						.map((row, metadata) -> row.get("id", UUID.class)).one());
	}

	private Mono<UUID> findPaper(String arxivId) {
		return databaseClient.sql("SELECT id FROM papers WHERE arxiv_id = :arxivId")
				.bind("arxivId", arxivId).map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	private void validate(ArxivResultMessage.Paper paper) {
		if (paper == null || paper.arxivId() == null
				|| !paper.arxivId().matches("(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})")
				|| paper.title() == null || paper.title().isBlank() || paper.title().length() > 20_000
				|| paper.abstractText() == null || paper.abstractText().isBlank()
				|| paper.abstractText().length() > 200_000
				|| paper.authors() == null || paper.authors().isEmpty() || paper.authors().size() > 500
				|| paper.categories() == null || paper.categories().isEmpty()
				|| paper.primaryCategory() == null || !paper.categories().contains(paper.primaryCategory())
				|| paper.pdfUrl() == null || !paper.pdfUrl().startsWith("https://arxiv.org/")) {
			throw new IllegalArgumentException("Worker paper metadata is invalid");
		}
		paper.authors().forEach(author -> {
			if (author.name() == null || author.name().isBlank() || author.name().length() > 300
					|| author.affiliations() == null || author.affiliations().size() > 100) {
				throw new IllegalArgumentException("Worker author metadata is invalid");
			}
		});
	}

	private Instant temporal(String value) {
		try {
			return value.length() == 10
					? LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
					: OffsetDateTime.parse(value).toInstant();
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Worker paper date is invalid", exception);
		}
	}

	private String normalizeDisplay(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Worker paper metadata could not be serialized", exception);
		}
	}

	private DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, String value
	) {
		return value == null ? statement.bindNull(name, String.class) : statement.bind(name, value);
	}

	private record PaperUpsert(UUID id, boolean updated) {
	}
}
