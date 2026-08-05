package com.camel_hub.advertisement.arxiv.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
class ArxivSchemaMigrationTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_arxiv_schema_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	@BeforeAll
	static void migrate() {
		Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}

	@Test
	void createsVersionedTaxonomyAndResumableOaiState() throws SQLException {
		assertThat(tableNames()).contains("arxiv_category_snapshots", "arxiv_sync_cursors", "paper_imports");
		assertThat(columnNames("arxiv_category_snapshots")).contains(
				"snapshot_version", "source_type", "source_url", "payload_sha256", "item_count",
				"source_updated_at", "applied_at", "active");
		assertThat(columnNames("arxiv_sync_cursors")).contains(
				"cursor_key", "sync_type", "set_spec", "from_datestamp", "resumption_token",
				"token_received_at", "last_response_date", "last_completed_datestamp", "version");
		assertThat(constraintNames()).contains(
				"uk_arxiv_snapshot_version", "uk_arxiv_snapshot_hash", "uk_arxiv_sync_cursor_key",
				"uk_paper_import_job");
	}

	@Test
	void addsCanonicalSearchAndOptimisticJobRuntimeColumns() throws SQLException {
		assertThat(columnNames("saved_searches")).contains("criteria_hash");
		assertThat(columnNames("jobs")).contains(
				"version", "parent_job_id", "root_job_id", "checkpoint", "control_requested_at",
				"last_message_at");
		assertThat(indexNames()).contains(
				"ix_saved_searches_owner_updated", "ix_jobs_creator_status_created",
				"ix_job_events_replay", "ix_arxiv_sync_cursors_active", "ix_papers_search_vector",
				"ix_paper_imports_job");
	}

	@Test
	void databaseRejectsInvalidSnapshotAndJobRuntimeState() throws SQLException {
		assertThat(fails("""
				INSERT INTO arxiv_category_snapshots
				(snapshot_version, source_type, source_url, payload_sha256, item_count, source_updated_at)
				VALUES ('bad', 'HTML_SCRAPE', 'https://example.invalid', repeat('a', 64), 1, now())
				""")).isTrue();
		assertThat(fails("""
				INSERT INTO jobs (type, idempotency_key, version)
				VALUES ('ARXIV_IMPORT_METADATA', 'invalid-version', -1)
				""")).isTrue();
	}

	private Set<String> tableNames() throws SQLException {
		return queryNames("""
				SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
				""");
	}

	private Set<String> columnNames(String table) throws SQLException {
		return queryNames("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = '%s'
				""".formatted(table));
	}

	private Set<String> constraintNames() throws SQLException {
		return queryNames("""
				SELECT conname FROM pg_constraint
				JOIN pg_namespace ON pg_namespace.oid = pg_constraint.connamespace
				WHERE pg_namespace.nspname = 'public'
				""");
	}

	private Set<String> indexNames() throws SQLException {
		return queryNames("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'");
	}

	private Set<String> queryNames(String sql) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = connection();
			 var statement = connection.createStatement();
			 ResultSet resultSet = statement.executeQuery(sql)) {
			while (resultSet.next()) {
				names.add(resultSet.getString(1));
			}
		}
		return names;
	}

	private boolean fails(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.executeUpdate(sql);
			return false;
		}
		catch (SQLException exception) {
			return true;
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}
