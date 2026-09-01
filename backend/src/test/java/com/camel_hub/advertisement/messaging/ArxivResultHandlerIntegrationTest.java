package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.arxiv.paper.PaperRepository;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryRepository;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryService;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyCategory;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomySnapshot;
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
import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArxivResultHandlerIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_result_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("d356fdb0-a74d-4784-8b2d-584e01b6b770");
	private DatabaseClient databaseClient;
	private ArxivResultHandler handler;
	private PaperQueryService queryService;
	private TaxonomyRepository taxonomyRepository;
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
		databaseClient.sql("DELETE FROM worker_heartbeats").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_sync_cursors").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM jobs").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM papers").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_categories").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_archives").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_groups").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_category_snapshots").fetch().rowsUpdated().block();
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
		taxonomyRepository = new TaxonomyRepository(databaseClient, mapper);
		handler = new ArxivResultHandler(
				new ArxivResultRepository(databaseClient), new PaperRepository(databaseClient, mapper),
				taxonomyRepository, mapper,
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
		assertThat(detail.authors().getFirst().corresponding()).isFalse();
		assertThat(detail.extractionRuns()).isEmpty();
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

	@Test
	void rejectsFailedEventWhosePayloadClaimsSuccess() {
		String invalid = envelope(UUID.randomUUID(), "ARXIV_JOB_FAILED", """
				{"status":"SUCCEEDED","stage":"COMPLETED","processedCount":1,
				 "successCount":1,"failedCount":0,"totalCount":1,"progressPercent":100,
				 "checkpoint":{},"papers":[]}
				""");

		assertThatThrownBy(() -> handler.handle(invalid).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("type and status");
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("PENDING");
		assertThat(count("processed_messages")).isZero();
	}

	@Test
	void persistsAndCompletesAnOpaqueOaiCursor() {
		jobId = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO jobs (
				  id, type, status, created_by, parameters, idempotency_key, current_stage)
				VALUES (
				  :id, 'ARXIV_SYNC_OAI', 'RUNNING', :actor,
				  '{"setSpec":"cs:cs:AI","from":"2026-08-01"}'::jsonb,
				  :key, 'FETCHING_OAI')
				""").bind("id", jobId).bind("actor", ACTOR).bind("key", "oai-result:" + jobId)
				.fetch().rowsUpdated().block();

		handler.handle(envelope(UUID.randomUUID(), "ARXIV_JOB_PROGRESS", """
				{"status":"RUNNING","stage":"FETCHING_OAI","processedCount":1,
				 "successCount":1,"failedCount":0,"totalCount":0,"progressPercent":0,
				 "checkpoint":{"resumptionToken":"opaque-token","responseDate":"2026-08-05T00:00:00Z"},
				 "papers":[]}
				""")).block();

		assertThat(text("SELECT resumption_token FROM arxiv_sync_cursors WHERE last_job_id = '" + jobId + "'"))
				.isEqualTo("opaque-token");
		handler.handle(completedMessage(UUID.randomUUID())).block();
		assertThat(databaseClient.sql("""
				SELECT resumption_token IS NULL AS cleared
				FROM arxiv_sync_cursors WHERE last_job_id = :jobId
				""").bind("jobId", jobId)
				.map((row, metadata) -> row.get("cleared", Boolean.class)).one().block()).isTrue();
	}

	@Test
	void failedOaiJobRetainsItsOpaqueResumeCursor() {
		jobId = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO jobs (
				  id, type, status, created_by, parameters, idempotency_key, current_stage)
				VALUES (
				  :id, 'ARXIV_SYNC_OAI', 'RUNNING', :actor,
				  '{"setSpec":"cs:cs:AI","from":"2026-08-01"}'::jsonb,
				  :key, 'FETCHING_OAI')
				""").bind("id", jobId).bind("actor", ACTOR).bind("key", "oai-failed:" + jobId)
				.fetch().rowsUpdated().block();
		handler.handle(envelope(UUID.randomUUID(), "ARXIV_JOB_PROGRESS", """
				{"status":"RUNNING","stage":"FETCHING_OAI","processedCount":1,
				 "successCount":1,"failedCount":0,"totalCount":0,"progressPercent":0,
				 "checkpoint":{"resumptionToken":"opaque-token","responseDate":"2026-08-05T00:00:00Z"},
				 "papers":[]}
				""")).block();
		handler.handle(envelope(UUID.randomUUID(), "ARXIV_JOB_PROGRESS", """
				{"status":"RUNNING","stage":"RETRYING_OAI","processedCount":1,
				 "successCount":1,"failedCount":0,"totalCount":0,"progressPercent":0,
				 "checkpoint":{},"papers":[]}
				""")).block();
		assertThat(text("SELECT resumption_token FROM arxiv_sync_cursors WHERE last_job_id = '"
				+ jobId + "'"))
				.isEqualTo("opaque-token");

		handler.handle(envelope(UUID.randomUUID(), "ARXIV_JOB_FAILED", """
				{"status":"FAILED","stage":"FAILED","processedCount":1,
				 "successCount":1,"failedCount":0,"totalCount":0,"progressPercent":0,
				 "checkpoint":{},"papers":[],"errorCode":"WORKER_RETRY_EXHAUSTED",
				 "errorSummary":"Worker exhausted retries"}
				""")).block();

		assertThat(text("SELECT resumption_token FROM arxiv_sync_cursors WHERE last_job_id = '"
				+ jobId + "'"))
				.isEqualTo("opaque-token");
		assertThat(databaseClient.sql("""
				SELECT last_completed_datestamp IS NULL AS retained
				FROM arxiv_sync_cursors WHERE last_job_id = :jobId
				""").bind("jobId", jobId)
				.map((row, metadata) -> row.get("retained", Boolean.class)).one().block()).isTrue();
	}

	@Test
	void persistsAndRefreshesWorkerHeartbeatWithoutAJob() {
		handler.handle(heartbeatMessage(UUID.randomUUID(), "IDLE", "2026-08-05T00:00:00Z")).block();
		handler.handle(heartbeatMessage(UUID.randomUUID(), "BUSY", "2026-08-05T00:01:00Z")).block();

		assertThat(count("worker_heartbeats")).isEqualTo(1);
		assertThat(text("SELECT worker_type FROM worker_heartbeats WHERE worker_id = 'worker-1'"))
				.isEqualTo("ARXIV");
		assertThat(text("SELECT status FROM worker_heartbeats WHERE worker_id = 'worker-1'"))
				.isEqualTo("BUSY");
	}

	@Test
	void ignoresAnUnknownCurrentJobInAHeartbeatInsteadOfPoisoningTheQueue() {
		handler.handle(heartbeatMessage(
				UUID.randomUUID(), "BUSY", "2026-08-05T00:00:00Z", UUID.randomUUID())).block();

		assertThat(text("SELECT status FROM worker_heartbeats WHERE worker_id = 'worker-1'"))
				.isEqualTo("BUSY");
		assertThat(databaseClient.sql("""
				SELECT current_job_id IS NULL AS cleared
				FROM worker_heartbeats WHERE worker_id = 'worker-1'
				""").map((row, metadata) -> row.get("cleared", Boolean.class)).one().block()).isTrue();
	}

	@Test
	void appliesACompleteWorkerTaxonomySnapshotAndPreservesHistoricalRows() {
		taxonomyRepository.applySnapshot(new TaxonomySnapshot(
				"offline-v1", "OFFLINE_SNAPSHOT", List.of("https://arxiv.org/category_taxonomy"),
				Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T01:00:00Z"),
				"1".repeat(64), List.of(
				new TaxonomyCategory("cs", "Computer Science", "cs", "Computer Science",
						"cs.AI", "Artificial Intelligence", "Preserved description", false, null),
				new TaxonomyCategory("cs", "Computer Science", "cs", "Computer Science",
						"cs.LG", "Machine Learning alias", "Preserved alias", true, "cs.AI"))))
				.block();
		jobId = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO jobs (id, type, status, created_by, idempotency_key, current_stage)
				VALUES (:id, 'ARXIV_SYNC_TAXONOMY', 'RUNNING', :actor, :key, 'FETCHING_TAXONOMY')
				""").bind("id", jobId).bind("actor", ACTOR).bind("key", "taxonomy-result:" + jobId)
				.fetch().rowsUpdated().block();

		handler.handle(taxonomySnapshotMessage(UUID.randomUUID())).block();

		assertThat(text("SELECT snapshot_version FROM arxiv_category_snapshots WHERE active = true"))
				.isEqualTo("oai-listsets-2026-08-05T00-00-00Z");
		assertThat(text("SELECT category_name FROM arxiv_categories WHERE category_id = 'math.NA'"))
				.isEqualTo("Numerical Analysis");
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("SUCCEEDED");
		assertThat(text("SELECT description FROM arxiv_categories WHERE category_id = 'cs.AI'"))
				.startsWith("Covers all areas of AI");
		assertThat(databaseClient.sql("SELECT is_alias FROM arxiv_categories WHERE category_id = 'cs.LG'")
				.map((row, metadata) -> row.get(0, Boolean.class)).one().block()).isTrue();
	}

	@Test
	void rejectsTaxonomyValuesWiderThanTheDatabaseSchemaWithoutPartialActivation() {
		jobId = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO jobs (id, type, status, created_by, idempotency_key, current_stage)
				VALUES (:id, 'ARXIV_SYNC_TAXONOMY', 'RUNNING', :actor, :key, 'FETCHING_TAXONOMY')
				""").bind("id", jobId).bind("actor", ACTOR).bind("key", "taxonomy-invalid:" + jobId)
				.fetch().rowsUpdated().block();

		String invalid = taxonomySnapshotMessage(UUID.randomUUID())
				.replace("\"groupId\":\"math\"", "\"groupId\":\"" + "x".repeat(41) + "\"");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(invalid).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
		assertThat(count("processed_messages")).isZero();
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

	private String heartbeatMessage(UUID messageId, String status, String occurredAt) {
		return heartbeatMessage(messageId, status, occurredAt, null);
	}

	private String heartbeatMessage(
			UUID messageId, String status, String occurredAt, UUID currentJobId
	) {
		String currentJob = currentJobId == null ? "null" : "\"" + currentJobId + "\"";
		return """
				{"version":1,"messageId":"%s","type":"WORKER_HEARTBEAT","jobId":null,
				 "idempotencyKey":"heartbeat:worker-1:%s","traceId":"0123456789abcdef",
				 "occurredAt":"%s","payload":{"workerId":"worker-1","workerType":"ARXIV",
				 "version":"0.1.0","status":"%s","currentJobId":%s}}
				""".formatted(messageId, messageId, occurredAt, status, currentJob);
	}

	private String taxonomySnapshotMessage(UUID messageId) {
		return envelope(messageId, "ARXIV_JOB_COMPLETED", """
				{"status":"SUCCEEDED","stage":"COMPLETED","processedCount":2,
				 "successCount":2,"failedCount":0,"totalCount":2,"progressPercent":100,
				 "checkpoint":{},"papers":[],"snapshotVersion":"oai-listsets-2026-08-05T00-00-00Z",
				 "taxonomySourceUpdatedAt":"2026-08-05T00:00:00Z",
				 "taxonomyCategories":[{"groupId":"cs","groupName":"Computer Science",
				 "archiveId":"cs","archiveName":"Computer Science","categoryId":"cs.AI",
				 "categoryName":"Artificial Intelligence","description":"","alias":false,
				 "aliasTarget":null},{"groupId":"math","groupName":"Mathematics",
				 "archiveId":"math","archiveName":"Mathematics","categoryId":"math.NA",
				 "categoryName":"Numerical Analysis","description":"","alias":false,
				 "aliasTarget":null}]}
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
