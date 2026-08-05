package com.camel_hub.advertisement.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_audit_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	private DatabaseClient databaseClient;
	private AuditService auditService;

	@BeforeEach
	void setUp() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
			Flyway.configure()
					.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration")
					.load()
					.migrate();
		}
		databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
		databaseClient.sql("DELETE FROM audit_logs").fetch().rowsUpdated().block();
		auditService = new AuditService(databaseClient, new ObjectMapper());
	}

	@Test
	void persistsTraceableEventsWhileRedactingSensitiveSummaryFields() {
		auditService.record(new AuditEvent(
				null,
				"AUTH_LOGIN_FAILURE",
				"USER",
				null,
				null,
				"Browser/1.0",
				"trace-login-1234",
				Map.of("password", "Maple!Orbit92", "principal", "masked"),
				Map.of("nested", Map.of("refreshToken", "secret-token")),
				AuditResult.FAILURE,
				"BAD_CREDENTIALS")).block();

		String summaries = databaseClient.sql("""
				SELECT before_summary::text || after_summary::text AS summaries
				FROM audit_logs
				""")
				.map((row, metadata) -> row.get("summaries", String.class))
				.one()
				.block();

		assertThat(summaries)
				.contains("[REDACTED]", "masked")
				.doesNotContain("Maple!Orbit92", "secret-token");
		assertThat(databaseClient.sql("SELECT trace_id FROM audit_logs")
				.map((row, metadata) -> row.get("trace_id", String.class))
				.one().block()).isEqualTo("trace-login-1234");
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
