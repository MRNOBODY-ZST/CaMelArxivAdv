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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
				"lock_version", "created_at");
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
				"classification_reason", "diagnostic_code");
		assertThat(columnNames("mailbox_sync_cursors")).containsExactlyInAnyOrder(
				"mailbox_account_id", "folder_name", "uid_validity", "last_remote_uid", "lease_hash", "lease_expires_at",
				"last_synced_at", "last_error_category", "updated_at");
		assertThat(columnNames("mailbox_inbound_events")).containsExactlyInAnyOrder(
				"id", "mailbox_account_id", "folder_name", "uid_validity", "remote_uid", "inbound_type", "referenced_message_id",
				"campaign_recipient_id", "safety_message_id", "diagnostic_code", "permanent", "received_at", "created_at");
	}

	private void assertV16ConstraintDefinitions() throws SQLException {
		Map<String, String> definitions = constraintDefinitions();
		assertThat(definitions.keySet()).contains(
				"ck_campaign_lock_version", "ck_campaign_review_preflight_digest", "ck_campaign_recipient_delivery_status",
				"ck_campaign_recipient_delivery_lease", "ck_campaign_recipient_attempt_count", "ck_campaign_recipient_rfc_message_id",
				"ck_campaign_recipient_unknown_outcome", "ck_delivery_attempt_status", "ck_delivery_attempt_stage",
				"ck_delivery_attempt_rfc_message_id", "ck_tracking_token_type", "ck_tracking_token_link", "ck_tracking_token_hash",
				"ck_recipient_delivery_cooldown_hmac", "uk_campaign_safety_message_recipient", "ck_campaign_safety_run_limit",
				"ck_campaign_safety_run_status", "ck_campaign_safety_run_lock_version", "ck_campaign_safety_message_status",
				"ck_campaign_safety_message_lease", "ck_campaign_safety_message_attempt_count", "ck_campaign_safety_message_rfc_message_id",
				"ck_campaign_safety_attempt_number", "ck_campaign_safety_attempt_status", "ck_campaign_safety_attempt_stage",
				"ck_campaign_safety_link_target_hash", "ck_campaign_safety_link_token_hash", "ck_campaign_safety_link_token_type",
				"ck_campaign_safety_link_target_scheme", "ck_campaign_safety_event_type", "ck_mailbox_sync_cursor_uid_validity",
				"ck_mailbox_sync_cursor_uid", "ck_mailbox_sync_cursor_lease", "uk_mailbox_inbound_event_uid",
				"ck_mailbox_inbound_event_type", "ck_mailbox_inbound_event_uid", "ck_mailbox_inbound_event_match");
		assertThat(definitions.get("ck_campaign_review_preflight_digest")).contains("octet_length(review_preflight_digest) = 32");
		assertThat(definitions.get("ck_tracking_token_hash")).contains("octet_length(token_hash) = 32");
		assertThat(definitions.get("ck_recipient_delivery_cooldown_hmac")).contains("octet_length(email_hmac) = 32");
		assertThat(definitions.get("ck_campaign_recipient_delivery_status")).contains(
				"QUEUED", "CONNECTING", "SMTP_ACCEPTED", "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "BOUNCED", "SUPPRESSED",
				"UNSUBSCRIBED", "CANCELED", "OUTCOME_UNKNOWN");
		assertThat(definitions.get("ck_tracking_token_type")).contains("OPEN", "CLICK", "UNSUBSCRIBE");
		assertThat(definitions.get("ck_campaign_safety_event_type")).contains(
				"OPEN", "CLICK", "UNSUBSCRIBE", "REPLY", "AUTO_REPLY", "BOUNCE");
		assertThat(definitions.get("ck_mailbox_inbound_event_type")).contains("REPLY", "AUTO_REPLY", "BOUNCE", "UNMATCHED");
		for (String digestConstraint : Set.of(
				"ck_campaign_recipient_delivery_lease", "ck_campaign_safety_message_lease", "ck_mailbox_sync_cursor_lease",
				"ck_campaign_safety_link_target_hash", "ck_campaign_safety_link_token_hash")) {
			assertThat(definitions.get(digestConstraint)).contains("octet_length").contains("= 32");
		}
		assertThat(definitions.get("uk_campaign_safety_message_recipient")).contains("UNIQUE (run_id, campaign_recipient_id)");
		assertThat(definitions.get("uk_mailbox_inbound_event_uid"))
				.contains("UNIQUE (mailbox_account_id, folder_name, uid_validity, remote_uid)");
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
		assertThat(definitions.keySet()).contains(
				"ix_campaigns_mailbox_status", "ix_campaign_recipients_delivery_due", "ix_campaign_recipients_rfc_message_id",
				"ix_recipient_delivery_cooldowns_accepted", "ix_campaign_safety_runs_campaign_created", "ix_campaign_safety_runs_status",
				"ix_campaign_safety_messages_due", "ix_campaign_safety_messages_run_status", "ix_campaign_safety_messages_rfc_message_id",
				"ix_campaign_safety_attempts_message_time", "ix_campaign_safety_links_message", "ix_campaign_safety_events_message_time",
				"ix_mailbox_sync_cursors_due", "ix_mailbox_inbound_events_message_id", "ix_mailbox_inbound_events_recipient");
		assertThat(definitions.get("ix_campaign_recipients_delivery_due"))
				.contains("(status, next_attempt_at, id)").contains("WHERE ((status)::text = ANY");
		assertThat(definitions.get("ix_campaign_safety_messages_due"))
				.contains("(status, next_attempt_at, id)").contains("WHERE ((status)::text = ANY");
		assertThat(definitions.get("ix_campaign_safety_messages_run_status")).contains("(run_id, status, id)");
		assertThat(definitions.get("ix_campaign_safety_attempts_message_time")).contains("(safety_message_id, started_at DESC)");
		assertThat(definitions.get("ix_campaign_safety_events_message_time")).contains("(safety_message_id, occurred_at DESC, id)");
		assertThat(definitions.get("ix_mailbox_sync_cursors_due")).contains("(lease_expires_at, mailbox_account_id, folder_name)");
		assertThat(definitions.get("ix_mailbox_inbound_events_message_id"))
				.contains("(referenced_message_id)").contains("WHERE (referenced_message_id IS NOT NULL)");
		assertThat(definitions.get("ix_mailbox_inbound_events_recipient"))
				.contains("(campaign_recipient_id, created_at DESC)").contains("WHERE (campaign_recipient_id IS NOT NULL)");
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
