package com.camel_hub.advertisement.arxiv.importing;

import com.camel_hub.advertisement.arxiv.search.ArxivQueryNormalizer;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArxivImportServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_import_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("211982fa-03d6-462d-9bb7-f84097fda6fd");
	private DatabaseClient databaseClient;
	private ArxivImportService service;

	@BeforeEach
	void setUp() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
			Flyway.configure().dataSource(
					POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").load().migrate();
		}
		var connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
		databaseClient.sql("DELETE FROM outbox_messages").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM jobs").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES (:id, 'import-owner', 'import@example.invalid', 'hash', 'Importer')
				""").bind("id", ACTOR).fetch().rowsUpdated().block();
		AuditService audit = mock(AuditService.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		service = new ArxivImportService(
				new ArxivImportRepository(databaseClient), new ArxivQueryNormalizer(mapper, 200),
				() -> Mono.just(Set.of("cs.AI", "cs:cs:AI")), audit, hasher, mapper, 10_000,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void selectedImportIsCanonicalIdempotentAndTransactionalWithOutbox() {
		var command = new ArxivImportService.ImportCommand(
				List.of("2608.00001v2", "2608.00001", "hep-th/9901001v3"), null, null);

		var first = service.createImport(ACTOR, command, context()).block();
		var duplicate = service.createImport(ACTOR, command, context()).block();

		assertThat(first.created()).isTrue();
		assertThat(duplicate.created()).isFalse();
		assertThat(duplicate.jobId()).isEqualTo(first.jobId());
		assertThat(count("outbox_messages")).isEqualTo(1);
		assertThat(count("job_events")).isEqualTo(1);
		assertThat(text("SELECT payload::text FROM outbox_messages LIMIT 1"))
				.contains("ARXIV_IMPORT_METADATA", "2608.00001", "hep-th/9901001")
				.doesNotContain("v2", "v3", "email", "password");
	}

	@Test
	void createsAnIncrementalOfficialOaiSetSyncAndRejectsUnknownSets() {
		var result = service.createOaiSync(
				ACTOR, new ArxivImportService.OaiSyncCommand("cs:cs:AI", LocalDate.parse("2026-08-01")),
				context()).block();

		assertThat(result.created()).isTrue();
		assertThat(text("SELECT type FROM jobs WHERE id = '" + result.jobId() + "'"))
				.isEqualTo("ARXIV_SYNC_OAI");
		assertThatThrownBy(() -> service.createOaiSync(
				ACTOR, new ArxivImportService.OaiSyncCommand("not:official:set", null), context()).block())
				.isInstanceOf(ArxivImportValidationException.class);
	}

	@Test
	void criteriaImportRequiresABoundedCeiling() {
		var criteria = new ArxivSearchCriteria(
				List.of("cs.AI"), ArxivSearchCriteria.CategoryMode.ANY,
				null, null, null, null, "agents", null, null,
				null, null, null, ArxivSearchCriteria.SortBy.RELEVANCE,
				ArxivSearchCriteria.SortOrder.DESCENDING, 1, 20);
		assertThatThrownBy(() -> service.createImport(
				ACTOR, new ArxivImportService.ImportCommand(List.of(), criteria, null), context()).block())
				.isInstanceOf(ArxivImportValidationException.class)
				.hasMessageContaining("ceiling");
	}

	private long count(String table) {
		return databaseClient.sql("SELECT count(*) AS total FROM " + table)
				.map((row, metadata) -> row.get("total", Long.class)).one().block();
	}

	private String text(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("192.0.2.20", "import-test", "import-trace-1234");
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
