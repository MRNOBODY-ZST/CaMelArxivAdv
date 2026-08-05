package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.arxiv.paper.PaperRepository;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryRepository;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArxivResultHandlerIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_result_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("d356fdb0-a74d-4784-8b2d-584e01b6b770");
	private DatabaseClient databaseClient;
	private ArxivResultHandler handler;
	private PaperQueryService queryService;
	private UUID jobId;

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
		databaseClient.sql("DELETE FROM processed_messages").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM jobs").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM papers").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_categories").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_archives").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_groups").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES (:id, 'result-owner', 'result@example.invalid', 'hash', 'Result Owner')
				""").bind("id", ACTOR).fetch().rowsUpdated().block();
		databaseClient.sql("""
				WITH g AS (
				  INSERT INTO arxiv_groups (group_id, group_name) VALUES ('cs', 'Computer Science') RETURNING id
				), a AS (
				  INSERT INTO arxiv_archives (group_ref_id, archive_id, archive_name)
				  SELECT id, 'cs', 'Computer Science' FROM g RETURNING id, group_ref_id
				)
				INSERT INTO arxiv_categories (
				  group_ref_id, archive_ref_id, group_id, group_name, archive_id, archive_name,
				  category_id, category_name
				)
				SELECT group_ref_id, id, 'cs', 'Computer Science', 'cs', 'Computer Science',
				       'cs.AI', 'Artificial Intelligence' FROM a
				""").fetch().rowsUpdated().block();
		jobId = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO jobs (id, type, status, created_by, idempotency_key, current_stage)
				VALUES (:id, 'ARXIV_IMPORT_METADATA', 'PENDING', :actor, :key, 'WAITING_FOR_WORKER')
				""").bind("id", jobId).bind("actor", ACTOR).bind("key", "result-test:" + jobId)
				.fetch().rowsUpdated().block();
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		handler = new ArxivResultHandler(
				new ArxivResultRepository(databaseClient), new PaperRepository(databaseClient, mapper), mapper,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
		queryService = new PaperQueryService(new PaperQueryRepository(databaseClient, mapper));
	}

	@Test
	void persistsMetadataProgressAndDeduplicatesAReplay() {
		UUID messageId = UUID.randomUUID();
		String batch = batchMessage(messageId);

		var first = handler.handle(batch).block();
		var replay = handler.handle(batch).block();

		assertThat(first.duplicate()).isFalse();
		assertThat(replay.duplicate()).isTrue();
		assertThat(count("papers")).isEqualTo(1);
		assertThat(count("paper_authors")).isEqualTo(1);
		assertThat(count("paper_categories")).isEqualTo(1);
		assertThat(count("paper_imports")).isEqualTo(1);
		assertThat(count("processed_messages")).isEqualTo(1);
		assertThat(text("SELECT title FROM papers WHERE arxiv_id = '2608.00001'"))
				.isEqualTo("Reliable Agents");
		var filter = new PaperQueryService.PaperFilter(
				"cs.AI", null, null, null, null, "Reliable", "Ada", "UNKNOWN",
				true, false, PaperQueryService.SortBy.UPDATED_AT,
				PaperQueryService.SortOrder.DESCENDING);
		var page = queryService.list(1, 20, filter).block();
		assertThat(page.items()).extracting(PaperQueryService.PaperSummary::arxivId)
				.containsExactly("2608.00001");
		assertThat(page.items().getFirst().authors()).containsExactly("Ada Lovelace");
		var detail = queryService.get(page.items().getFirst().id()).block();
		assertThat(detail.categories()).extracting(PaperQueryService.CategoryView::categoryId)
				.containsExactly("cs.AI");
		assertThat(detail.imports()).hasSize(1);
	}

	@Test
	void appliesMonotonicProgressAndTerminalStatus() {
		handler.handle(progressMessage(UUID.randomUUID(), 1, 2, 50)).block();
		handler.handle(progressMessage(UUID.randomUUID(), 0, 2, 10)).block();
		handler.handle(completedMessage(UUID.randomUUID())).block();

		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(2);
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("SUCCEEDED");
		assertThat(number("SELECT progress_percent::bigint FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(100);
	}

	private String batchMessage(UUID messageId) {
		return envelope(messageId, "ARXIV_JOB_BATCH", """
				{"status":"RUNNING","stage":"PERSISTING_METADATA","processedCount":0,
				 "successCount":0,"failedCount":0,"totalCount":1,"progressPercent":0,"checkpoint":{},
				 "papers":[{"arxivId":"2608.00001","version":2,"title":"Reliable Agents",
				 "abstract":"A robust agent study.","authors":[{"name":"Ada Lovelace","affiliations":["Institute"]}],
				 "primaryCategory":"cs.AI","categories":["cs.AI"],
				 "publishedAt":"2026-08-01T09:00:00+00:00","updatedAt":"2026-08-04T12:30:00+00:00",
				 "doi":"10.1000/example","journalReference":null,"comment":null,
				 "licenseUrl":"https://creativecommons.org/licenses/by/4.0/",
				 "pdfUrl":"https://arxiv.org/pdf/2608.00001v2"}]}
				""");
	}

	private String progressMessage(UUID id, long processed, long total, int progress) {
		return envelope(id, "ARXIV_JOB_PROGRESS", """
				{"status":"RUNNING","stage":"FETCHING_METADATA","processedCount":%d,
				 "successCount":%d,"failedCount":0,"totalCount":%d,"progressPercent":%d,
				 "checkpoint":{},"papers":[]}
				""".formatted(processed, processed, total, progress));
	}

	private String completedMessage(UUID id) {
		return envelope(id, "ARXIV_JOB_COMPLETED", """
				{"status":"SUCCEEDED","stage":"COMPLETED","processedCount":2,
				 "successCount":2,"failedCount":0,"totalCount":2,"progressPercent":100,
				 "checkpoint":{},"papers":[]}
				""");
	}

	private String envelope(UUID messageId, String type, String payload) {
		return """
				{"version":1,"messageId":"%s","type":"%s","jobId":"%s",
				 "idempotencyKey":"result:%s","traceId":"0123456789abcdef",
				 "occurredAt":"2026-08-05T00:00:00Z","payload":%s}
				""".formatted(messageId, type, jobId, messageId, payload);
	}

	private long count(String table) {
		return databaseClient.sql("SELECT count(*) AS total FROM " + table)
				.map((row, metadata) -> row.get("total", Long.class)).one().block();
	}

	private String text(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private long number(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
