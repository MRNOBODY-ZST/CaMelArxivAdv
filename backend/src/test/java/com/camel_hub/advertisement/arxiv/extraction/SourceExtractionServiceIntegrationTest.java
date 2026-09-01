package com.camel_hub.advertisement.arxiv.extraction;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceExtractionServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_source_job_test").withUsername("camel").withPassword("test-only");
	private static final UUID ACTOR = UUID.fromString("c98ac60e-e560-4c1d-846d-40fc75912a3b");
	private static final UUID PAPER_ONE = UUID.fromString("b8378f64-7357-4b19-992c-b32b84060b15");
	private static final UUID PAPER_TWO = UUID.fromString("e9c76886-897a-4f7a-9590-36f3cf084d47");
	private DatabaseClient databaseClient;
	private SourceExtractionService service;

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
		databaseClient.sql("DELETE FROM papers").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_categories").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_archives").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_groups").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
		seed();
		AuditService audit = mock(AuditService.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		service = new SourceExtractionService(
				new SourceExtractionRepository(databaseClient), audit, hasher,
				new ObjectMapper().findAndRegisterModules(), "0.1.0",
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void createsJobItemsAndOutboxAtomicallyForLockedPapers() {
		var result = service.create(ACTOR, List.of(PAPER_TWO, PAPER_ONE), context()).block();

		assertThat(result).isNotNull();
		assertThat(result.status()).isEqualTo("PENDING");
		assertThat(count("jobs")).isEqualTo(1);
		assertThat(count("job_items")).isEqualTo(2);
		assertThat(count("outbox_messages")).isEqualTo(1);
		assertThat(text("SELECT type FROM jobs WHERE id = '" + result.jobId() + "'"))
				.isEqualTo("ARXIV_FETCH_AND_PARSE_SOURCE");
		assertThat(text("SELECT payload::text FROM outbox_messages LIMIT 1"))
				.contains("ARXIV_FETCH_AND_PARSE_SOURCE", "2608.00001", "2608.00002",
						"paperId", "metadataAuthors", "Alice Example")
				.doesNotContain("Paper title", "abstract", "email", "password");
	}

	@Test
	void rejectsMissingPapersAndConcurrentNonterminalExtraction() {
		UUID missing = UUID.randomUUID();
		assertThatThrownBy(() -> service.create(ACTOR, List.of(missing), context()).block())
				.isInstanceOf(SourceExtractionNotFoundException.class);

		service.create(ACTOR, List.of(PAPER_ONE), context()).block();
		assertThatThrownBy(() -> service.create(ACTOR, List.of(PAPER_ONE), context()).block())
				.isInstanceOf(SourceExtractionConflictException.class);
		assertThat(count("jobs")).isEqualTo(1);
		assertThat(count("outbox_messages")).isEqualTo(1);
	}

	@Test
	void rejectsEmptyDuplicateOrOversizedRequestsBeforeWriting() {
		assertThatThrownBy(() -> service.create(ACTOR, List.of(), context()).block())
				.isInstanceOf(SourceExtractionValidationException.class);
		assertThatThrownBy(() -> service.create(
				ACTOR, List.of(PAPER_ONE, PAPER_ONE), context()).block())
				.isInstanceOf(SourceExtractionValidationException.class);
		assertThat(count("jobs")).isZero();
	}

	private void seed() {
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES (:actor, 'source-user', 'source-user@example.invalid', 'hash', 'Source User')
				""").bind("actor", ACTOR).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO arxiv_groups (id, group_id, group_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'cs', 'Computer Science')
				""").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO arxiv_archives (id, group_ref_id, archive_id, archive_name)
				VALUES ('10000000-0000-0000-0000-000000000002',
				        '10000000-0000-0000-0000-000000000001', 'cs', 'Computer Science')
				""").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO arxiv_categories (
				  id, group_ref_id, archive_ref_id, group_id, group_name,
				  archive_id, archive_name, category_id, category_name)
				VALUES ('10000000-0000-0000-0000-000000000003',
				        '10000000-0000-0000-0000-000000000001',
				        '10000000-0000-0000-0000-000000000002', 'cs', 'Computer Science',
				        'cs', 'Computer Science', 'cs.AI', 'Artificial Intelligence')
				""").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO papers (
				  id, arxiv_id, title, abstract_text, primary_category_id,
				  submitted_at, updated_at, pdf_url)
				VALUES (:one, '2608.00001', 'Paper title one', 'abstract one',
				        '10000000-0000-0000-0000-000000000003', now(), now(),
				        'https://arxiv.org/pdf/2608.00001'),
				       (:two, '2608.00002', 'Paper title two', 'abstract two',
				        '10000000-0000-0000-0000-000000000003', now(), now(),
				        'https://arxiv.org/pdf/2608.00002')
				""").bind("one", PAPER_ONE).bind("two", PAPER_TWO)
				.fetch().rowsUpdated().block();
		databaseClient.sql("""
				WITH author AS (
				  INSERT INTO authors (normalized_name, display_name)
				  VALUES ('alice example', 'Alice Example') RETURNING id
				)
				INSERT INTO paper_authors (
				  paper_id, author_id, author_order, raw_name, affiliation_data)
				SELECT :paper, id, 1, 'Alice Example', '[]'::jsonb FROM author
				""").bind("paper", PAPER_ONE).fetch().rowsUpdated().block();
	}

	private long count(String table) {
		return databaseClient.sql("SELECT count(*) AS total FROM " + table)
				.map((row, metadata) -> row.get("total", Long.class)).one().block();
	}

	private String text(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("192.0.2.40", "source-test", "source-trace-1234");
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
