package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_campaign_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID TEMPLATE = UUID.fromString("70000000-0000-0000-0000-000000000001");
	private static final UUID SEGMENT = UUID.fromString("71000000-0000-0000-0000-000000000001");
	private static final UUID SMTP = UUID.fromString("72000000-0000-0000-0000-000000000001");
	private static DatabaseClient databaseClient;
	private CampaignService service;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
	}

	@BeforeEach
	void setUp() {
		sql("TRUNCATE mailbox_inbound_events, mailbox_sync_cursors, campaign_safety_events, "
				+ "campaign_safety_links, campaign_safety_attempts, campaign_safety_messages, campaign_safety_runs, "
				+ "recipient_delivery_cooldowns, outbox_messages, campaign_exclusions, campaign_recipients, "
				+ "campaigns, segments, suppression_entries, unsubscribe_records, extraction_evidence, "
				+ "paper_author_contacts, extraction_runs, contacts, paper_authors, authors, "
				+ "papers, arxiv_categories, arxiv_archives, arxiv_groups, mailbox_accounts, smtp_accounts, "
				+ "email_template_versions, email_templates, users CASCADE");
		seedCampaignDependencies();
		ConnectionFactory connectionFactory = ConnectionFactories.get(r2dbcUrl());
		TransactionalOperator transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		service = new CampaignService(
				new CampaignRepository(databaseClient), new SegmentRepository(databaseClient, objectMapper),
				new PersonalizationProperties(true, "openai", "gpt-test", 100), objectMapper, transactions);
	}

	@Test
	void createsADraftAndQueuesOnePrivacySafePersonalizationJob() {
		var created = service.create(ACTOR, command()).block();
		var started = service.startPersonalization(
				created.id(), new AuthenticationRequestContext("127.0.0.1", "JUnit", "campaigntrace1")).block();

		assertThat(started).isNotNull();
		assertThat(started.queuedRecipients()).isEqualTo(1);
		var updated = service.get(created.id()).block();
		assertThat(updated.generationStatus()).isEqualTo("QUEUED");
		assertThat(updated.templateVersion()).isEqualTo(1);
		assertThat(updated.lockVersion()).isZero();
		assertThat(updated.mailboxAccountId()).isNull();
		assertThat(updated.deliveryCounts().queued()).isEqualTo(1);
		assertThat(updated.recipientCounts().queued()).isEqualTo(1);
		assertThat(service.recipients(created.id(), 1, 20).block().items()).singleElement()
				.satisfies(recipient -> {
					assertThat(recipient.authorName()).isEqualTo("Ada Lovelace");
					assertThat(recipient.paperTitle()).isEqualTo("Safe Distributed Intelligence");
					assertThat(recipient.personalizationStatus()).isEqualTo("QUEUED");
				});
		String payload = text("SELECT CAST(payload AS text) FROM outbox_messages");
		assertThat(payload).contains("Safe Distributed Intelligence", "Ada Lovelace", "campaigntrace1")
				.doesNotContain("university.edu", "emailCiphertext", "emailNonce", "emailHmac", "recipientEmail");
	}

	@Test
	void rejectsGenerationWhenTheProviderIsDisabled() {
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		ConnectionFactory connectionFactory = ConnectionFactories.get(r2dbcUrl());
		var disabled = new CampaignService(
				new CampaignRepository(databaseClient), new SegmentRepository(databaseClient, objectMapper),
				new PersonalizationProperties(false, "openai", "gpt-test", 100), objectMapper,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
		var created = disabled.create(ACTOR, command()).block();

		assertThatThrownBy(() -> disabled.startPersonalization(
				created.id(), new AuthenticationRequestContext("127.0.0.1", "JUnit", "campaigntrace2")).block())
				.isInstanceOf(PersonalizationUnavailableException.class);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isZero();
	}

	private CampaignService.CampaignCommand command() {
		return new CampaignService.CampaignCommand(
				"Paper-aware outreach", "Invite the author to discuss safe distributed AI.",
				TEMPLATE, SEGMENT, SMTP);
	}

	private void seedCampaignDependencies() {
		sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'campaign-admin',
				        'campaign-admin@example.invalid', 'hash', 'Campaign Admin')
				""");
		sql("""
				INSERT INTO email_templates (id, name, description, status, created_by, updated_by)
				VALUES ('70000000-0000-0000-0000-000000000001', 'Paper outreach', 'Safe style',
				        'ACTIVE', '10000000-0000-0000-0000-000000000001',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO email_template_versions
				(id, template_id, version_number, subject_template, from_name_template, reply_to,
				 html_content, text_content, content_size_bytes, validation_result, created_by)
				VALUES ('70100000-0000-0000-0000-000000000001',
				        '70000000-0000-0000-0000-000000000001', 1, 'About {{paper_title}}',
				        'Research Team', 'reply@example.org',
				        '<p>Hello {{author_name}}</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
				        'Hello {{author_name}} {{unsubscribe_url}}', 120, '{"valid":true}',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO smtp_accounts
				(id, name, host, port, tls_mode, from_email, default_from_name, reply_to,
				 per_minute_limit, per_hour_limit, per_day_limit, per_domain_hour_limit, enabled,
				 last_tested_at, last_test_status, created_by)
				VALUES ('72000000-0000-0000-0000-000000000001', 'Internal Mailpit', 'mailpit', 1025,
				        'PLAIN_LOCAL_ONLY', 'research@example.org', 'Research Team', 'reply@example.org',
				        10, 100, 500, 50, true, now(), 'SUCCEEDED',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO segments (id, name, description, created_by)
				VALUES ('71000000-0000-0000-0000-000000000001', 'Confirmed AI', 'Safe contacts',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO segment_rules (segment_id, rule_order, field_name, operator, value_data)
				VALUES
				('71000000-0000-0000-0000-000000000001', 1, 'primaryCategory', 'equals', '"cs.AI"'),
				('71000000-0000-0000-0000-000000000001', 2, 'confidence', 'equals', '"HIGH"'),
				('71000000-0000-0000-0000-000000000001', 3, 'verificationStatus', 'equals', '"CONFIRMED"'),
				('71000000-0000-0000-0000-000000000001', 4, 'corresponding', 'equals', 'true')
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

	private String text(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private long longValue(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
