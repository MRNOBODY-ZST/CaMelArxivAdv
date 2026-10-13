package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutreachTemplateCampaignIntegrationTest {
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_outreach_template_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID TEMPLATE = UUID.fromString("70000000-0000-0000-0000-000000000001");
	private static final UUID SMTP = UUID.fromString("72000000-0000-0000-0000-000000000001");
	private static final UUID MAILBOX = UUID.fromString("73000000-0000-0000-0000-000000000001");
	private static final UUID PAPER = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final String SCHEMA = "outreach_template_" + UUID.randomUUID().toString().replace("-", "");
	private static DatabaseClient db;
	private static ConnectionFactory connectionFactory;
	private OutreachTemplateCampaignService service;
	private TransactionalOperator transactions;

	@BeforeAll
	static void database() {
		String jdbc = System.getenv("OUTREACH_TEST_JDBC_URL");
		String username, password;
		if (jdbc == null || jdbc.isBlank()) {
			POSTGRES.start();
			jdbc = POSTGRES.getJdbcUrl();
			username = POSTGRES.getUsername();
			password = POSTGRES.getPassword();
		} else {
			username = System.getenv().getOrDefault("OUTREACH_TEST_DB_USERNAME", "outreach_test");
			password = System.getenv().getOrDefault("OUTREACH_TEST_DB_PASSWORD", "outreach_test_only");
		}
		Flyway.configure().dataSource(jdbc, username, password)
				.schemas(SCHEMA).defaultSchema(SCHEMA)
				.locations("classpath:db/migration").load().migrate();
		connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.parse("r2dbc:" + jdbc.substring("jdbc:".length()))
				.mutate().option(ConnectionFactoryOptions.USER, username).option(ConnectionFactoryOptions.PASSWORD, password)
				.option(Option.valueOf("schema"), SCHEMA).build());
		db = DatabaseClient.create(connectionFactory);
		assertPrivateSchema();
		Long initialOutbox = db.sql("SELECT count(*) FROM outbox_messages")
				.map((r,m) -> r.get(0, Long.class)).one().block();
		assertThat(initialOutbox).isZero();
		System.out.println("Outreach template fixture: schema=" + SCHEMA + ", initialOutbox=" + initialOutbox);
	}

	@BeforeEach
	void setup() {
		assertPrivateSchema();
		sql("TRUNCATE campaigns, segments, contacts, papers, mailbox_accounts, smtp_accounts, email_templates, users CASCADE");
		sql("INSERT INTO users(id,username,email,password_hash,display_name) VALUES ('" + ACTOR + "','test','test@example.test','test-only','Test')");
		sql("INSERT INTO email_templates(id,name,status) VALUES ('" + TEMPLATE + "','Neutral source template','ACTIVE')");
		sql("INSERT INTO email_template_versions(template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes) VALUES ('" + TEMPLATE + "',1,'Invitation: {{paper_title}}','Camel Hub','sender@example.test','<p>Hello {{author_name}}</p><p>{{paper_title}}</p><a href=\"{{paper_url}}\" rel=\"noopener noreferrer\">Paper</a><a href=\"{{unsubscribe_url}}\" rel=\"noopener noreferrer\">Unsubscribe</a>','Hello {{first_name}} {{paper_title}} {{paper_url}} {{unsubscribe_url}}',1000)");
		sql("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,last_test_status,last_tested_at) VALUES ('" + SMTP + "','Test SMTP','smtp.example.test',465,'TLS_IMPLICIT','sender@example.test','Camel Hub','sender@example.test',10,100,120,100,true,'SUCCEEDED',now()+interval '1 minute')");
		sql("INSERT INTO mailbox_accounts(id,name,protocol,host,port,tls_mode,username,password_ciphertext,password_nonce,enabled,last_test_status,last_tested_at) VALUES ('" + MAILBOX + "','Test mailbox','IMAP','imap.example.test',993,'TLS_IMPLICIT','sender@example.test',decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),true,'SUCCEEDED',now()+interval '1 minute')");
		sql("INSERT INTO papers(id,arxiv_id,title,abstract_text,submitted_at,updated_at,pdf_url) VALUES ('" + PAPER + "','2601.00001','Source paper','Source abstract',now(),now(),'https://arxiv.org/pdf/2601.00001')");
		transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
		var mapper = new ObjectMapper().findAndRegisterModules();
		var repo = new CampaignRepository(db);
		var segments = new SegmentRepository(db, mapper);
		var campaignService = new CampaignService(repo, segments, new PersonalizationProperties(false, "unused", "unused", 120), mapper, transactions);
		var delivery = new CampaignDeliveryProperties(false, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		var preflight = new CampaignPreflightService(new CampaignWorkflowRepository(db), delivery, new MailTrackingProperties(true, "https://tracking.example.test", Base64.getEncoder().encodeToString(new byte[32]), Duration.ofDays(30)), mapper);
		var hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(anyString())).thenReturn(new byte[32]);
		var workflow = new CampaignWorkflowService(new CampaignWorkflowRepository(db), preflight, campaignService, new AuditService(db, mapper), hasher, mapper, transactions);
		var crypto = new ContactCrypto(new ContactDataProtectionProperties(Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1})));
		service = new OutreachTemplateCampaignService(repo, segments, campaignService, workflow, new OutreachTemplateCampaignRepository(db), new CampaignRecipientContentRepository(db), new OutreachTemplateContentPolicy(new TemplateEngine(102_400)), new CampaignProductionSelectionPolicy(crypto, new CampaignSafetyProperties(false, null, 20, Map.of()), Duration.ofDays(180)), transactions);
	}

	@Test
	void freshConnectionsAndTransactionsUseOnlyThePrivateFixtureSchema() {
		var probes = Flux.range(0, 16).flatMap(ignored -> transactions.transactional(db.sql("""
				SELECT current_schema() AS schema, current_setting('search_path') AS search_path,
					EXISTS(SELECT 1 FROM users WHERE id=:actor) AS private_fixture
				""").bind("actor", ACTOR).map((r,m) -> Map.of(
				"schema", r.get("schema", String.class), "search_path", r.get("search_path", String.class),
				"private_fixture", r.get("private_fixture", Boolean.class))).one()))
				.collectList().block(Duration.ofSeconds(10));
		assertThat(probes).hasSize(16).allSatisfy(probe -> assertThat(probe)
				.containsEntry("schema", SCHEMA).containsEntry("search_path", SCHEMA).containsEntry("private_fixture", true));
	}

	private static void assertPrivateSchema() {
		var probe = db.sql("SELECT current_schema() AS schema, current_setting('search_path') AS search_path")
				.map((r,m) -> Map.of("schema", r.get("schema", String.class),
						"search_path", r.get("search_path", String.class))).one().block();
		assertThat(probe).containsEntry("schema", SCHEMA).containsEntry("search_path", SCHEMA);
	}

	@Test
	void exactHundredIsFrozenGeneratedAndReviewedWithoutAModelOrOutbox() {
		contacts(121);
		assertThat(service.eligibleCount(List.of(PAPER)).block()).isEqualTo(121);
		var campaign = prepare(100);
		assertThat(campaign.recipientCounts().generated()).isEqualTo(100);
		assertThat(campaign.generationStatus()).isEqualTo("COMPLETED");
		assertThat(campaign.generationProvider()).isEqualTo("TEMPLATE");
		assertThat(campaign.generationModel()).isEqualTo("exact-v1");
		assertThat(campaign.mailboxAccountId()).isEqualTo(MAILBOX);
		assertThat(count("outbox_messages")).isZero();
		assertThat(count("outreach_content_reviews")).isEqualTo(100);
		assertThat(text("SELECT rendered_html FROM campaign_recipients LIMIT 1")).contains("Hello research team", "{{unsubscribe_url}}").doesNotContain("placeholder.invalid");
		service.review(campaign.id()).block();
	}

	@Test
	void insufficientCandidatesRollBackTheEntireCampaignAndSegment() {
		contacts(1);
		assertThatThrownBy(() -> prepare(2)).isInstanceOf(CampaignValidationException.class);
		assertThat(count("campaigns")).isZero();
		assertThat(count("segments")).isZero();
		assertThat(count("outreach_content_reviews")).isZero();
	}

	@Test
	void paperModeOverflowRollsBackWithoutTruncating() {
		contacts(121);
		assertThatThrownBy(() -> prepare(null)).isInstanceOf(CampaignValidationException.class);
		assertThat(count("campaigns")).isZero();
	}

	@Test
	void nestedAdapterJoinsTheRunTransactionAndRollsBackWithIt() {
		contacts(1);
		assertThatThrownBy(() -> transactions.transactional(service.prepare(ACTOR, UUID.randomUUID(), TEMPLATE, SMTP, MAILBOX, "Research invitation", List.of(PAPER), 1)
				.then(Mono.error(new IllegalStateException("outer transaction failed")))).block()).isInstanceOf(IllegalStateException.class);
		assertThat(count("campaigns")).isZero();
		assertThat(count("segments")).isZero();
		assertThat(count("outreach_content_reviews")).isZero();
	}

	@Test
	void editsToAnyContentFieldOrFrozenSourceInvalidateReviewWithoutUpdatingHashes() {
		contacts(1);
		var campaign = prepare(1);
		String originalHash = text("SELECT encode(content_hash,'hex') FROM outreach_content_reviews");
		for (String field : List.of("rendered_subject", "rendered_html", "rendered_text", "personalization_rationale", "paper_title_snapshot")) {
			String original = text("SELECT " + field + " FROM campaign_recipients");
			db.sql("UPDATE campaign_recipients SET " + field + "=:value").bind("value", original + " changed").fetch().rowsUpdated().block();
			assertThatThrownBy(() -> service.review(campaign.id()).block()).isInstanceOf(CampaignValidationException.class);
			assertThat(text("SELECT encode(content_hash,'hex') FROM outreach_content_reviews")).isEqualTo(originalHash);
			db.sql("UPDATE campaign_recipients SET " + field + "=:value").bind("value", original).fetch().rowsUpdated().block();
		}
	}

	@Test
	void reviewSupportsPreSendWorkflowStatesButNeverStartedOrAttemptedRecipients() {
		contacts(1);
		var campaign = prepare(1);
		for (String status : List.of("DRAFT", "READY_FOR_REVIEW", "APPROVED")) {
			sql("UPDATE campaigns SET status='" + status + "'");
			service.review(campaign.id()).block();
		}
		sql("UPDATE campaigns SET status='RUNNING'");
		assertThatThrownBy(() -> service.review(campaign.id()).block()).isInstanceOf(CampaignValidationException.class);
		sql("UPDATE campaigns SET status='DRAFT'");
		sql("UPDATE campaign_recipients SET attempt_count=1");
		assertThatThrownBy(() -> service.review(campaign.id()).block()).isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void noTwoPaperContactCampaignsCanFreezeTheSamePendingAddress() {
		contacts(1);
		prepare(1);
		assertThatThrownBy(() -> prepare(1)).isInstanceOf(CampaignValidationException.class);
		assertThat(count("campaigns")).isEqualTo(1);
		assertThat(count("campaign_recipients")).isEqualTo(1);
	}

	@Test
	void autoMatchesTheMonitoredReplyToMailboxWhenFromIsDifferent() {
		contacts(1);
		sql("UPDATE smtp_accounts SET from_email='outbound@example.test',reply_to='sender@example.test'");
		var campaign = service.prepare(ACTOR, UUID.randomUUID(), TEMPLATE, SMTP, null, "Research invitation", List.of(PAPER), 1).block();
		assertThat(campaign.fromEmail()).isEqualTo("outbound@example.test");
		assertThat(campaign.replyTo()).isEqualTo("sender@example.test");
		assertThat(campaign.mailboxAccountId()).isEqualTo(MAILBOX);
		service.review(campaign.id()).block();
	}

	private CampaignService.CampaignView prepare(Integer count) {
		return service.prepare(ACTOR, UUID.randomUUID(), TEMPLATE, SMTP, MAILBOX, "Research invitation", List.of(PAPER), count).block();
	}

	private void contacts(int count) {
		UUID run = UUID.randomUUID();
		db.sql("""
				INSERT INTO contacts(email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid,example_address,suppression_status)
				SELECT decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),decode(lpad(to_hex(i),64,'0'),'hex'),
				'example.test',decode(repeat('01',16),'hex'),true,false,'ACTIVE' FROM generate_series(1,:count) AS i
				""").bind("count", count).fetch().rowsUpdated().block();
		sql("INSERT INTO extraction_runs(id,paper_id,parser_version,status,started_at,completed_at) VALUES ('" + run + "','" + PAPER + "','test','SUCCEEDED',now(),now())");
		sql("INSERT INTO paper_author_contacts(paper_id,contact_id,extraction_run_id,confidence,human_verified,verification_status) SELECT '" + PAPER + "',id,'" + run + "','LOW',false,'UNVERIFIED' FROM contacts");
		sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) SELECT id,'source.tex','source-email','masked source' FROM paper_author_contacts");
	}

	private void sql(String sql) { db.sql(sql).fetch().rowsUpdated().block(); }
	private long count(String table) { return db.sql("SELECT count(*) AS n FROM " + table).map((r,m) -> r.get("n", Long.class)).one().block(); }
	private String text(String sql) { return db.sql(sql).map((r,m) -> r.get(0, String.class)).one().block(); }
}
