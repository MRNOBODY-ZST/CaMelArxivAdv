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
				"audit_logs");
			assertThat(constraintNames()).contains(
				"uk_papers_arxiv_id",
				"uk_campaign_recipient",
				"uk_jobs_idempotency_key",
				"uk_contacts_email_hmac",
				"uk_email_template_version",
				"uk_template_assets_object_key",
					"uk_tracking_token");
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
				assertThat(latest.migrate().migrationsExecuted).isEqualTo(5);
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

	private Set<String> constraintNames() throws SQLException {
		return queryNames("""
				SELECT conname
				FROM pg_constraint
				JOIN pg_namespace ON pg_namespace.oid = pg_constraint.connamespace
				WHERE pg_namespace.nspname = 'public'
				""");
	}

	private Set<String> columnNames(String table) throws SQLException {
		return queryNames("""
				SELECT column_name
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = '%s'
				""".formatted(table));
	}

	private Set<String> indexNames() throws SQLException {
		return queryNames("""
				SELECT indexname
				FROM pg_indexes
				WHERE schemaname = 'public'
				""");
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
