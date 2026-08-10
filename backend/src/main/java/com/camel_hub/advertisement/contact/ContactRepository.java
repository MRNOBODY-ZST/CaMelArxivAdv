package com.camel_hub.advertisement.contact;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public class ContactRepository {

	private final DatabaseClient databaseClient;

	public ContactRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<ContactRow> list(ContactService.ContactFilter filter, int offset, int limit) {
		return bind(databaseClient.sql(selectSql(true) + filtersSql() + """
				ORDER BY c.last_extracted_at DESC, c.id
				OFFSET :offset LIMIT :limit
				"""), filter).bind("offset", offset).bind("limit", limit).map(this::row).all();
	}

	public Mono<Long> count(ContactService.ContactFilter filter) {
		return bind(databaseClient.sql("""
				SELECT count(*) AS total FROM contacts c
				LEFT JOIN LATERAL (
				  SELECT pac.id AS mapping_id, pac.confidence, pac.corresponding_author,
				         pac.verification_status, pac.paper_id
				  FROM paper_author_contacts pac
				  JOIN extraction_runs er ON er.id = pac.extraction_run_id
				  WHERE pac.contact_id = c.id
				    AND (:paperEmpty OR pac.paper_id = :paperId)
				  ORDER BY er.completed_at DESC NULLS LAST, pac.created_at DESC, pac.id
				  LIMIT 1
				) latest ON true
				""" + filtersSql()), filter)
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<ContactRow> find(UUID contactId) {
		return databaseClient.sql(selectSql(false) + " WHERE c.id = :contactId AND c.deleted_at IS NULL")
				.bind("contactId", contactId).map(this::row).one();
	}

	public Flux<EvidenceRow> evidence(UUID mappingId) {
		if (mappingId == null) {
			return Flux.empty();
		}
		return databaseClient.sql("""
				SELECT source_relative_path, rule_name, line_number, logical_location, masked_context
				FROM extraction_evidence WHERE paper_author_contact_id = :mappingId
				ORDER BY created_at, id
				""").bind("mappingId", mappingId).map((row, metadata) -> new EvidenceRow(
						row.get("source_relative_path", String.class), row.get("rule_name", String.class),
						row.get("line_number", Integer.class), row.get("logical_location", String.class),
						row.get("masked_context", String.class))).all();
	}

	public Mono<Boolean> updateVerification(
			UUID contactId,
			UUID mappingId,
			long expectedVersion,
			String status,
			UUID actorId
	) {
		return databaseClient.sql("""
				UPDATE paper_author_contacts SET
				  human_verified = true, verification_status = :status,
				  verified_by = :actorId, verified_at = now(), version = version + 1
				WHERE id = :mappingId AND contact_id = :contactId AND version = :expectedVersion
				""").bind("status", status).bind("actorId", actorId).bind("mappingId", mappingId)
				.bind("contactId", contactId).bind("expectedVersion", expectedVersion)
				.fetch().rowsUpdated().map(rows -> rows == 1);
	}

	private String selectSql(boolean scopeMappingToPaper) {
		String paperScope = scopeMappingToPaper
				? " AND (:paperEmpty OR pac.paper_id = :paperId)\n" : "";
		return """
				SELECT c.id, c.display_ciphertext, c.display_nonce, c.email_domain,
				       c.example_address, c.suppression_status, c.last_extracted_at,
				       latest.mapping_id, latest.version, latest.confidence,
				       latest.corresponding_author, latest.verification_status,
				       latest.human_verified, latest.paper_id,
				       p.arxiv_id, p.title AS paper_title, pa.raw_name AS author_name,
				       category.category_id,
				       (SELECT ee.rule_name FROM extraction_evidence ee
				        WHERE ee.paper_author_contact_id = latest.mapping_id
				        ORDER BY ee.created_at, ee.id LIMIT 1) AS rule_name
				FROM contacts c
				LEFT JOIN LATERAL (
				  SELECT pac.id AS mapping_id, pac.version, pac.confidence,
				         pac.corresponding_author, pac.verification_status,
				         pac.human_verified, pac.paper_id, pac.paper_author_id
				  FROM paper_author_contacts pac
				  JOIN extraction_runs er ON er.id = pac.extraction_run_id
				  WHERE pac.contact_id = c.id
				""" + paperScope + """
				  ORDER BY er.completed_at DESC NULLS LAST, pac.created_at DESC, pac.id
				  LIMIT 1
				) latest ON true
				LEFT JOIN papers p ON p.id = latest.paper_id
				LEFT JOIN paper_authors pa ON pa.id = latest.paper_author_id
				LEFT JOIN arxiv_categories category ON category.id = p.primary_category_id
				""";
	}

	private String filtersSql() {
		return """
				WHERE c.deleted_at IS NULL
				  AND (:domainEmpty OR c.email_domain = :domain)
				  AND (:confidenceEmpty OR latest.confidence = :confidence)
				  AND (:verificationEmpty OR latest.verification_status = :verification)
				  AND (:correspondingEmpty OR latest.corresponding_author = :corresponding)
				  AND (:paperEmpty OR latest.paper_id = :paperId)
				""";
	}

	private DatabaseClient.GenericExecuteSpec bind(
			DatabaseClient.GenericExecuteSpec statement,
			ContactService.ContactFilter filter
	) {
		return statement.bind("domainEmpty", filter.domain() == null)
				.bind("domain", value(filter.domain()))
				.bind("confidenceEmpty", filter.confidence() == null)
				.bind("confidence", value(filter.confidence()))
				.bind("verificationEmpty", filter.verificationStatus() == null)
				.bind("verification", value(filter.verificationStatus()))
				.bind("correspondingEmpty", filter.corresponding() == null)
				.bind("corresponding", Boolean.TRUE.equals(filter.corresponding()))
				.bind("paperEmpty", filter.paperId() == null)
				.bind("paperId", filter.paperId() == null ? new UUID(0, 0) : filter.paperId());
	}

	private ContactRow row(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		Long version = row.get("version", Long.class);
		return new ContactRow(
				row.get("id", UUID.class), row.get("display_ciphertext", byte[].class),
				row.get("display_nonce", byte[].class), row.get("email_domain", String.class),
				Boolean.TRUE.equals(row.get("example_address", Boolean.class)),
				row.get("suppression_status", String.class), row.get("last_extracted_at", Instant.class),
				row.get("mapping_id", UUID.class), version == null ? 0 : version,
				row.get("confidence", String.class),
				Boolean.TRUE.equals(row.get("corresponding_author", Boolean.class)),
				row.get("verification_status", String.class),
				Boolean.TRUE.equals(row.get("human_verified", Boolean.class)),
				row.get("paper_id", UUID.class), row.get("arxiv_id", String.class),
				row.get("paper_title", String.class), row.get("author_name", String.class),
				row.get("category_id", String.class), row.get("rule_name", String.class));
	}

	private String value(String value) {
		return value == null ? "" : value;
	}

	public record ContactRow(
			UUID id,
			byte[] displayCiphertext,
			byte[] displayNonce,
			String domain,
			boolean exampleAddress,
			String suppressionStatus,
			Instant lastExtractedAt,
			UUID mappingId,
			long version,
			String confidence,
			boolean corresponding,
			String verificationStatus,
			boolean humanVerified,
			UUID paperId,
			String arxivId,
			String paperTitle,
			String authorName,
			String categoryId,
			String ruleName
	) { }

	public record EvidenceRow(
			String sourceRelativePath,
			String ruleName,
			Integer lineNumber,
			String logicalLocation,
			String maskedContext
	) { }
}
