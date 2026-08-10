package com.camel_hub.advertisement.campaign;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignReportingIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_reporting_test").withUsername("camel").withPassword("camel-test-only");
	private static DatabaseClient databaseClient;
	private CampaignReportingService service;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
	}

	@BeforeEach
	void setUp() {
		sql("TRUNCATE tracking_events, campaign_links, delivery_attempts, campaign_recipients, campaigns, "
				+ "segments, smtp_accounts, email_template_versions, email_templates, users CASCADE");
		seedReportingData();
		service = new CampaignReportingService(new CampaignReportingRepository(databaseClient));
	}

	@Test
	void reportsDeliveryAndSeparatesHumanFromAutomatedEngagement() {
		var deliveries = service.deliveries(1, 20).block();
		assertThat(deliveries.total()).isEqualTo(1);
		assertThat(deliveries.items()).singleElement().satisfies(item -> {
			assertThat(item.status()).isEqualTo("SMTP_ACCEPTED");
			assertThat(item.authorName()).isEqualTo("Ada Lovelace");
		});

		var campaigns = service.campaigns(1, 20).block();
		assertThat(campaigns.items()).singleElement().satisfies(item -> {
			assertThat(item.smtpAccepted()).isEqualTo(1);
			assertThat(item.humanClicks()).isEqualTo(1);
			assertThat(item.automatedClicks()).isEqualTo(1);
		});

		var links = service.links(1, 20).block();
		assertThat(links.items()).singleElement().satisfies(item -> {
			assertThat(item.humanClicks()).isEqualTo(1);
			assertThat(item.automatedClicks()).isEqualTo(1);
		});
	}

	private void seedReportingData() {
		sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'report-admin',
				        'report-admin@example.invalid', 'hash', 'Report Admin')
				""");
		sql("""
				INSERT INTO email_templates (id, name, status, created_by, updated_by)
				VALUES ('20000000-0000-0000-0000-000000000001', 'Report template', 'ACTIVE',
				        '10000000-0000-0000-0000-000000000001',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO email_template_versions
				(id, template_id, version_number, subject_template, from_name_template, reply_to,
				 html_content, text_content, content_size_bytes, created_by)
				VALUES ('21000000-0000-0000-0000-000000000001',
				        '20000000-0000-0000-0000-000000000001', 1, 'Subject', 'Team',
				        'reply@example.org', '<p>Body</p>', 'Body', 20,
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO smtp_accounts
				(id, name, host, port, tls_mode, from_email, default_from_name, reply_to,
				 per_minute_limit, per_hour_limit, per_day_limit, per_domain_hour_limit, enabled, created_by)
				VALUES ('30000000-0000-0000-0000-000000000001', 'Mailpit', 'mailpit', 1025,
				        'PLAIN_LOCAL_ONLY', 'team@example.org', 'Team', 'reply@example.org',
				        10, 100, 500, 50, true, '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO segments (id, name, created_by)
				VALUES ('40000000-0000-0000-0000-000000000001', 'All',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO campaigns
				(id, name, purpose, status, template_id, template_version_id, segment_id,
				 smtp_account_id, from_name, from_email, reply_to, created_by, updated_by)
				VALUES ('50000000-0000-0000-0000-000000000001', 'Report campaign', 'Test metrics', 'DRAFT',
				        '20000000-0000-0000-0000-000000000001',
				        '21000000-0000-0000-0000-000000000001',
				        '40000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001', 'Team', 'team@example.org',
				        'reply@example.org', '10000000-0000-0000-0000-000000000001',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO campaign_recipients
				(id, campaign_id, email_ciphertext, email_nonce, email_hmac, email_domain,
				 author_name_snapshot, paper_title_snapshot, confidence, status, personalization_status,
				 personalized_at, smtp_accepted_at)
				VALUES ('60000000-0000-0000-0000-000000000001',
				        '50000000-0000-0000-0000-000000000001', decode('01','hex'), decode('02','hex'),
				        decode('03','hex'), 'university.edu', 'Ada Lovelace', 'Safe AI', 'HIGH',
				        'SMTP_ACCEPTED', 'GENERATED', now(), now())
				""");
		sql("""
				INSERT INTO delivery_attempts
				(campaign_recipient_id, smtp_account_id, attempt_number, idempotency_key,
				 status, smtp_response_code, smtp_response_summary, started_at, completed_at)
				VALUES ('60000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001', 1, 'delivery:test',
				        'SMTP_ACCEPTED', 250, 'Accepted by internal SMTP', now(), now())
				""");
		sql("""
				INSERT INTO campaign_links
				(id, campaign_id, template_version_id, target_url, target_url_hash, label)
				VALUES ('70000000-0000-0000-0000-000000000001',
				        '50000000-0000-0000-0000-000000000001',
				        '21000000-0000-0000-0000-000000000001', 'https://arxiv.org/abs/2608.00001',
				        decode('04','hex'), 'Paper')
				""");
		sql("""
				INSERT INTO tracking_events
				(campaign_id, campaign_recipient_id, campaign_link_id, event_type, classification)
				VALUES
				('50000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001',
				 '70000000-0000-0000-0000-000000000001', 'CLICK', 'LIKELY_HUMAN'),
				('50000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001',
				 '70000000-0000-0000-0000-000000000001', 'CLICK', 'SECURITY_SCANNER')
				""");
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
