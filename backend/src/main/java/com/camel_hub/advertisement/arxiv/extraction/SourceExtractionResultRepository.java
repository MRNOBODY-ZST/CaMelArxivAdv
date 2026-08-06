package com.camel_hub.advertisement.arxiv.extraction;

import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.messaging.ArxivResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SourceExtractionResultRepository {

	private final DatabaseClient databaseClient;
	private final ContactCrypto crypto;
	private final ObjectMapper objectMapper;

	public SourceExtractionResultRepository(
			DatabaseClient databaseClient,
			ContactCrypto crypto,
			ObjectMapper objectMapper
	) {
		this.databaseClient = databaseClient;
		this.crypto = crypto;
		this.objectMapper = objectMapper;
	}

	public Mono<Void> apply(
			ArxivResultMessage message,
			ArxivResultMessage.SourceExtraction result
	) {
		return validateTarget(message.jobId(), result)
				.then(insertRun(message, result))
				.flatMap(runId -> updatePaper(result)
						.then(upsertAuthors(result).collectMap(AuthorLink::order, AuthorLink::paperAuthorId))
						.flatMap(authors -> upsertContacts(result, runId, authors))
						.then(updateJobItem(message.jobId(), result))
						.then(insertJobError(message.jobId(), result)))
				.then();
	}

	private Mono<Void> validateTarget(UUID jobId, ArxivResultMessage.SourceExtraction result) {
		return databaseClient.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM jobs j
				  JOIN job_items ji ON ji.job_id = j.id
				  JOIN papers p ON p.id::text = ji.external_key
				  WHERE j.id = :jobId
				    AND j.type IN ('ARXIV_FETCH_AND_PARSE_SOURCE', 'ARXIV_REEXTRACT_CONTACTS')
				    AND p.id = :paperId AND p.arxiv_id = :arxivId AND p.deleted_at IS NULL
				) AS valid
				""").bind("jobId", jobId).bind("paperId", result.paperId())
				.bind("arxivId", result.arxivId())
				.map((row, metadata) -> Boolean.TRUE.equals(row.get("valid", Boolean.class))).one()
				.flatMap(valid -> valid ? Mono.empty()
						: Mono.error(new IllegalArgumentException(
								"Extraction result does not match its job target")));
	}

	private Mono<UUID> insertRun(
			ArxivResultMessage message,
			ArxivResultMessage.SourceExtraction result
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO extraction_runs (
				  paper_id, job_id, message_id, idempotency_key, parser_version, status,
				  document_class, source_format, files_inspected, contacts_found,
				  archive_size_bytes, extracted_size_bytes, duration_ms,
				  cleanup_confirmed, cleanup_confirmed_at, started_at, completed_at,
				  error_code, error_summary)
				VALUES (
				  :paperId, :jobId, :messageId, :idempotencyKey, :parserVersion, :status,
				  :documentClass, :sourceFormat, :filesInspected, :contactsFound,
				  :archiveSize, :extractedSize, :durationMs,
				  true, :completedAt, :startedAt, :completedAt, :errorCode, :errorSummary)
				ON CONFLICT DO NOTHING
				RETURNING id
				""").bind("paperId", result.paperId()).bind("jobId", message.jobId())
				.bind("messageId", message.messageId())
				.bind("idempotencyKey", truncate(message.idempotencyKey(), 200))
				.bind("parserVersion", result.parserVersion()).bind("status", result.status())
				.bind("filesInspected", result.filesInspected())
				.bind("contactsFound", safe(result.contacts()).size())
				.bind("archiveSize", result.archiveSizeBytes())
				.bind("extractedSize", result.extractedSizeBytes())
				.bind("durationMs", result.durationMs())
				.bind("startedAt", message.occurredAt().minusMillis(result.durationMs()))
				.bind("completedAt", message.occurredAt());
		statement = bindNullable(statement, "documentClass", result.documentClass(), String.class);
		statement = bindNullable(statement, "sourceFormat", result.sourceFormat(), String.class);
		statement = bindNullable(statement, "errorCode", result.errorCode(), String.class);
		statement = bindNullable(statement, "errorSummary", result.errorSummary(), String.class);
		return statement.map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	private Mono<Void> updatePaper(ArxivResultMessage.SourceExtraction result) {
		String sourceStatus = switch (result.status()) {
			case "SUCCEEDED" -> "PARSED";
			case "PARTIALLY_SUCCEEDED" -> "PARTIALLY_PARSED";
			case "SOURCE_UNAVAILABLE" -> "UNAVAILABLE";
			case "SECURITY_REJECTED" -> "SECURITY_REJECTED";
			default -> "PARSE_FAILED";
		};
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				UPDATE papers SET source_status = :status, source_format = :sourceFormat,
				  last_extracted_at = now()
				WHERE id = :paperId AND arxiv_id = :arxivId AND deleted_at IS NULL
				""").bind("status", sourceStatus).bind("paperId", result.paperId())
				.bind("arxivId", result.arxivId());
		statement = bindNullable(statement, "sourceFormat", result.sourceFormat(), String.class);
		return statement.fetch().rowsUpdated()
				.flatMap(rows -> rows == 1 ? Mono.empty()
						: Mono.error(new IllegalArgumentException("Extraction paper no longer exists")));
	}

	private Flux<AuthorLink> upsertAuthors(ArxivResultMessage.SourceExtraction result) {
		return Flux.fromIterable(safe(result.authors())).concatMap(author -> {
			String display = normalizeName(author.name());
			String normalized = display.toLowerCase(Locale.ROOT);
			return findOrCreateAuthor(normalized, display)
					.flatMap(authorId -> upsertPaperAuthor(result.paperId(), authorId, author, display));
		});
	}

	private Mono<UUID> findOrCreateAuthor(String normalized, String display) {
		return databaseClient.sql("""
				SELECT id FROM authors WHERE normalized_name = :normalized ORDER BY created_at, id LIMIT 1
				""").bind("normalized", normalized).map((row, metadata) -> row.get("id", UUID.class)).one()
				.switchIfEmpty(databaseClient.sql("""
						INSERT INTO authors (normalized_name, display_name)
						VALUES (:normalized, :display) RETURNING id
						""").bind("normalized", normalized).bind("display", display)
						.map((row, metadata) -> row.get("id", UUID.class)).one());
	}

	private Mono<AuthorLink> upsertPaperAuthor(
			UUID paperId,
			UUID authorId,
			ArxivResultMessage.SourceAuthor author,
			String display
	) {
		List<String> affiliations = safe(author.affiliations()).stream()
				.map(value -> truncate(value.strip(), 2000)).toList();
		return databaseClient.sql("""
				INSERT INTO paper_authors (
				  paper_id, author_id, author_order, corresponding_author,
				  raw_name, affiliation_text, affiliation_data)
				VALUES (
				  :paperId, :authorId, :authorOrder, :corresponding,
				  :rawName, :affiliationText, CAST(:affiliations AS jsonb))
				ON CONFLICT (paper_id, author_order) DO UPDATE SET
				  author_id = EXCLUDED.author_id,
				  corresponding_author = EXCLUDED.corresponding_author,
				  raw_name = EXCLUDED.raw_name,
				  affiliation_text = EXCLUDED.affiliation_text,
				  affiliation_data = EXCLUDED.affiliation_data
				RETURNING id
				""").bind("paperId", paperId).bind("authorId", authorId)
				.bind("authorOrder", author.order()).bind("corresponding", author.corresponding())
				.bind("rawName", display).bind("affiliationText", String.join("; ", affiliations))
				.bind("affiliations", json(affiliations))
				.map((row, metadata) -> new AuthorLink(
						author.order(), row.get("id", UUID.class))).one();
	}

	private Mono<Void> upsertContacts(
			ArxivResultMessage.SourceExtraction result,
			UUID runId,
			Map<Integer, UUID> authors
	) {
		return Flux.fromIterable(safe(result.contacts()))
				.concatMap(contact -> upsertContact(contact)
						.flatMap(contactId -> insertMapping(
								result.paperId(), runId, contactId,
								contact.authorOrder() == null ? null : authors.get(contact.authorOrder()), contact)
								.flatMap(mappingId -> insertEvidence(mappingId, contact.evidence()))))
				.then();
	}

	private Mono<UUID> upsertContact(ArxivResultMessage.SourceContact contact) {
		ContactCrypto.EncryptedValue email = crypto.encrypt(contact.normalizedEmail());
		ContactCrypto.EncryptedValue display = crypto.encrypt(contact.displayEmail());
		byte[] hmac = crypto.hmac(contact.normalizedEmail());
		return databaseClient.sql("""
				INSERT INTO contacts (
				  email_ciphertext, email_nonce, email_hmac, email_domain,
				  display_ciphertext, display_nonce, syntax_valid, example_address)
				VALUES (
				  :emailCiphertext, :emailNonce, :emailHmac, :domain,
				  :displayCiphertext, :displayNonce, :syntaxValid, :exampleAddress)
				ON CONFLICT (email_hmac) DO UPDATE SET
				  last_extracted_at = now(),
				  syntax_valid = contacts.syntax_valid OR EXCLUDED.syntax_valid,
				  example_address = contacts.example_address AND EXCLUDED.example_address
				RETURNING id
				""").bind("emailCiphertext", email.ciphertext()).bind("emailNonce", email.nonce())
				.bind("emailHmac", hmac).bind("domain", contact.domain())
				.bind("displayCiphertext", display.ciphertext()).bind("displayNonce", display.nonce())
				.bind("syntaxValid", contact.syntaxValid()).bind("exampleAddress", contact.exampleAddress())
				.map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	private Mono<UUID> insertMapping(
			UUID paperId,
			UUID runId,
			UUID contactId,
			UUID paperAuthorId,
			ArxivResultMessage.SourceContact contact
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO paper_author_contacts (
				  paper_author_id, paper_id, contact_id, extraction_run_id,
				  confidence, corresponding_author)
				VALUES (
				  :paperAuthorId, :paperId, :contactId, :runId, :confidence, :corresponding)
				RETURNING id
				""").bind("paperId", paperId).bind("contactId", contactId).bind("runId", runId)
				.bind("confidence", contact.confidence()).bind("corresponding", contact.corresponding());
		statement = bindNullable(statement, "paperAuthorId", paperAuthorId, UUID.class);
		return statement.map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	private Mono<Void> insertEvidence(
			UUID mappingId,
			List<ArxivResultMessage.SourceEvidence> evidence
	) {
		return Flux.fromIterable(safe(evidence)).concatMap(item -> {
			DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
					INSERT INTO extraction_evidence (
					  paper_author_contact_id, source_relative_path, rule_name,
					  line_number, logical_location, masked_context)
					VALUES (
					  :mappingId, :path, :ruleName, :lineNumber, :logicalLocation, :maskedContext)
					""").bind("mappingId", mappingId).bind("path", item.sourceRelativePath())
					.bind("ruleName", item.ruleName()).bind("logicalLocation", item.logicalLocation())
					.bind("maskedContext", item.maskedContext());
			statement = bindNullable(statement, "lineNumber", item.lineNumber(), Integer.class);
			return statement.fetch().rowsUpdated();
		}).then();
	}

	private Mono<Void> updateJobItem(UUID jobId, ArxivResultMessage.SourceExtraction result) {
		String itemStatus = switch (result.status()) {
			case "SUCCEEDED", "PARTIALLY_SUCCEEDED" -> "SUCCEEDED";
			case "SOURCE_UNAVAILABLE" -> "SKIPPED";
			default -> "FAILED";
		};
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				UPDATE job_items SET status = :status, attempt_count = attempt_count + 1,
				  result_summary = jsonb_build_object(
				    'sourceStatus', :sourceStatus, 'sourceFormat', :sourceFormat,
				    'filesInspected', :filesInspected, 'contactsFound', :contactsFound),
				  error_code = :errorCode, error_summary = :errorSummary,
				  started_at = coalesce(started_at, now()), completed_at = now()
				WHERE job_id = :jobId AND external_key = :externalKey
				""").bind("status", itemStatus).bind("sourceStatus", result.status())
				.bind("filesInspected", result.filesInspected())
				.bind("contactsFound", safe(result.contacts()).size())
				.bind("jobId", jobId).bind("externalKey", result.paperId().toString());
		statement = bindNullable(statement, "sourceFormat", result.sourceFormat(), String.class);
		statement = bindNullable(statement, "errorCode", result.errorCode(), String.class);
		statement = bindNullable(statement, "errorSummary", result.errorSummary(), String.class);
		return statement.fetch().rowsUpdated()
				.flatMap(rows -> rows == 1 ? Mono.empty()
						: Mono.error(new IllegalArgumentException("Extraction job item does not exist")));
	}

	private Mono<Void> insertJobError(UUID jobId, ArxivResultMessage.SourceExtraction result) {
		if (result.errorCode() == null) {
			return Mono.empty();
		}
		return databaseClient.sql("""
				INSERT INTO job_errors (job_id, job_item_id, category, code, summary, retryable)
				SELECT :jobId, id, 'SOURCE_EXTRACTION', :code, :summary, false
				FROM job_items WHERE job_id = :jobId AND external_key = :externalKey
				""").bind("jobId", jobId).bind("code", result.errorCode())
				.bind("summary", result.errorSummary() == null ? "Source extraction failed" : result.errorSummary())
				.bind("externalKey", result.paperId().toString()).fetch().rowsUpdated().then();
	}

	private String normalizeName(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Extraction value could not be serialized", exception);
		}
	}

	private <T> List<T> safe(List<T> value) {
		return value == null ? List.of() : List.copyOf(value);
	}

	private String truncate(String value, int maximum) {
		return value.substring(0, Math.min(value.length(), maximum));
	}

	private DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement,
			String name,
			Object value,
			Class<?> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	private record AuthorLink(int order, UUID paperAuthorId) { }
}
