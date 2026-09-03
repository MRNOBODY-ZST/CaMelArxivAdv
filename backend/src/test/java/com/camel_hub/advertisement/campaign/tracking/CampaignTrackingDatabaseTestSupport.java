package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import com.camel_hub.advertisement.email.smtp.SmtpModels;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

abstract class CampaignTrackingDatabaseTestSupport {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("campaign_tracking_test").withUsername("camel").withPassword("camel-test-only");
	static final Instant NOW = Instant.parse("2031-02-03T04:05:06Z");
	static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	static final UUID TEMPLATE = UUID.fromString("70000000-0000-0000-0000-000000000001");
	static final UUID TEMPLATE_VERSION = UUID.fromString("70100000-0000-0000-0000-000000000001");
	static final UUID SMTP = UUID.fromString("72000000-0000-0000-0000-000000000001");
	static final String TRACKING_KEY = Base64.getEncoder().encodeToString(
			"campaign-tracking-test-key-32bytes".getBytes(StandardCharsets.UTF_8));
	static final MailTrackingProperties TRACKING_PROPERTIES = new MailTrackingProperties(
			true, "https://tracking.example.test", TRACKING_KEY, Duration.ofDays(30));
	static final byte[] LEASE = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

	static DatabaseClient database;
	static TransactionalOperator transactions;
	static ConnectionFactory connectionFactory;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		ConnectionFactory factory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
				+ POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName());
		connectionFactory = factory;
		database = DatabaseClient.create(factory);
		transactions = TransactionalOperator.create(new R2dbcTransactionManager(factory));
	}

	@BeforeEach
	void resetDatabase() {
		sql("""
				TRUNCATE audit_logs, campaign_safety_events, campaign_safety_links,
				         campaign_safety_attempts, campaign_safety_messages, campaign_safety_runs,
				         tracking_events, tracking_tokens, campaign_links, delivery_attempts,
				         recipient_delivery_cooldowns, unsubscribe_records, suppression_entries,
				         campaign_recipients, campaigns, smtp_accounts,
				         email_template_versions, email_templates, users CASCADE
				""");
		seedDependencies();
	}

	CampaignTrackingService service(Clock clock) {
		return new CampaignTrackingService(new CampaignTrackingRepository(database), TRACKING_PROPERTIES,
				new CampaignTrackingSigner(TRACKING_KEY), new MailOpenClassifier(), clock, transactions,
				value -> CampaignTrackingSigner.sha256(value));
	}

	CampaignDeliveryRepository.ProductionClaim insertClaim(String html, String text) {
		UUID campaign = UUID.randomUUID();
		UUID recipient = UUID.randomUUID();
		UUID attempt = UUID.randomUUID();
		byte[] hmac = sha256("author@example.org");
		database.sql("""
				INSERT INTO campaigns (
				    id, name, purpose, status, template_id, template_version_id, smtp_account_id,
				    from_name, from_email, reply_to, tracking_opens_enabled,
				    tracking_clicks_enabled, unsubscribe_enabled, created_by, updated_by
				) VALUES (
				    :campaign, 'Tracking campaign', 'Research outreach', 'RUNNING', :template, :version, :smtp,
				    'Research Team', 'sender@example.org', 'reply@example.org', true, true, true, :actor, :actor
				)
				""").bind("campaign", campaign).bind("template", TEMPLATE).bind("version", TEMPLATE_VERSION)
				.bind("smtp", SMTP).bind("actor", ACTOR).fetch().rowsUpdated().block();
		database.sql("""
				INSERT INTO campaign_recipients (
				    id, campaign_id, email_ciphertext, email_nonce, email_hmac, email_domain,
			    confidence, status, personalization_status, rendered_subject, rendered_html,
			    rendered_text, personalized_at, next_attempt_at, attempt_count, delivery_lease_hash,
				    delivery_lease_expires_at, rfc_message_id
				) VALUES (
				    :recipient, :campaign, decode('aa','hex'), decode('bb','hex'), :hmac, 'example.org',
			    'HIGH', 'CONNECTING', 'GENERATED', 'Personal research note', :html, :text,
			    :now, :now, 1, :lease, :expires, :messageId
				)
				""").bind("recipient", recipient).bind("campaign", campaign).bind("hmac", hmac)
				.bind("html", html).bind("text", text).bind("now", NOW)
				.bind("lease", sha256(LEASE)).bind("expires", NOW.plusSeconds(120))
				.bind("messageId", "<" + recipient + "@delivery.camel-arxiv.invalid>")
				.fetch().rowsUpdated().block();
		database.sql("""
				INSERT INTO delivery_attempts (
				    id, campaign_recipient_id, smtp_account_id, attempt_number, idempotency_key,
				    status, rfc_message_id, started_at
				) VALUES (:attempt, :recipient, :smtp, 1, :key, 'CONNECTING', :messageId, :now)
				""").bind("attempt", attempt).bind("recipient", recipient).bind("smtp", SMTP)
				.bind("key", "delivery:" + recipient + ":1")
				.bind("messageId", "<" + recipient + "@delivery.camel-arxiv.invalid>")
				.bind("now", NOW).fetch().rowsUpdated().block();

		return new CampaignDeliveryRepository.ProductionClaim(
				recipient, campaign, attempt, 1, "delivery:" + recipient + ":1",
				"<" + recipient + "@delivery.camel-arxiv.invalid>", "delivery-" + recipient, LEASE,
				new byte[] {(byte) 0xaa}, new byte[] {(byte) 0xbb}, hmac, "example.org", account(),
				TEMPLATE_VERSION, "Research Team", "sender@example.org", "reply@example.org",
				true, true, true, "Personal research note", html, text);
	}

	CampaignDeliveryRepository.ProductionClaim preparedClaim(CampaignTrackingService service) {
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<p>Personalized note</p><a href=\"https://papers.example.org/abs/42\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
				"Personalized note\nUnsubscribe: {{unsubscribe_url}}");
		service.prepare(claim).block();
		sql("UPDATE campaign_recipients SET status = 'SMTP_ACCEPTED', smtp_accepted_at = TIMESTAMPTZ '"
				+ NOW + "', delivery_lease_hash = NULL, delivery_lease_expires_at = NULL WHERE id = '"
				+ claim.recipientId() + "'");
		sql("UPDATE delivery_attempts SET status = 'SMTP_ACCEPTED', completed_at = TIMESTAMPTZ '" + NOW
				+ "' WHERE id = '" + claim.attemptId() + "'");
		return claim;
	}

	String text(String statement) {
		Optional<String> value = database.sql(statement)
				.map((row, metadata) -> Optional.ofNullable(row.get(0, String.class)))
				.one().block();
		return value == null ? null : value.orElse(null);
	}

	long count(String table) {
		return database.sql("SELECT count(*) FROM " + table)
				.map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	long longValue(String statement) {
		return database.sql(statement).map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	void sql(String statement) {
		database.sql(statement).fetch().rowsUpdated().block();
	}

	byte[] sha256(String value) {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void seedDependencies() {
		sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'tracking-user',
				        'tracking-user@example.invalid', 'hash', 'Tracking User')
				""");
		sql("""
				INSERT INTO smtp_accounts (
				    id, name, host, port, tls_mode, from_email, default_from_name, reply_to,
				    per_minute_limit, per_hour_limit, per_day_limit, per_domain_hour_limit, enabled, created_by
				) VALUES (
				    '72000000-0000-0000-0000-000000000001', 'Tracking SMTP', 'localhost', 1025,
				    'PLAIN_LOCAL_ONLY', 'sender@example.org', 'Research Team', 'reply@example.org',
				    2, 10, 30, 10, true, '10000000-0000-0000-0000-000000000001'
				)
				""");
		sql("""
				INSERT INTO email_templates (id, name, current_version, status, created_by, updated_by)
				VALUES ('70000000-0000-0000-0000-000000000001', 'Tracking template', 1, 'ACTIVE',
				        '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO email_template_versions (
				    id, template_id, version_number, subject_template, from_name_template,
				    reply_to, html_content, text_content, content_size_bytes, created_by
				) VALUES (
				    '70100000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001',
				    1, 'Subject', 'Research Team', 'reply@example.org', '<p>body</p>', 'body', 11,
				    '10000000-0000-0000-0000-000000000001'
				)
				""");
	}

	private SmtpRepository.SmtpAccountRecord account() {
		return new SmtpRepository.SmtpAccountRecord(
				SMTP, "Tracking SMTP", "localhost", 1025, SmtpModels.TlsMode.PLAIN_LOCAL_ONLY,
				null, null, null, "sender@example.org", "Research Team", "reply@example.org",
				2, 10, 30, 10, true, NOW, "SUCCEEDED", null, 0, ACTOR, NOW, NOW);
	}
}
