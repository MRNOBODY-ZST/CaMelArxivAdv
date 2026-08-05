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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayMigrationTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_migration_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	@Test
	void migratesEmptyDatabaseAndCreatesCriticalConstraints() throws SQLException {
		Flyway flyway = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.load();

		assertThat(flyway.migrate().success).isTrue();
		assertThat(tableNames()).contains(
				"users",
				"arxiv_categories",
				"papers",
				"contacts",
				"jobs",
				"campaigns",
				"tracking_events",
				"audit_logs");
		assertThat(constraintNames()).contains(
				"uk_papers_arxiv_id",
				"uk_campaign_recipient",
				"uk_jobs_idempotency_key",
				"uk_contacts_email_hmac",
				"uk_tracking_token");
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(canInsertGlobalAggregateRows()).isTrue();
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

	private boolean canInsertGlobalAggregateRows() throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO ingestion_daily_stats (stat_date, category_id) VALUES (CURRENT_DATE, NULL)");
			statement.executeUpdate("INSERT INTO contact_daily_stats (stat_date, category_id) VALUES (CURRENT_DATE, NULL)");
			return true;
		}
	}
}
