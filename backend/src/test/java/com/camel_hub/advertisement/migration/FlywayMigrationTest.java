package com.camel_hub.advertisement.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayMigrationTest {

	private static final Set<String> REQUIRED_PERMISSIONS = Set.of(
			"user:read", "user:create", "user:update", "user:disable",
			"role:read", "role:manage",
			"paper:read", "paper:import", "paper:delete",
			"contact:read_masked", "contact:read_full", "contact:verify", "contact:export",
			"job:manage",
				"template:read", "template:manage",
				"smtp:read", "smtp:manage",
				"mailbox:read", "mailbox:manage",
			"campaign:read", "campaign:create", "campaign:approve", "campaign:send",
			"campaign:pause",
			"analytics:read", "audit:read", "system:manage");

	private static final Set<String> REQUIRED_ROLES = Set.of(
			"SUPER_ADMIN", "ADMIN", "CAMPAIGN_MANAGER", "DATA_ANALYST", "VIEWER");

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_migration_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	@Test
	void migratesEmptyDatabaseAndCreatesCriticalConstraints() throws SQLException {
		Flyway flyway = flyway();

		assertThat(flyway.migrate().success).isTrue();
		assertThat(tableNames()).contains(
				"users",
				"arxiv_category_snapshots",
				"arxiv_categories",
				"arxiv_sync_cursors",
				"papers",
				"paper_imports",
				"contacts",
					"jobs",
					"smtp_accounts",
					"mailbox_accounts",
				"email_templates",
				"email_template_versions",
				"template_assets",
				"campaigns",
				"tracking_events",
				"mail_send_records",
				"mail_open_events",
				"mail_click_links",
				"mail_click_events",
				"audit_logs");
			assertThat(constraintNames()).contains(
				"uk_papers_arxiv_id",
				"uk_campaign_recipient",
				"uk_jobs_idempotency_key",
				"uk_contacts_email_hmac",
				"uk_email_template_version",
				"uk_template_assets_object_key",
					"uk_tracking_token",
					"uk_mail_click_target",
					"uk_mail_click_token",
					"uk_mail_click_minute");
			assertThat(columnNames("outbox_messages")).contains("topic_name").doesNotContain("exchange_name");
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(canInsertGlobalAggregateRows()).isTrue();
	}

	@Test
	void seedsPromptRolesAndPermissionsWithoutAPlaintextAdministrator() throws SQLException {
		assertThat(flyway().migrate().success).isTrue();

		assertThat(queryNames("SELECT code FROM permissions"))
				.containsExactlyInAnyOrderElementsOf(REQUIRED_PERMISSIONS);
		assertThat(queryNames("SELECT code FROM roles WHERE system_role = true"))
				.containsExactlyInAnyOrderElementsOf(REQUIRED_ROLES);
		assertThat(queryLong("""
				SELECT count(*)
				FROM role_permissions rp
				JOIN roles r ON r.id = rp.role_id
				WHERE r.code = 'SUPER_ADMIN'
				""")).isEqualTo(REQUIRED_PERMISSIONS.size());
		assertThat(queryLong("""
				SELECT count(*)
				FROM role_permissions rp
				JOIN roles r ON r.id = rp.role_id
				JOIN permissions p ON p.id = rp.permission_id
				WHERE r.code = 'VIEWER' AND p.code = 'contact:read_full'
				""")).isZero();
		assertThat(queryLong("SELECT count(*) FROM users")).isZero();
	}

	@Test
	void addsCampaignPersonalizationStateWithConstrainedPollingIndexes() throws SQLException {
		assertThat(flyway().migrate().success).isTrue();

		assertThat(columnNames("campaigns")).contains(
				"generation_status", "generation_provider", "generation_model", "generation_job_id",
				"generation_requested_at", "generation_completed_at", "generation_error_summary");
		assertThat(columnNames("campaign_recipients")).contains(
				"personalization_status", "personalization_rationale", "personalization_error_code",
				"personalization_error_message", "personalization_attempts", "personalized_at");
		assertThat(constraintNames()).contains(
				"ck_campaign_generation_status", "ck_campaign_recipient_personalization_status",
				"ck_campaign_recipient_personalization_attempts");
		assertThat(indexNames()).contains(
				"ix_campaigns_generation_status", "ix_campaign_recipients_personalization_status");
	}

	@Test
	void addsDurableCampaignDeliveryAndSafetyIsolationSchema() throws SQLException {
		assertThat(flyway().migrate().success).isTrue();

		assertThat(tableNames()).contains(
				"recipient_delivery_cooldowns", "campaign_safety_runs", "campaign_safety_messages",
				"campaign_safety_attempts", "campaign_safety_links", "campaign_safety_events",
				"mailbox_sync_cursors", "mailbox_inbound_events");

		assertV16Columns();
		assertV16ConstraintDefinitions();
		assertV16ForeignKeys();
		assertV16IndexDefinitions();
	}

	@Test
	void hardensCampaignSafetyCallbackAndInboundIdentitySchema() throws SQLException {
		assertThat(flyway().migrate().success).isTrue();

		assertColumn("tracking_tokens", "token_type", "character varying", 20, false);
		assertColumn("campaign_safety_links", "target_url", "character varying", 2048, true);
		assertColumn("campaign_safety_links", "target_url_hash", "bytea", null, true);
		assertColumn("campaign_safety_events", "fingerprint_hash", "bytea", null, true);
		assertColumn("campaign_safety_events", "minute_bucket", "bigint", null, true);
		assertColumn("campaign_safety_runs", "destination_hmac", "bytea", null, false);
		assertColumn("campaign_safety_runs", "destination_masked", "character varying", 320, false);

		Map<String, String> constraints = constraintDefinitions();
		assertDefinitions(constraints, Map.ofEntries(
				Map.entry("ck_campaign_safety_link_target_pairing", List.of("token_type", "CLICK", "OPEN", "UNSUBSCRIBE", "target_url IS NULL", "target_url_hash IS NULL", "target_url IS NOT NULL", "target_url_hash IS NOT NULL")),
				Map.entry("ck_campaign_safety_link_target_hash", List.of("target_url_hash IS NULL", "octet_length(target_url_hash) = 32")),
				Map.entry("ck_campaign_safety_link_target_scheme", List.of("target_url IS NULL", "https?://")),
				Map.entry("ck_campaign_safety_event_callback_fields", List.of("OPEN", "CLICK", "UNSUBSCRIBE", "REPLY", "AUTO_REPLY", "BOUNCE", "safety_link_id IS NOT NULL", "fingerprint_hash IS NOT NULL", "octet_length(fingerprint_hash) = 32", "minute_bucket IS NOT NULL", "classification IS NOT NULL", "classification_reason IS NULL", "safety_link_id IS NULL", "fingerprint_hash IS NULL", "minute_bucket IS NULL", "classification IS NULL")),
				Map.entry("ck_campaign_safety_run_destination_hmac", List.of("octet_length(destination_hmac) = 32"))));
		assertExactSqlStringSets(constraints, Map.ofEntries(
				Map.entry("ck_campaign_safety_link_target_pairing", List.of("CLICK", "OPEN", "UNSUBSCRIBE")),
				Map.entry("ck_campaign_safety_event_callback_fields", List.of("OPEN", "CLICK", "UNSUBSCRIBE", "REPLY", "AUTO_REPLY", "BOUNCE", "UNCLASSIFIED", "LIKELY_HUMAN", "BOT", "PREFETCH", "SECURITY_SCANNER"))));

		Map<String, String> indexes = indexDefinitions();
		assertDefinitions(indexes, Map.ofEntries(
				Map.entry("ix_campaign_recipients_rfc_message_id", List.of("CREATE UNIQUE INDEX", "(rfc_message_id)", "WHERE (rfc_message_id IS NOT NULL)")),
				Map.entry("ix_campaign_safety_messages_rfc_message_id", List.of("CREATE UNIQUE INDEX", "(rfc_message_id)", "WHERE (rfc_message_id IS NOT NULL)")),
				Map.entry("uk_campaign_safety_open_token", List.of("CREATE UNIQUE INDEX", "(safety_message_id)", "WHERE ((token_type)::text = 'OPEN'::text)")),
				Map.entry("uk_campaign_safety_unsubscribe_token", List.of("CREATE UNIQUE INDEX", "(safety_message_id)", "WHERE ((token_type)::text = 'UNSUBSCRIBE'::text)")),
				Map.entry("uk_campaign_safety_event_callback_minute", List.of("CREATE UNIQUE INDEX", "(safety_link_id, fingerprint_hash, minute_bucket)", "WHERE ((event_type)::text = ANY"))));
		assertExactSqlStringSets(indexes, Map.of(
				"uk_campaign_safety_open_token", List.of("OPEN"),
				"uk_campaign_safety_unsubscribe_token", List.of("UNSUBSCRIBE"),
				"uk_campaign_safety_event_callback_minute", List.of("OPEN", "CLICK", "UNSUBSCRIBE")));

		SafetyFixture fixture = createSafetyFixture();
		UUID openLink = insertSafetyLink(fixture, "OPEN", null, null, "1");
		UUID clickLink = insertSafetyLink(fixture, "CLICK", "https://example.test/safety", "2", "3");
		UUID unsubscribeLink = insertSafetyLink(fixture, "UNSUBSCRIBE", null, null, "4");
		assertThat(openLink).isNotEqualTo(clickLink).isNotEqualTo(unsubscribeLink);
		assertThatThrownBy(() -> insertSafetyLink(fixture, "OPEN", null, null, "5")).isInstanceOf(SQLException.class);
		SafetyFixture invalidPairingFixture = fixture.withSafetyMessageId(newSafetyMessage(fixture));
		assertThatThrownBy(() -> insertSafetyLink(invalidPairingFixture, "OPEN", "https://example.test/invalid", "6", "7"))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> insertSafetyLink(fixture, "CLICK", "https://example.test/invalid", null, "8")).isInstanceOf(SQLException.class);

		insertSafetyCallbackEvent(fixture, openLink, "OPEN", "9", 7, "LIKELY_HUMAN");
		assertThatThrownBy(() -> insertSafetyCallbackEvent(fixture, openLink, "CLICK", "9", 7, "LIKELY_HUMAN"))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> insertSafetyCallbackEvent(fixture, clickLink, "CLICK", null, 8, "LIKELY_HUMAN"))
				.isInstanceOf(SQLException.class);
		insertSafetyInboundEvent(fixture, "REPLY", null, null, null, null, "4.2.0");
		assertThatThrownBy(() -> insertSafetyInboundEvent(fixture, "BOUNCE", openLink, "a", 9L, "BOT", "5.1.1"))
				.isInstanceOf(SQLException.class);

		setProductionMessageId(fixture.campaignRecipientId(), "<production@example.test>");
		assertThatThrownBy(() -> setProductionMessageId(newCampaignRecipient(fixture.campaignId()), "<production@example.test>"))
				.isInstanceOf(SQLException.class);
		setSafetyMessageId(fixture.safetyMessageId(), "<safety@example.test>");
		assertThatThrownBy(() -> setSafetyMessageId(newSafetyMessage(fixture), "<safety@example.test>"))
				.isInstanceOf(SQLException.class);
	}

	@Test
	void upgradesAnExistingV8SchemaWithoutChangingPublishedChecksums() throws SQLException {
		String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
		Flyway throughV8 = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.target("8")
				.load();

		try {
			assertThat(throughV8.migrate().targetSchemaVersion.toString()).isEqualTo("8");
			Flyway latest = Flyway.configure()
					.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.load();
				assertThat(latest.migrate().migrationsExecuted).isEqualTo(8);
			assertThat(latest.validateWithResult().validationSuccessful).isTrue();
		} finally {
			dropSchema(schema);
		}
	}

	private Flyway flyway() {
		return Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.load();
	}

	private Set<String> tableNames() throws SQLException {
		return queryNames("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				""");
	}

	private Set<String> columnNames(String table) throws SQLException {
		return queryNames("""
				SELECT column_name
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = '%s'
				""".formatted(table));
	}

	private Set<String> constraintNames() throws SQLException {
		return constraintDefinitions().keySet();
	}

	private Set<String> indexNames() throws SQLException {
		return indexDefinitions().keySet();
	}

	private void assertV16Columns() throws SQLException {
		assertThat(columnNames("campaigns")).containsExactlyInAnyOrderElementsOf(Set.of(
				"id", "name", "purpose", "status", "template_id", "template_version_id", "segment_id", "smtp_account_id",
				"from_name", "from_email", "reply_to", "tracking_opens_enabled", "tracking_clicks_enabled", "unsubscribe_enabled",
				"scheduled_at", "submitted_for_review_at", "approved_at", "approved_by", "rejected_at", "rejected_by",
				"rejection_reason", "started_at", "completed_at", "canceled_at", "created_by", "updated_by", "created_at", "updated_at",
				"generation_status", "generation_provider", "generation_model", "generation_job_id", "generation_requested_at",
				"generation_completed_at", "generation_error_summary", "lock_version", "mailbox_account_id", "review_preflight_digest",
				"review_preflight_at", "status_changed_at", "status_changed_by"));
		assertThat(columnNames("campaign_recipients")).containsExactlyInAnyOrderElementsOf(Set.of(
				"id", "campaign_id", "contact_id", "paper_id", "author_id", "email_ciphertext", "email_nonce", "email_hmac",
				"email_domain", "author_name_snapshot", "paper_title_snapshot", "category_snapshot", "organization_snapshot", "confidence",
				"status", "exclusion_reason", "rendered_subject", "rendered_html", "rendered_text", "queued_at", "first_attempt_at",
				"smtp_accepted_at", "final_failure_at", "first_open_at", "first_click_at", "created_at", "personalization_status",
				"personalization_rationale", "personalization_error_code", "personalization_error_message", "personalization_attempts", "personalized_at",
				"delivery_lease_hash", "delivery_lease_expires_at", "next_attempt_at", "attempt_count", "rfc_message_id", "replied_at",
				"outcome_unknown_at", "outcome_unknown_reason"));
		assertThat(columnNames("delivery_attempts")).containsExactlyInAnyOrderElementsOf(Set.of(
				"id", "campaign_recipient_id", "smtp_account_id", "attempt_number", "idempotency_key", "status", "smtp_response_code",
				"smtp_response_summary", "failure_category", "retryable", "started_at", "completed_at", "transport_stage",
				"smtp_enhanced_status_code", "rfc_message_id", "outcome_unknown_reason"));
		assertThat(columnNames("recipient_delivery_cooldowns")).containsExactlyInAnyOrder("email_hmac", "last_smtp_accepted_at", "updated_at");
		assertThat(columnNames("campaign_safety_runs")).containsExactlyInAnyOrder(
				"id", "campaign_id", "smtp_account_id", "created_by", "recipient_limit", "status", "started_at", "completed_at",
				"lock_version", "created_at", "destination_hmac", "destination_masked");
		assertThat(columnNames("campaign_safety_messages")).containsExactlyInAnyOrder(
				"id", "run_id", "campaign_recipient_id", "smtp_account_id", "status", "delivery_lease_hash", "delivery_lease_expires_at",
				"next_attempt_at", "attempt_count", "rfc_message_id", "rendered_subject", "rendered_html", "rendered_text", "created_at",
				"smtp_accepted_at", "outcome_unknown_at", "outcome_unknown_reason");
		assertThat(columnNames("campaign_safety_attempts")).containsExactlyInAnyOrder(
				"id", "safety_message_id", "attempt_number", "idempotency_key", "status", "transport_stage", "smtp_response_code",
				"smtp_enhanced_status_code", "smtp_response_summary", "failure_category", "outcome_unknown_reason", "retryable",
				"rfc_message_id", "started_at", "completed_at");
		assertThat(columnNames("campaign_safety_links")).containsExactlyInAnyOrder(
				"id", "safety_message_id", "target_url", "target_url_hash", "token_type", "token_hash", "expires_at", "created_at");
		assertThat(columnNames("campaign_safety_events")).containsExactlyInAnyOrder(
				"id", "run_id", "safety_message_id", "safety_link_id", "event_type", "occurred_at", "classification",
				"classification_reason", "diagnostic_code", "fingerprint_hash", "minute_bucket");
		assertThat(columnNames("mailbox_sync_cursors")).containsExactlyInAnyOrder(
				"mailbox_account_id", "folder_name", "uid_validity", "last_remote_uid", "lease_hash", "lease_expires_at",
				"last_synced_at", "last_error_category", "updated_at");
		assertThat(columnNames("mailbox_inbound_events")).containsExactlyInAnyOrder(
				"id", "mailbox_account_id", "folder_name", "uid_validity", "remote_uid", "inbound_type", "referenced_message_id",
				"campaign_recipient_id", "safety_message_id", "diagnostic_code", "permanent", "received_at", "created_at");
	}

	private void assertV16ConstraintDefinitions() throws SQLException {
		Map<String, String> definitions = constraintDefinitions();
		assertDefinitions(definitions, Map.ofEntries(
				Map.entry("ck_campaign_lock_version", List.of("lock_version >= 0")),
				Map.entry("ck_campaign_review_preflight_digest", List.of("review_preflight_digest IS NULL", "octet_length(review_preflight_digest) = 32")),
				Map.entry("ck_campaign_recipient_delivery_status", recipientStatuses()),
				Map.entry("ck_campaign_recipient_delivery_lease", List.of("delivery_lease_hash IS NULL", "delivery_lease_expires_at IS NULL", "octet_length(delivery_lease_hash) = 32")),
				Map.entry("ck_campaign_recipient_attempt_count", List.of("attempt_count >= 0", "attempt_count <= 3")),
				Map.entry("ck_campaign_recipient_rfc_message_id", List.of("rfc_message_id", "[:space:]")),
				Map.entry("ck_campaign_recipient_unknown_outcome", List.of("outcome_unknown_at IS NULL", "outcome_unknown_reason IS NULL", "outcome_unknown_at IS NOT NULL", "outcome_unknown_reason IS NOT NULL")),
				Map.entry("ck_delivery_attempt_status", attemptStatuses()),
				Map.entry("ck_delivery_attempt_stage", transportStages()),
				Map.entry("ck_delivery_attempt_rfc_message_id", List.of("rfc_message_id", "[:space:]")),
				Map.entry("ck_tracking_token_type", List.of("OPEN", "CLICK", "UNSUBSCRIBE")),
				Map.entry("ck_tracking_token_hash", List.of("octet_length(token_hash) = 32")),
				Map.entry("ck_tracking_token_link", List.of("token_type", "OPEN", "CLICK", "UNSUBSCRIBE", "campaign_link_id IS NULL", "campaign_link_id IS NOT NULL")),
				Map.entry("ck_recipient_delivery_cooldown_hmac", List.of("octet_length(email_hmac) = 32")),
				Map.entry("uk_campaign_safety_message_recipient", List.of("UNIQUE (run_id, campaign_recipient_id)")),
				Map.entry("uk_campaign_safety_attempt_idempotency", List.of("UNIQUE (idempotency_key)")),
				Map.entry("uk_campaign_safety_attempt_number", List.of("UNIQUE (safety_message_id, attempt_number)")),
				Map.entry("uk_campaign_safety_link_target", List.of("UNIQUE (safety_message_id, target_url_hash)")),
				Map.entry("uk_campaign_safety_link_token", List.of("UNIQUE (token_hash)")),
				Map.entry("ck_campaign_safety_run_limit", List.of("recipient_limit >= 1", "recipient_limit <= 20")),
				Map.entry("ck_campaign_safety_run_status", List.of("QUEUED", "RUNNING", "COMPLETED", "PARTIALLY_FAILED", "FAILED", "CANCELED")),
				Map.entry("ck_campaign_safety_run_lock_version", List.of("lock_version >= 0")),
				Map.entry("ck_campaign_safety_message_status", List.of("QUEUED", "CONNECTING", "SMTP_ACCEPTED", "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "CANCELED", "OUTCOME_UNKNOWN")),
				Map.entry("ck_campaign_safety_message_lease", List.of("delivery_lease_hash IS NULL", "delivery_lease_expires_at IS NULL", "octet_length(delivery_lease_hash) = 32")),
				Map.entry("ck_campaign_safety_message_attempt_count", List.of("attempt_count >= 0", "attempt_count <= 3")),
				Map.entry("ck_campaign_safety_message_rfc_message_id", List.of("rfc_message_id", "[:space:]")),
				Map.entry("ck_campaign_safety_attempt_number", List.of("attempt_number >= 1", "attempt_number <= 3")),
				Map.entry("ck_campaign_safety_attempt_status", attemptStatuses()),
				Map.entry("ck_campaign_safety_attempt_stage", transportStages()),
				Map.entry("ck_campaign_safety_link_target_hash", List.of("octet_length(target_url_hash) = 32")),
				Map.entry("ck_campaign_safety_link_token_hash", List.of("octet_length(token_hash) = 32")),
				Map.entry("ck_campaign_safety_link_token_type", List.of("OPEN", "CLICK", "UNSUBSCRIBE")),
				Map.entry("ck_campaign_safety_link_target_scheme", List.of("target_url", "https?://")),
				Map.entry("ck_campaign_safety_event_type", List.of("OPEN", "CLICK", "UNSUBSCRIBE", "REPLY", "AUTO_REPLY", "BOUNCE")),
				Map.entry("ck_mailbox_sync_cursor_uid_validity", List.of("uid_validity >= 0")),
				Map.entry("ck_mailbox_sync_cursor_uid", List.of("last_remote_uid >= 0")),
				Map.entry("ck_mailbox_sync_cursor_lease", List.of("lease_hash IS NULL", "lease_expires_at IS NULL", "octet_length(lease_hash) = 32")),
				Map.entry("uk_mailbox_inbound_event_uid", List.of("UNIQUE (mailbox_account_id, folder_name, uid_validity, remote_uid)")),
				Map.entry("ck_mailbox_inbound_event_type", List.of("REPLY", "AUTO_REPLY", "BOUNCE", "UNMATCHED")),
				Map.entry("ck_mailbox_inbound_event_uid", List.of("uid_validity >= 0", "remote_uid >= 0")),
				Map.entry("ck_mailbox_inbound_event_match", List.of("campaign_recipient_id", "safety_message_id", "NOT"))));
		assertExactSqlStringSets(definitions, Map.ofEntries(
				Map.entry("ck_campaign_recipient_delivery_status", recipientStatuses()),
				Map.entry("ck_delivery_attempt_status", attemptStatuses()),
				Map.entry("ck_delivery_attempt_stage", transportStages()),
				Map.entry("ck_tracking_token_type", List.of("OPEN", "CLICK", "UNSUBSCRIBE")),
				Map.entry("ck_tracking_token_link", List.of("OPEN", "CLICK", "UNSUBSCRIBE")),
				Map.entry("ck_campaign_safety_run_status", List.of("QUEUED", "RUNNING", "COMPLETED", "PARTIALLY_FAILED", "FAILED", "CANCELED")),
				Map.entry("ck_campaign_safety_message_status", List.of("QUEUED", "CONNECTING", "SMTP_ACCEPTED", "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "CANCELED", "OUTCOME_UNKNOWN")),
				Map.entry("ck_campaign_safety_attempt_status", attemptStatuses()),
				Map.entry("ck_campaign_safety_attempt_stage", transportStages()),
				Map.entry("ck_campaign_safety_link_token_type", List.of("OPEN", "CLICK", "UNSUBSCRIBE")),
				Map.entry("ck_campaign_safety_event_type", List.of("OPEN", "CLICK", "UNSUBSCRIBE", "REPLY", "AUTO_REPLY", "BOUNCE")),
				Map.entry("ck_mailbox_inbound_event_type", List.of("REPLY", "AUTO_REPLY", "BOUNCE", "UNMATCHED"))));
	}

	private void assertV16ForeignKeys() throws SQLException {
		assertThat(foreignKeyDefinitions("campaigns")).contains(
				"mailbox_accounts:SET NULL", "users:SET NULL");
		assertThat(foreignKeyDefinitions("campaign_safety_runs")).containsExactlyInAnyOrder(
				"campaigns:CASCADE", "smtp_accounts:NO ACTION", "users:SET NULL");
		assertThat(foreignKeyDefinitions("campaign_safety_messages")).containsExactlyInAnyOrder(
				"campaign_safety_runs:CASCADE", "campaign_recipients:NO ACTION", "smtp_accounts:NO ACTION");
		assertThat(foreignKeyDefinitions("campaign_safety_attempts")).containsExactly("campaign_safety_messages:CASCADE");
		assertThat(foreignKeyDefinitions("campaign_safety_links")).containsExactly("campaign_safety_messages:CASCADE");
		assertThat(foreignKeyDefinitions("campaign_safety_events")).containsExactlyInAnyOrder(
				"campaign_safety_runs:CASCADE", "campaign_safety_messages:CASCADE", "campaign_safety_links:SET NULL");
		assertThat(foreignKeyDefinitions("mailbox_sync_cursors")).containsExactly("mailbox_accounts:CASCADE");
		assertThat(foreignKeyDefinitions("mailbox_inbound_events")).containsExactlyInAnyOrder(
				"mailbox_accounts:CASCADE", "campaign_recipients:SET NULL", "campaign_safety_messages:SET NULL");
	}

	private void assertV16IndexDefinitions() throws SQLException {
		Map<String, String> definitions = indexDefinitions();
		assertDefinitions(definitions, Map.ofEntries(
				Map.entry("ix_campaigns_mailbox_status", List.of("(mailbox_account_id, status, id)", "WHERE (mailbox_account_id IS NOT NULL)")),
				Map.entry("ix_campaign_recipients_delivery_due", List.of("(status, next_attempt_at, id)", "WHERE ((status)::text = ANY", "QUEUED", "TEMPORARY_FAILURE")),
				Map.entry("ix_campaign_recipients_rfc_message_id", List.of("(rfc_message_id)", "WHERE (rfc_message_id IS NOT NULL)")),
				Map.entry("ix_recipient_delivery_cooldowns_accepted", List.of("(last_smtp_accepted_at DESC)")),
				Map.entry("ix_campaign_safety_runs_campaign_created", List.of("(campaign_id, created_at DESC, id)")),
				Map.entry("ix_campaign_safety_runs_status", List.of("(status, created_at, id)", "WHERE ((status)::text = ANY", "QUEUED", "RUNNING")),
				Map.entry("ix_campaign_safety_messages_due", List.of("(status, next_attempt_at, id)", "WHERE ((status)::text = ANY", "QUEUED", "TEMPORARY_FAILURE")),
				Map.entry("ix_campaign_safety_messages_run_status", List.of("(run_id, status, id)")),
				Map.entry("ix_campaign_safety_messages_rfc_message_id", List.of("(rfc_message_id)", "WHERE (rfc_message_id IS NOT NULL)")),
				Map.entry("ix_campaign_safety_attempts_message_time", List.of("(safety_message_id, started_at DESC)")),
				Map.entry("ix_campaign_safety_links_message", List.of("(safety_message_id, id)")),
				Map.entry("ix_campaign_safety_events_message_time", List.of("(safety_message_id, occurred_at DESC, id)")),
				Map.entry("ix_mailbox_sync_cursors_due", List.of("(lease_expires_at, mailbox_account_id, folder_name)")),
				Map.entry("ix_mailbox_inbound_events_message_id", List.of("(referenced_message_id)", "WHERE (referenced_message_id IS NOT NULL)")),
				Map.entry("ix_mailbox_inbound_events_recipient", List.of("(campaign_recipient_id, created_at DESC)", "WHERE (campaign_recipient_id IS NOT NULL)"))));
		assertExactSqlStringSets(definitions, Map.of(
				"ix_campaign_recipients_delivery_due", List.of("QUEUED", "TEMPORARY_FAILURE"),
				"ix_campaign_safety_runs_status", List.of("QUEUED", "RUNNING"),
				"ix_campaign_safety_messages_due", List.of("QUEUED", "TEMPORARY_FAILURE")));
		assertExactPredicates(definitions, Map.of(
				"ix_campaigns_mailbox_status", "mailbox_account_id IS NOT NULL",
				"ix_campaign_recipients_rfc_message_id", "rfc_message_id IS NOT NULL",
				"ix_campaign_safety_messages_rfc_message_id", "rfc_message_id IS NOT NULL",
				"ix_mailbox_inbound_events_message_id", "referenced_message_id IS NOT NULL",
				"ix_mailbox_inbound_events_recipient", "campaign_recipient_id IS NOT NULL"));
	}

	private List<String> recipientStatuses() {
		return List.of("QUEUED", "CONNECTING", "SMTP_ACCEPTED", "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "BOUNCED",
				"SUPPRESSED", "UNSUBSCRIBED", "CANCELED", "OUTCOME_UNKNOWN");
	}

	private List<String> attemptStatuses() {
		return List.of("CONNECTING", "SMTP_ACCEPTED", "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "CANCELED", "OUTCOME_UNKNOWN");
	}

	private List<String> transportStages() {
		return List.of("CONNECT", "EHLO", "STARTTLS", "AUTH", "MAIL_FROM", "RCPT_TO", "DATA", "POST_DATA");
	}

	private void assertDefinitions(Map<String, String> actual, Map<String, List<String>> expected) {
		assertThat(actual.keySet()).containsAll(expected.keySet());
		expected.forEach((name, fragments) -> assertThat(actual.get(name)).as(name).contains(fragments.toArray(String[]::new)));
	}

	private void assertExactSqlStringSets(Map<String, String> actual, Map<String, List<String>> expected) {
		expected.forEach((name, values) -> assertThat(sqlStringLiterals(actual.get(name))).as(name)
				.containsExactlyInAnyOrderElementsOf(values));
	}

	private void assertExactPredicates(Map<String, String> actual, Map<String, String> expected) {
		expected.forEach((name, predicate) -> assertThat(normalizePredicate(indexPredicate(actual.get(name)))).as(name)
				.isEqualTo(normalizePredicate(predicate)));
	}

	private List<String> sqlStringLiterals(String definition) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("'((?:''|[^'])*)'").matcher(definition);
		java.util.ArrayList<String> values = new java.util.ArrayList<>();
		while (matcher.find()) values.add(matcher.group(1).replace("''", "'"));
		return values;
	}

	private String indexPredicate(String indexDefinition) {
		int predicateStart = indexDefinition.indexOf(" WHERE ");
		return predicateStart < 0 ? "" : indexDefinition.substring(predicateStart + " WHERE ".length());
	}

	private String normalizePredicate(String predicate) {
		String normalized = predicate.replaceAll("\\s+", " ").strip();
		while (normalized.startsWith("(") && normalized.endsWith(")")) {
			normalized = normalized.substring(1, normalized.length() - 1).strip();
		}
		return normalized;
	}

	private void assertColumn(String table, String column, String type, Integer length, boolean nullable) throws SQLException {
		try (Connection connection = connection();
			 var statement = connection.prepareStatement("""
					SELECT data_type, character_maximum_length, is_nullable
					FROM information_schema.columns
					WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
					""")) {
			statement.setString(1, table);
			statement.setString(2, column);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).as(table + "." + column).isTrue();
				assertThat(resultSet.getString("data_type")).isEqualTo(type);
				assertThat((Integer) resultSet.getObject("character_maximum_length")).isEqualTo(length);
				assertThat(resultSet.getString("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
			}
		}
	}

	private SafetyFixture createSafetyFixture() throws SQLException {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		UUID smtpAccountId = queryUuid("""
				INSERT INTO smtp_accounts (name, host, port, tls_mode, from_email, default_from_name, reply_to,
						per_minute_limit, per_hour_limit, per_day_limit, per_domain_hour_limit)
				VALUES ('smtp-%s', 'localhost', 2525, 'PLAIN_LOCAL_ONLY', 'from@example.test', 'Safety Test', 'reply@example.test',
						10, 100, 1000, 100)
				RETURNING id
				""".formatted(suffix));
		UUID templateId = queryUuid("""
				INSERT INTO email_templates (name) VALUES ('template-%s') RETURNING id
				""".formatted(suffix));
		UUID templateVersionId = queryUuid("""
				INSERT INTO email_template_versions (template_id, version_number, subject_template, from_name_template, reply_to,
						html_content, text_content, content_size_bytes)
				VALUES ('%s', 1, 'subject', 'Safety Test', 'reply@example.test', '<p>body</p>', 'body', 11)
				RETURNING id
				""".formatted(templateId));
		UUID campaignId = queryUuid("""
				INSERT INTO campaigns (name, purpose, template_id, template_version_id, smtp_account_id, from_name, from_email, reply_to)
				VALUES ('campaign-%s', 'safety schema test', '%s', '%s', '%s', 'Safety Test', 'from@example.test', 'reply@example.test')
				RETURNING id
				""".formatted(suffix, templateId, templateVersionId, smtpAccountId));
		UUID campaignRecipientId = newCampaignRecipient(campaignId);
		UUID runId = queryUuid("""
				INSERT INTO campaign_safety_runs (campaign_id, smtp_account_id, recipient_limit, destination_hmac, destination_masked)
				VALUES ('%s', '%s', 1, decode(repeat('a', 64), 'hex'), 's***@example.test')
				RETURNING id
				""".formatted(campaignId, smtpAccountId));
		UUID safetyMessageId = queryUuid("""
				INSERT INTO campaign_safety_messages (run_id, campaign_recipient_id, smtp_account_id)
				VALUES ('%s', '%s', '%s') RETURNING id
				""".formatted(runId, campaignRecipientId, smtpAccountId));
		return new SafetyFixture(campaignId, campaignRecipientId, runId, safetyMessageId, smtpAccountId);
	}

	private UUID newCampaignRecipient(UUID campaignId) throws SQLException {
		String digest = UUID.randomUUID().toString().replace("-", "");
		return queryUuid("""
				INSERT INTO campaign_recipients (campaign_id, email_ciphertext, email_nonce, email_hmac, email_domain, confidence)
				VALUES ('%s', decode('01', 'hex'), decode('02', 'hex'), decode('%s%s', 'hex'), 'example.test', 'HIGH')
				RETURNING id
				""".formatted(campaignId, digest, digest));
	}

	private UUID newSafetyMessage(SafetyFixture fixture) throws SQLException {
		return queryUuid("""
				INSERT INTO campaign_safety_messages (run_id, campaign_recipient_id, smtp_account_id)
				VALUES ('%s', '%s', '%s') RETURNING id
				""".formatted(fixture.runId(), newCampaignRecipient(fixture.campaignId()), fixture.smtpAccountId()));
	}

	private UUID insertSafetyLink(SafetyFixture fixture, String tokenType, String targetUrl, String targetHashDigit, String tokenHashDigit)
			throws SQLException {
		String targetUrlValue = targetUrl == null ? "NULL" : "'" + targetUrl + "'";
		String targetHashValue = targetHashDigit == null ? "NULL" : "decode(repeat('" + targetHashDigit + "', 64), 'hex')";
		return queryUuid("""
				INSERT INTO campaign_safety_links (safety_message_id, target_url, target_url_hash, token_type, token_hash, expires_at)
				VALUES ('%s', %s, %s, '%s', decode(repeat('%s', 64), 'hex'), '%s')
				RETURNING id
				""".formatted(fixture.safetyMessageId(), targetUrlValue, targetHashValue, tokenType, tokenHashDigit,
				Instant.parse("2027-01-01T00:00:00Z")));
	}

	private void insertSafetyCallbackEvent(SafetyFixture fixture, UUID linkId, String eventType, String fingerprintDigit,
			long minuteBucket, String classification) throws SQLException {
		String fingerprintValue = fingerprintDigit == null ? "NULL" : "decode(repeat('" + fingerprintDigit + "', 64), 'hex')";
		String classificationValue = classification == null ? "NULL" : "'" + classification + "'";
		execute("""
				INSERT INTO campaign_safety_events (run_id, safety_message_id, safety_link_id, event_type, fingerprint_hash, minute_bucket, classification)
				VALUES ('%s', '%s', '%s', '%s', %s, %d, %s)
				""".formatted(fixture.runId(), fixture.safetyMessageId(), linkId, eventType, fingerprintValue, minuteBucket, classificationValue));
	}

	private void insertSafetyInboundEvent(SafetyFixture fixture, String eventType, UUID linkId, String fingerprintDigit,
			Long minuteBucket, String classification, String diagnosticCode) throws SQLException {
		String linkValue = linkId == null ? "NULL" : "'" + linkId + "'";
		String fingerprintValue = fingerprintDigit == null ? "NULL" : "decode(repeat('" + fingerprintDigit + "', 64), 'hex')";
		String minuteValue = minuteBucket == null ? "NULL" : minuteBucket.toString();
		String classificationValue = classification == null ? "NULL" : "'" + classification + "'";
		execute("""
				INSERT INTO campaign_safety_events (run_id, safety_message_id, safety_link_id, event_type, fingerprint_hash, minute_bucket, classification, diagnostic_code)
				VALUES ('%s', '%s', %s, '%s', %s, %s, %s, '%s')
				""".formatted(fixture.runId(), fixture.safetyMessageId(), linkValue, eventType, fingerprintValue, minuteValue,
				classificationValue, diagnosticCode));
	}

	private void setProductionMessageId(UUID recipientId, String messageId) throws SQLException {
		execute("UPDATE campaign_recipients SET rfc_message_id = '%s' WHERE id = '%s'".formatted(messageId, recipientId));
	}

	private void setSafetyMessageId(UUID safetyMessageId, String messageId) throws SQLException {
		execute("UPDATE campaign_safety_messages SET rfc_message_id = '%s' WHERE id = '%s'".formatted(messageId, safetyMessageId));
	}

	private UUID queryUuid(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
			assertThat(resultSet.next()).isTrue();
			return resultSet.getObject(1, UUID.class);
		}
	}

	private void execute(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private record SafetyFixture(UUID campaignId, UUID campaignRecipientId, UUID runId, UUID safetyMessageId, UUID smtpAccountId) {
		private SafetyFixture withSafetyMessageId(UUID value) {
			return new SafetyFixture(campaignId, campaignRecipientId, runId, value, smtpAccountId);
		}
	}

	private Map<String, String> constraintDefinitions() throws SQLException {
		return queryMap("""
				SELECT conname, pg_get_constraintdef(pg_constraint.oid, true)
				FROM pg_constraint
				JOIN pg_namespace ON pg_namespace.oid = pg_constraint.connamespace
				WHERE pg_namespace.nspname = 'public'
				""");
	}

	private Set<String> foreignKeyDefinitions(String table) throws SQLException {
		return queryNames("""
				SELECT target.relname || ':' || CASE fk_constraint.confdeltype
					WHEN 'a' THEN 'NO ACTION'
					WHEN 'c' THEN 'CASCADE'
					WHEN 'n' THEN 'SET NULL'
					WHEN 'r' THEN 'RESTRICT'
					WHEN 'd' THEN 'SET DEFAULT'
				END
				FROM pg_constraint fk_constraint
				JOIN pg_class source ON source.oid = fk_constraint.conrelid
				JOIN pg_class target ON target.oid = fk_constraint.confrelid
				JOIN pg_namespace namespace ON namespace.oid = source.relnamespace
				WHERE namespace.nspname = 'public'
				  AND source.relname = '%s'
				  AND fk_constraint.contype = 'f'
				""".formatted(table));
	}

	private Map<String, String> indexDefinitions() throws SQLException {
		return queryMap("""
				SELECT indexname, indexdef
				FROM pg_indexes
				WHERE schemaname = 'public'
				""");
	}

	private Map<String, String> queryMap(String sql) throws SQLException {
		Map<String, String> result = new java.util.HashMap<>();
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement();
			 ResultSet resultSet = statement.executeQuery(sql)) {
			while (resultSet.next()) {
				result.put(resultSet.getString(1), resultSet.getString(2));
			}
			return result;
		}
	}

	private Set<String> queryNames(String sql) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement();
			 ResultSet resultSet = statement.executeQuery(sql)) {
			while (resultSet.next()) {
				names.add(resultSet.getString(1));
			}
		}
		return names;
	}

	private long queryLong(String sql) throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement();
			 ResultSet resultSet = statement.executeQuery(sql)) {
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

	private boolean canInsertGlobalAggregateRows() throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO ingestion_daily_stats (stat_date, category_id) VALUES (CURRENT_DATE, NULL)");
			statement.executeUpdate("INSERT INTO contact_daily_stats (stat_date, category_id) VALUES (CURRENT_DATE, NULL)");
			return true;
		}
	}

	private void dropSchema(String schema) throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA " + schema + " CASCADE");
		}
	}
}
