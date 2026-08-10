package com.camel_hub.advertisement.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class SegmentRepository {

	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public SegmentRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Flux<SegmentHeader> list(int offset, int limit) {
		return databaseClient.sql("""
				SELECT id, name, description, created_at, updated_at
				FROM segments ORDER BY updated_at DESC, id
				OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit).map(this::header).all();
	}

	public Mono<Long> count() {
		return databaseClient.sql("SELECT count(*) AS total FROM segments")
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<SegmentHeader> find(UUID id) {
		return databaseClient.sql("""
				SELECT id, name, description, created_at, updated_at
				FROM segments WHERE id = :id
				""").bind("id", id).map(this::header).one();
	}

	public Flux<SegmentModels.RuleInput> rules(UUID segmentId) {
		return databaseClient.sql("""
				SELECT field_name, operator, CAST(value_data AS text) AS value_data
				FROM segment_rules WHERE segment_id = :segmentId ORDER BY rule_order
				""").bind("segmentId", segmentId).map((row, metadata) -> new SegmentModels.RuleInput(
				row.get("field_name", String.class), row.get("operator", String.class),
				json(row.get("value_data", String.class)))).all();
	}

	public Mono<UUID> create(String name, String description, UUID actorId) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO segments (name, description, created_by)
				VALUES (:name, :description, :actorId) RETURNING id
				""").bind("name", name).bind("actorId", actorId);
		statement = bindNullable(statement, "description", description, String.class);
		return statement.map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	public Mono<Void> insertRule(UUID segmentId, int order, SegmentModels.RuleInput rule) {
		return databaseClient.sql("""
				INSERT INTO segment_rules (segment_id, rule_order, field_name, operator, value_data)
				VALUES (:segmentId, :ruleOrder, :field, :operator, CAST(:value AS jsonb))
				""").bind("segmentId", segmentId).bind("ruleOrder", order)
				.bind("field", rule.field()).bind("operator", rule.operator())
				.bind("value", json(rule.value())).fetch().rowsUpdated().then();
	}

	public Mono<Long> eligibleCount(SegmentModels.SegmentCriteria criteria) {
		return bindCriteria(databaseClient.sql(eligibleCte() + " SELECT count(*) AS total FROM eligible"), criteria)
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Flux<EligibleContact> eligibleSample(SegmentModels.SegmentCriteria criteria, int limit) {
		return bindCriteria(databaseClient.sql(eligibleCte() + """
				 SELECT contact_id, email_domain, author_name, paper_title, arxiv_id, primary_category,
				        confidence, verification_status, corresponding_author
				 FROM eligible ORDER BY paper_title, contact_id LIMIT :limit
				"""), criteria).bind("limit", limit).map((row, metadata) -> new EligibleContact(
				row.get("contact_id", UUID.class), row.get("email_domain", String.class),
				row.get("author_name", String.class), row.get("paper_title", String.class),
				row.get("arxiv_id", String.class), row.get("primary_category", String.class),
				row.get("confidence", String.class), row.get("verification_status", String.class),
				Boolean.TRUE.equals(row.get("corresponding_author", Boolean.class)))).all();
	}

	public Flux<CampaignCandidate> campaignCandidates(SegmentModels.SegmentCriteria criteria, int limit) {
		return bindCriteria(databaseClient.sql(eligibleCte() + """
				 SELECT contact_id, paper_id, author_id, email_ciphertext, email_nonce, email_hmac,
				        email_domain, author_name, paper_title, abstract_text, arxiv_id, primary_category,
				        organization, confidence
				 FROM eligible ORDER BY paper_title, contact_id LIMIT :limit
				"""), criteria).bind("limit", limit).map((row, metadata) -> new CampaignCandidate(
				row.get("contact_id", UUID.class), row.get("paper_id", UUID.class),
				row.get("author_id", UUID.class), row.get("email_ciphertext", byte[].class),
				row.get("email_nonce", byte[].class), row.get("email_hmac", byte[].class),
				row.get("email_domain", String.class), row.get("author_name", String.class),
				row.get("paper_title", String.class), row.get("abstract_text", String.class),
				row.get("arxiv_id", String.class), row.get("primary_category", String.class),
				row.get("organization", String.class), row.get("confidence", String.class))).all();
	}

	private String eligibleCte() {
		return """
				WITH eligible AS (
				  SELECT DISTINCT ON (c.id)
				         c.id AS contact_id, pac.paper_id, pa.author_id,
				         c.email_ciphertext, c.email_nonce, c.email_hmac, c.email_domain,
				         pa.raw_name AS author_name, pa.affiliation_text AS organization,
				         p.title AS paper_title, p.abstract_text, p.arxiv_id,
				         category.category_id AS primary_category,
				         pac.confidence, pac.verification_status, pac.corresponding_author
				  FROM contacts c
				  JOIN paper_author_contacts pac ON pac.contact_id = c.id
				  JOIN papers p ON p.id = pac.paper_id AND p.deleted_at IS NULL
				  LEFT JOIN paper_authors pa ON pa.id = pac.paper_author_id
				  LEFT JOIN arxiv_categories category ON category.id = p.primary_category_id
				  WHERE c.deleted_at IS NULL
				    AND c.syntax_valid = true
				    AND c.example_address = false
				    AND c.suppression_status = 'ACTIVE'
				    AND pac.confidence IN ('HIGH', 'MEDIUM')
				    AND pac.verification_status <> 'REJECTED'
				    AND NOT EXISTS (
				      SELECT 1 FROM suppression_entries se
				      WHERE se.email_hmac = c.email_hmac AND (se.expires_at IS NULL OR se.expires_at > now())
				    )
				    AND NOT EXISTS (
				      SELECT 1 FROM unsubscribe_records ur WHERE ur.email_hmac = c.email_hmac
				    )
				    AND (:categoryEmpty OR category.category_id = :category)
				    AND (:confidenceEmpty OR pac.confidence = :confidence)
				    AND (:verificationEmpty OR pac.verification_status = :verification)
				    AND (:correspondingEmpty OR pac.corresponding_author = :corresponding)
				  ORDER BY c.id, pac.human_verified DESC, pac.created_at DESC, pac.id
				)
				""";
	}

	private DatabaseClient.GenericExecuteSpec bindCriteria(
			DatabaseClient.GenericExecuteSpec statement, SegmentModels.SegmentCriteria criteria
	) {
		return statement.bind("categoryEmpty", criteria.primaryCategory() == null)
				.bind("category", value(criteria.primaryCategory()))
				.bind("confidenceEmpty", criteria.confidence() == null)
				.bind("confidence", value(criteria.confidence()))
				.bind("verificationEmpty", criteria.verificationStatus() == null)
				.bind("verification", value(criteria.verificationStatus()))
				.bind("correspondingEmpty", criteria.corresponding() == null)
				.bind("corresponding", Boolean.TRUE.equals(criteria.corresponding()));
	}

	private SegmentHeader header(Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new SegmentHeader(
				row.get("id", UUID.class), row.get("name", String.class), row.get("description", String.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}

	private Object json(String value) {
		try {
			return objectMapper.readValue(value, Object.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Stored segment rule could not be read", exception);
		}
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new SegmentValidationException("Segment rule value could not be encoded");
		}
	}

	private String value(String value) {
		return value == null ? "" : value;
	}

	private <T> DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, T value, Class<T> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	public record SegmentHeader(UUID id, String name, String description, Instant createdAt, Instant updatedAt) { }

	public record EligibleContact(
			UUID contactId, String emailDomain, String authorName, String paperTitle, String arxivId,
			String primaryCategory, String confidence, String verificationStatus, boolean corresponding
	) { }

	public record CampaignCandidate(
			UUID contactId, UUID paperId, UUID authorId, byte[] emailCiphertext, byte[] emailNonce,
			byte[] emailHmac, String emailDomain, String authorName, String paperTitle,
			String abstractText, String arxivId, String primaryCategory, String organization,
			String confidence
	) { }
}
