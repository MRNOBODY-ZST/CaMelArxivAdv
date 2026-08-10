package com.camel_hub.advertisement.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_segment_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static DatabaseClient databaseClient;
	private SegmentService service;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		ConnectionFactory connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
	}

	@BeforeEach
	void setUp() {
		sql("TRUNCATE segments, suppression_entries, unsubscribe_records, paper_author_contacts, "
				+ "extraction_runs, contacts, paper_authors, authors, papers, arxiv_categories, "
				+ "arxiv_archives, arxiv_groups, users CASCADE");
		seedEligibleAndExcludedContacts();
		ConnectionFactory connectionFactory = ConnectionFactories.get(r2dbcUrl());
		service = new SegmentService(
				new SegmentRepository(databaseClient, new ObjectMapper()),
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void createsListsAndPreviewsOnlySuppressionSafeRecipients() {
		var command = new SegmentService.SegmentCommand(
				"Confirmed AI correspondents", "Only safe high-confidence contacts", rules());
		var created = service.create(ACTOR, command).block();

		assertThat(created).isNotNull();
		assertThat(created.eligibleCount()).isEqualTo(1);
		assertThat(service.list(1, 20).block().items()).extracting(SegmentService.SegmentView::name)
				.containsExactly("Confirmed AI correspondents");
		var preview = service.preview(rules()).block();
		assertThat(preview.eligibleCount()).isEqualTo(1);
		assertThat(preview.sample()).singleElement().satisfies(contact -> {
			assertThat(contact.authorName()).isEqualTo("Ada Lovelace");
			assertThat(contact.paperTitle()).isEqualTo("Safe Distributed Intelligence");
			assertThat(contact.emailDomain()).isEqualTo("university.edu");
		});
	}

	private List<SegmentModels.RuleInput> rules() {
		return List.of(
				new SegmentModels.RuleInput("primaryCategory", "equals", TextNode.valueOf("cs.AI")),
				new SegmentModels.RuleInput("confidence", "equals", TextNode.valueOf("HIGH")),
				new SegmentModels.RuleInput("verificationStatus", "equals", TextNode.valueOf("CONFIRMED")),
				new SegmentModels.RuleInput("corresponding", "equals", BooleanNode.TRUE));
	}

	private void seedEligibleAndExcludedContacts() {
		sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'segment-admin',
				        'segment-admin@example.invalid', 'hash', 'Segment Admin')
				""");
		sql("""
				INSERT INTO arxiv_groups (id, group_id, group_name)
				VALUES ('20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science')
				""");
		sql("""
				INSERT INTO arxiv_categories
				(id, group_ref_id, group_id, group_name, category_id, category_name)
				VALUES ('21000000-0000-0000-0000-000000000001',
				        '20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science',
				        'cs.AI', 'Artificial Intelligence')
				""");
		sql("""
				INSERT INTO papers
				(id, arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at, pdf_url)
				VALUES ('30000000-0000-0000-0000-000000000001', '2608.00001',
				        'Safe Distributed Intelligence', 'A public abstract about distributed intelligence.',
				        '21000000-0000-0000-0000-000000000001', now() - interval '1 day', now(),
				        'https://arxiv.org/pdf/2608.00001')
				""");
		sql("""
				INSERT INTO authors (id, normalized_name, display_name)
				VALUES ('40000000-0000-0000-0000-000000000001', 'ada lovelace', 'Ada Lovelace')
				""");
		sql("""
				INSERT INTO paper_authors
				(id, paper_id, author_id, author_order, corresponding_author, raw_name, affiliation_text)
				VALUES ('41000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001',
				        '40000000-0000-0000-0000-000000000001', 1, true,
				        'Ada Lovelace', 'Analytical Engine University')
				""");
		sql("""
				INSERT INTO extraction_runs
				(id, paper_id, parser_version, status, started_at, completed_at)
				VALUES ('50000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001', '1.0', 'SUCCEEDED', now(), now())
				""");
		insertContact("60000000-0000-0000-0000-000000000001", "01", "university.edu", false, "HIGH");
		insertContact("60000000-0000-0000-0000-000000000002", "02", "example.com", true, "HIGH");
		insertContact("60000000-0000-0000-0000-000000000003", "03", "blocked.edu", false, "HIGH");
		insertContact("60000000-0000-0000-0000-000000000004", "04", "low.edu", false, "LOW");
		sql("""
				INSERT INTO suppression_entries (email_hmac, email_domain, reason, source)
				VALUES (decode('03', 'hex'), 'blocked.edu', 'MANUAL', 'TEST')
				""");
	}

	private void insertContact(String id, String hex, String domain, boolean example, String confidence) {
		sql("""
				INSERT INTO contacts
				(id, email_ciphertext, email_nonce, email_hmac, email_domain, display_ciphertext,
				 display_nonce, syntax_valid, example_address)
				VALUES ('%s', decode('%s', 'hex'), decode('11', 'hex'), decode('%s', 'hex'),
				        '%s', decode('22', 'hex'), decode('33', 'hex'), true, %s)
				""".formatted(id, hex, hex, domain, example));
		sql("""
				INSERT INTO paper_author_contacts
				(paper_author_id, paper_id, contact_id, extraction_run_id, confidence,
				 corresponding_author, human_verified, verification_status)
				VALUES ('41000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001', '%s',
				        '50000000-0000-0000-0000-000000000001', '%s', true, true, 'CONFIRMED')
				""".formatted(id, confidence));
	}

	private void sql(String statement) {
		databaseClient.sql(statement).fetch().rowsUpdated().block();
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
