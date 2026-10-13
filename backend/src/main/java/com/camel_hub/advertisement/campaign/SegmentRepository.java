package com.camel_hub.advertisement.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
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

	/** Keep every eligible paper/contact edge so shared authors cannot hide a selected paper. */
	public Flux<CampaignCandidate> selectedPaperCandidates(
			SegmentModels.SegmentCriteria criteria, List<UUID> paperIds
	) {
		return bindCriteria(databaseClient.sql(eligibleCte(true) + """
				 SELECT contact_id, paper_id, author_id, email_ciphertext, email_nonce, email_hmac,
				        email_domain, author_name, paper_title, abstract_text, arxiv_id, primary_category,
				        organization, confidence
				 FROM eligible ORDER BY paper_id, confidence, contact_id
				"""), criteria).bind("paperIds", paperIds).map((row, metadata) -> new CampaignCandidate(
				row.get("contact_id", UUID.class), row.get("paper_id", UUID.class),
				row.get("author_id", UUID.class), row.get("email_ciphertext", byte[].class),
				row.get("email_nonce", byte[].class), row.get("email_hmac", byte[].class),
				row.get("email_domain", String.class), row.get("author_name", String.class),
				row.get("paper_title", String.class), row.get("abstract_text", String.class),
				row.get("arxiv_id", String.class), row.get("primary_category", String.class),
				row.get("organization", String.class), row.get("confidence", String.class))).all();
	}

	public Flux<ExistingSelection> existingCampaignSelection(UUID campaignId) {
		return databaseClient.sql("""
				SELECT paper_id, email_hmac, personalization_status
				FROM campaign_recipients WHERE campaign_id = :campaignId
				ORDER BY id FOR UPDATE
				""").bind("campaignId", campaignId).map((row, metadata) -> new ExistingSelection(
				row.get("paper_id", UUID.class), row.get("email_hmac", byte[].class),
				row.get("personalization_status", String.class))).all();
	}

	/** Production evidence gates are additive: segment rules can narrow, never relax them. */
	public Flux<CampaignCandidate> productionCandidates(
			SegmentModels.SegmentCriteria criteria, Instant cooldownCutoff, String[] excludedHmacs
	) {
		return bindCriteria(databaseClient.sql(eligibleCte(false, true) + """
				 SELECT contact_id, paper_id, author_id, email_ciphertext, email_nonce, email_hmac,
				        email_domain, author_name, paper_title, abstract_text, arxiv_id, primary_category,
				        organization, confidence
				 FROM eligible ORDER BY author_id, contact_id LIMIT 12000
				"""), criteria).bind("cooldownCutoff", cooldownCutoff).bind("excludedHmacs", excludedHmacs)
				.map((row, metadata) -> new CampaignCandidate(
						row.get("contact_id", UUID.class), row.get("paper_id", UUID.class), row.get("author_id", UUID.class),
						row.get("email_ciphertext", byte[].class), row.get("email_nonce", byte[].class),
						row.get("email_hmac", byte[].class), row.get("email_domain", String.class),
						row.get("author_name", String.class), row.get("paper_title", String.class),
						row.get("abstract_text", String.class), row.get("arxiv_id", String.class),
						row.get("primary_category", String.class), row.get("organization", String.class),
						row.get("confidence", String.class))).all();
	}

	private String eligibleCte() {
		return eligibleCte(false);
	}

	/** Explicit paper scope ignores segment confidence rules, but never live safety exclusions. */
	public Flux<CampaignCandidate> paperContactCandidates(UUID campaignId, List<UUID> paperIds,
			Instant cooldownCutoff, String[] excludedHmacs) {
		return paperContactCandidatePage(campaignId, paperIds, cooldownCutoff, excludedHmacs, 121);
	}

	/** Bounded page uses the exact PAPER_CONTACT policy; reservations advance the cursor. */
	public Flux<CampaignCandidate> paperContactCandidatePage(UUID campaignId, List<UUID> paperIds,
			Instant cooldownCutoff, String[] excludedHmacs, int limit) {
		if (paperIds == null || paperIds.isEmpty()) return Flux.empty();
		if (paperIds.size() > 10000 || limit < 1 || limit > 121) throw new CampaignValidationException("Candidate page exceeds bulk bounds");
		return databaseClient.sql("""
				SELECT DISTINCT ON (c.email_hmac)
				  c.id AS contact_id, p.id AS paper_id,
				  CASE WHEN trusted.identity THEN pa.author_id END AS author_id,
				  c.email_ciphertext, c.email_nonce, c.email_hmac, c.email_domain,
				  CASE WHEN trusted.identity THEN pa.raw_name END AS author_name,
				  CASE WHEN trusted.identity THEN pa.affiliation_text END AS organization,
				  p.title AS paper_title, p.abstract_text, p.arxiv_id, cat.category_id, pac.confidence
				FROM papers p
				JOIN paper_author_contacts pac ON pac.paper_id = p.id
				JOIN contacts c ON c.id = pac.contact_id
				LEFT JOIN paper_authors pa ON pa.id = pac.paper_author_id AND pa.paper_id = p.id
				LEFT JOIN arxiv_categories cat ON cat.id = p.primary_category_id
				CROSS JOIN LATERAL (SELECT pa.author_id IS NOT NULL
				  AND length(trim(coalesce(pa.raw_name, ''))) BETWEEN 1 AND 300
				  AND length(pa.raw_name) <= 300 AND length(coalesce(pa.affiliation_text, '')) <= 500
				  AND (pac.confidence = 'HIGH' OR (pac.human_verified AND pac.verification_status = 'CONFIRMED')) AS identity) trusted
				WHERE p.id IN (:papers) AND p.deleted_at IS NULL
				  AND c.deleted_at IS NULL AND c.syntax_valid AND NOT c.example_address
				  AND c.suppression_status = 'ACTIVE' AND pac.verification_status <> 'REJECTED'
				  AND pac.id = (SELECT latest.id FROM paper_author_contacts latest
				    JOIN extraction_runs er ON er.id = latest.extraction_run_id AND er.paper_id = latest.paper_id
				      AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
				    WHERE latest.paper_id = p.id AND latest.contact_id = c.id
				    ORDER BY er.completed_at DESC NULLS LAST, latest.created_at DESC, latest.id LIMIT 1)
				  AND EXISTS (SELECT 1 FROM extraction_evidence ee WHERE ee.paper_author_contact_id = pac.id)
				  AND length(trim(p.title)) BETWEEN 1 AND 2000 AND length(p.title) <= 2000
				  AND length(trim(coalesce(p.abstract_text, ''))) BETWEEN 1 AND 20000 AND length(p.abstract_text) <= 20000
				  AND p.arxiv_id ~ '^([0-9]{4}[.][0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$'
				  AND encode(c.email_hmac, 'hex') <> ALL(:excludedHmacs)
				  AND NOT EXISTS (SELECT 1 FROM suppression_entries se WHERE se.email_hmac = c.email_hmac
				    AND (se.expires_at IS NULL OR se.expires_at > now()))
				  AND NOT EXISTS (SELECT 1 FROM unsubscribe_records ur WHERE ur.email_hmac = c.email_hmac)
				  AND NOT EXISTS (SELECT 1 FROM campaign_exclusions ce WHERE ce.campaign_id = :campaign
				    AND ce.email_hmac = c.email_hmac)
				  AND NOT EXISTS (SELECT 1 FROM recipient_delivery_cooldowns cd WHERE cd.email_hmac = c.email_hmac
				    AND cd.last_smtp_accepted_at >= :cooldownCutoff)
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipients pending JOIN campaigns pc ON pc.id = pending.campaign_id
				    WHERE pending.email_hmac = c.email_hmac AND (pending.status IN ('CONNECTING', 'OUTCOME_UNKNOWN')
				      OR (pc.recipient_policy = 'PAPER_CONTACT'
				        AND pc.status IN ('DRAFT', 'READY_FOR_REVIEW', 'APPROVED', 'REJECTED', 'SCHEDULED', 'RUNNING', 'PAUSED')
				        AND pending.status IN ('QUEUED', 'TEMPORARY_FAILURE'))))
				  AND NOT EXISTS (SELECT 1 FROM bulk_outreach_reservations br WHERE br.email_hmac=c.email_hmac)
				  AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t JOIN campaign_recipients s
				    ON s.id=t.source_recipient_id WHERE s.email_hmac=c.email_hmac)
				ORDER BY c.email_hmac, trusted.identity DESC, p.id, pac.id LIMIT :candidateLimit
				""").bind("papers", paperIds).bind("campaign", campaignId).bind("cooldownCutoff", cooldownCutoff)
				.bind("candidateLimit", limit)
				.bind("excludedHmacs", excludedHmacs).map((row, metadata) -> new CampaignCandidate(
						row.get("contact_id", UUID.class), row.get("paper_id", UUID.class), row.get("author_id", UUID.class),
						row.get("email_ciphertext", byte[].class), row.get("email_nonce", byte[].class), row.get("email_hmac", byte[].class),
						row.get("email_domain", String.class), row.get("author_name", String.class), row.get("paper_title", String.class),
						row.get("abstract_text", String.class), row.get("arxiv_id", String.class), row.get("category_id", String.class),
						row.get("organization", String.class), row.get("confidence", String.class))).all();
	}

	private String eligibleCte(boolean selectedPapers) {
		return eligibleCte(selectedPapers, false);
	}

	private String eligibleCte(boolean selectedPapers, boolean production) {
		String distinct = production ? "pa.author_id, c.email_hmac" : selectedPapers ? "pac.paper_id, c.id" : "c.id";
		String selectedFilter = selectedPapers ? "AND p.id IN (:paperIds)" : "";
		if (production) selectedFilter = """
				AND pa.author_id IS NOT NULL AND pa.paper_id = p.id
				AND (pac.confidence = 'HIGH' OR (pac.confidence = 'MEDIUM'
				  AND pac.human_verified = true AND pac.verification_status = 'CONFIRMED'))
				AND EXISTS (SELECT 1 FROM extraction_evidence ee WHERE ee.paper_author_contact_id = pac.id)
				AND pac.id = (
				  SELECT latest.id FROM paper_author_contacts latest
				  JOIN extraction_runs er ON er.id = latest.extraction_run_id
				    AND er.status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
				  WHERE latest.contact_id = c.id AND latest.paper_id = p.id
				  ORDER BY er.completed_at DESC NULLS LAST, latest.created_at DESC, latest.id LIMIT 1
				)
				AND length(trim(coalesce(pa.raw_name, ''))) BETWEEN 1 AND 300
				AND length(pa.raw_name) <= 300
				AND length(trim(p.title)) BETWEEN 1 AND 2000
				AND length(p.title) <= 2000
				AND length(trim(coalesce(p.abstract_text, ''))) BETWEEN 1 AND 20000
				AND length(p.abstract_text) <= 20000
				AND length(coalesce(pa.affiliation_text, '')) <= 500
				AND p.arxiv_id ~ '^([0-9]{4}[.][0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$'
				AND encode(c.email_hmac, 'hex') <> ALL(:excludedHmacs)
				AND NOT EXISTS (SELECT 1 FROM recipient_delivery_cooldowns cooldown
				  WHERE cooldown.email_hmac = c.email_hmac AND cooldown.last_smtp_accepted_at >= :cooldownCutoff)
				AND NOT EXISTS (SELECT 1 FROM campaign_recipients pending
				  WHERE pending.email_hmac = c.email_hmac AND pending.status IN ('CONNECTING', 'OUTCOME_UNKNOWN'))
				AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t JOIN campaign_recipients s
				  ON s.id=t.source_recipient_id WHERE s.email_hmac=c.email_hmac)
				AND NOT EXISTS (SELECT 1 FROM bulk_outreach_reservations br WHERE br.email_hmac=c.email_hmac)
				""";
		return """
				WITH eligible AS (
				  SELECT DISTINCT ON (%s)
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
				    AND NOT EXISTS (SELECT 1 FROM campaign_recipient_transfers t JOIN campaign_recipients s
				      ON s.id=t.source_recipient_id WHERE s.email_hmac=c.email_hmac)
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
				    AND (:keywordEmpty OR strpos(lower(p.title || ' ' || coalesce(p.abstract_text, '')), lower(:keyword)) > 0)
				    %s
				  ORDER BY %s, pac.human_verified DESC, pac.created_at DESC, pac.id
				)
				""".formatted(distinct, selectedFilter, distinct);
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
				.bind("corresponding", Boolean.TRUE.equals(criteria.corresponding()))
				.bind("keywordEmpty", criteria.paperKeyword() == null)
				.bind("keyword", value(criteria.paperKeyword()));
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

	public record ExistingSelection(UUID paperId, byte[] emailHmac, String personalizationStatus) { }
}
