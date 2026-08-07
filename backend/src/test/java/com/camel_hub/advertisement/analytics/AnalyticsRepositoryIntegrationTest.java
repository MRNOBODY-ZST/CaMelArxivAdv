package com.camel_hub.advertisement.analytics;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsRepositoryIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_analytics_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static DatabaseClient databaseClient;
	private AnalyticsService service;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
	}

	@BeforeEach
	void setUp() {
		sql("""
				TRUNCATE extraction_evidence, paper_author_contacts, contacts, extraction_runs,
				         paper_imports, paper_authors, authors, paper_categories, paper_versions,
				         papers, job_errors, jobs, arxiv_categories, arxiv_archives, arxiv_groups,
				         users CASCADE
				""");
		seed();
		service = new AnalyticsService(
				new AnalyticsRepository(databaseClient),
				Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void reconcilesOverviewFunnelAndContactsAgainstTheUtcPaperCohort() {
		AnalyticsQuery query = query(null);

		var overview = service.overview(query).block();
		assertThat(metric(overview.metrics(), "cohortPapers").value()).isEqualTo(2);
		assertThat(metric(overview.metrics(), "parsedCoverage").value()).isEqualTo(0.5);
		assertThat(metric(overview.metrics(), "emailDiscovery").value()).isEqualTo(0.5);
		assertThat(overview.funnel()).extracting(AnalyticsDtos.FunnelStep::count)
				.containsExactly(2L, 2L, 2L, 1L, 1L, 1L, 1L, 1L);

		var contacts = service.contacts(query).block();
		assertThat(metric(contacts.metrics(), "uniqueAuthors").value()).isEqualTo(2);
		assertThat(metric(contacts.metrics(), "uniqueEmails").value()).isEqualTo(2);
		assertThat(metric(contacts.metrics(), "emailsPerPaper").value()).isEqualTo(1);
		assertThat(contacts.categoryDiscovery()).singleElement().satisfies(row -> {
			assertThat(row.key()).isEqualTo("cs.AI");
			assertThat(row.numerator()).isEqualTo(1);
			assertThat(row.denominator()).isEqualTo(2);
			assertThat(row.rate()).isEqualTo(0.5);
		});
		assertThat(contacts.domains()).extracting(AnalyticsDtos.NamedCount::key)
				.containsExactlyInAnyOrder("example.edu", "gmail.com");
	}

	@Test
	void latestMappingEliminatesRerunInflationAndDomainFilterNarrowsTheCohort() {
		var contacts = service.contacts(query("example.edu")).block();

		assertThat(metric(contacts.metrics(), "uniqueEmails").value()).isEqualTo(1);
		assertThat(metric(contacts.metrics(), "discoveryRate").numerator()).isEqualTo(1);
		assertThat(metric(contacts.metrics(), "discoveryRate").denominator()).isEqualTo(1);
		assertThat(contacts.domains()).singleElement().satisfies(domain -> {
			assertThat(domain.key()).isEqualTo("example.edu");
			assertThat(domain.count()).isEqualTo(1);
		});

		AnalyticsQuery oldConfidence = new AnalyticsQuery(
				LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-03"),
				null, AnalyticsQuery.Relation.ALL, null, null, null, AnalyticsQuery.Confidence.LOW);
		var low = service.contacts(oldConfidence).block();
		assertThat(metric(low.metrics(), "uniqueEmails").value()).isZero();
		assertThat(metric(low.metrics(), "discoveryRate").denominator()).isZero();
	}

	@Test
	void zeroDenominatorsAreExplicitZerosRatherThanNanOrInfinity() {
		AnalyticsQuery empty = new AnalyticsQuery(
				LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-02"),
				null, AnalyticsQuery.Relation.ALL, null, null, null, null);

		var contacts = service.contacts(empty).block();
		assertThat(contacts.metrics()).allSatisfy(metric -> assertThat(metric.value()).isFinite());
		assertThat(metric(contacts.metrics(), "discoveryRate").value()).isZero();
		assertThat(contacts.freshness().status()).isEqualTo("NO_DATA");
		assertThat(contacts.freshness().dataThrough()).isNull();
	}

	@Test
	void zeroFillsDailySeriesAndKeepsDailyJobStatusDimensions() {
		var ingestion = service.ingestion(query(null)).block();

		assertThat(ingestion.dailyImported()).extracting(AnalyticsDtos.DailyCount::date)
				.containsExactly(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-02"),
						LocalDate.parse("2026-08-03"));
		assertThat(ingestion.dailyImported()).extracting(AnalyticsDtos.DailyCount::count)
				.containsExactly(0L, 1L, 1L);
		assertThat(ingestion.jobThroughput()).extracting(AnalyticsDtos.DailySeriesPoint::date)
				.containsExactly(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-02"),
						LocalDate.parse("2026-08-03"));
		assertThat(ingestion.jobThroughput()).extracting(AnalyticsDtos.DailySeriesPoint::count)
				.containsExactly(0L, 3L, 0L);
	}

	@Test
	void countsCanonicalAuthorsAcrossPapersAndReturnsDocumentClassDenominators() {
		sql("""
				INSERT INTO paper_authors (id, paper_id, author_id, author_order, raw_name)
				VALUES ('51000000-0000-0000-0000-000000000004',
				        '40000000-0000-0000-0000-000000000002',
				        '50000000-0000-0000-0000-000000000001', 2, 'Alice');
				INSERT INTO paper_author_contacts (
				  id, paper_author_id, paper_id, contact_id, extraction_run_id, confidence,
				  corresponding_author, human_verified, verification_status, created_at)
				VALUES ('80000000-0000-0000-0000-000000000004',
				        '51000000-0000-0000-0000-000000000004',
				        '40000000-0000-0000-0000-000000000002',
				        '70000000-0000-0000-0000-000000000001',
				        '60000000-0000-0000-0000-000000000003', 'HIGH', false, false,
				        'UNVERIFIED', '2026-08-03T01:01:00Z');
				""");

		var contacts = service.contacts(query(null)).block();
		assertThat(metric(contacts.metrics(), "uniqueAuthors").value()).isEqualTo(2);
		assertThat(contacts.documentClasses()).anySatisfy(row -> {
			if (row.key().equals("revtex4-2")) {
				assertThat(row.numerator()).isEqualTo(1);
				assertThat(row.denominator()).isEqualTo(1);
			}
		});
	}

	@Test
	void buildsAStableAuthorGraphFromTheFilteredPaperCohort() {
		sql("""
				INSERT INTO paper_authors (id, paper_id, author_id, author_order, raw_name)
				VALUES ('51000000-0000-0000-0000-000000000004',
				        '40000000-0000-0000-0000-000000000002',
				        '50000000-0000-0000-0000-000000000001', 2, 'Alice');
				""");

		var graph = service.authors(query(null)).block();

		assertThat(graph.summary().totalAuthors()).isEqualTo(3);
		assertThat(graph.summary().totalCollaborations()).isEqualTo(2);
		assertThat(graph.summary().totalPapers()).isEqualTo(2);
		assertThat(graph.summary().truncated()).isFalse();
		assertThat(graph.nodes())
				.filteredOn(node -> node.id().equals(UUID.fromString("50000000-0000-0000-0000-000000000001")))
				.singleElement()
				.satisfies(node -> {
					assertThat(node.label()).isEqualTo("Alice");
					assertThat(node.paperCount()).isEqualTo(2);
					assertThat(node.collaboratorCount()).isEqualTo(2);
					assertThat(node.contactCount()).isEqualTo(1);
				});
		assertThat(graph.edges())
				.filteredOn(edge -> edge.source().equals(UUID.fromString("50000000-0000-0000-0000-000000000001"))
						&& edge.target().equals(UUID.fromString("50000000-0000-0000-0000-000000000002")))
				.singleElement()
				.satisfies(edge -> assertThat(edge.sharedPaperCount()).isEqualTo(1));
	}

	@Test
	void separatesPrimaryAllAndCrossListCategories() {
		sql("""
				INSERT INTO arxiv_categories (
				  id, group_ref_id, archive_ref_id, group_id, group_name,
				  archive_id, archive_name, category_id, category_name)
				VALUES ('22000000-0000-0000-0000-000000000002',
				        '20000000-0000-0000-0000-000000000001',
				        '21000000-0000-0000-0000-000000000001',
				        'cs', 'Computer Science', 'cs', 'Computer Science',
				        'cs.HC', 'Human-Computer Interaction');
				INSERT INTO paper_categories (paper_id, category_id, relation_type)
				VALUES ('40000000-0000-0000-0000-000000000001',
				        '22000000-0000-0000-0000-000000000002', 'CROSS_LIST');
				""");

		var papers = service.papers(query(null)).block();
		assertThat(papers.categories()).extracting(AnalyticsDtos.NamedCount::key)
				.containsExactly("cs.AI");
		assertThat(papers.allCategories()).extracting(AnalyticsDtos.NamedCount::key)
				.contains("cs.AI", "cs.HC");
		assertThat(papers.crossListCategories()).singleElement()
				.extracting(AnalyticsDtos.NamedCount::key).isEqualTo("cs.HC");
	}

	@Test
	void exportUsesAnAllowlistedDatasetAndFiltersUserDirectoryOptions() {
		var csv = service.export("contacts", "document-classes", query(null), ACTOR, null).block();

		assertThat(csv.filename()).contains("contacts-document-classes");
		assertThat(csv.content()).contains("document-classes").doesNotContain("domain-classes");
		assertThatThrownBy(() -> service.export("contacts", "not-allowed", query(null), ACTOR, null))
				.isInstanceOf(AnalyticsValidationException.class);
		assertThat(service.filters(query(null), false).block().users()).isEmpty();
		assertThat(service.filters(query(null), true).block().users()).singleElement()
				.extracting(AnalyticsDtos.Option::label).asString().contains("analyst");
	}

	@Test
	void allDatasetIncludesWindowFreshnessAndFunnelRate() {
		var csv = service.export("ingestion", "all", query(null), ACTOR, null).block();

		assertThat(csv.content())
				.contains("window,from,From")
				.contains("window,date-basis,\"papers.imported_at\"")
				.contains("freshness,status,\"CURRENT\"")
				.contains("funnel,\"imported\",\"已导入\",1.0,2,2,rate");
	}

	@Test
	void csvHardensFormulaCellsAndAuditsTheSelectedDataset() {
		sql("UPDATE arxiv_categories SET category_name = '=SUM(1,1)' WHERE category_id = 'cs.AI'");
		AuditService audit = mock(AuditService.class);
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(audit.record(any())).thenReturn(reactor.core.publisher.Mono.empty());
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		service = new AnalyticsService(new AnalyticsRepository(databaseClient), audit, hasher);

		var csv = service.export("overview", "primary-categories", query(null), ACTOR,
				new AuthenticationRequestContext("127.0.0.1", "JUnit", "analytics-export-test")).block();

		assertThat(csv.content()).startsWith("\uFEFFsection,key,label")
				.contains("\"'=SUM(1,1)\"");
		var captor = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
		verify(audit).record(captor.capture());
		assertThat(captor.getValue().afterSummary().get("dataset")).isEqualTo("primary-categories");
	}

	private AnalyticsQuery query(String domain) {
		return new AnalyticsQuery(
				LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-03"),
				null, AnalyticsQuery.Relation.ALL, null, null, domain, null);
	}

	private AnalyticsDtos.Metric metric(java.util.List<AnalyticsDtos.Metric> metrics, String key) {
		return metrics.stream().filter(metric -> metric.key().equals(key)).findFirst().orElseThrow();
	}

	private void seed() {
		sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'analyst', 'analyst@example.invalid', 'hash', 'Analyst');

				INSERT INTO arxiv_groups (id, group_id, group_name)
				VALUES ('20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science');
				INSERT INTO arxiv_archives (id, group_ref_id, archive_id, archive_name)
				VALUES ('21000000-0000-0000-0000-000000000001',
				        '20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science');
				INSERT INTO arxiv_categories (
				  id, group_ref_id, archive_ref_id, group_id, group_name,
				  archive_id, archive_name, category_id, category_name)
				VALUES ('22000000-0000-0000-0000-000000000001',
				        '20000000-0000-0000-0000-000000000001',
				        '21000000-0000-0000-0000-000000000001',
				        'cs', 'Computer Science', 'cs', 'Computer Science',
				        'cs.AI', 'Artificial Intelligence');

				INSERT INTO jobs (
				  id, type, status, created_by, idempotency_key, total_count, processed_count,
				  success_count, progress_percent, ended_at, created_at)
				VALUES ('30000000-0000-0000-0000-000000000001', 'ARXIV_IMPORT_METADATA', 'SUCCEEDED',
				        '10000000-0000-0000-0000-000000000001', 'analytics-fixture', 5, 3, 3, 100,
				        '2026-08-02T03:00:00Z', '2026-08-02T00:00:00Z');

				INSERT INTO papers (
				  id, arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at,
				  doi, journal_reference, pdf_url, source_status, version_count, imported_at)
				VALUES
				 ('40000000-0000-0000-0000-000000000001', '2608.00001', 'Paper one', 'A',
				  '22000000-0000-0000-0000-000000000001', '2026-07-01T00:00:00Z', '2026-07-02T00:00:00Z',
				  '10.1/example', NULL, 'https://arxiv.org/pdf/2608.00001', 'PARSED', 2, '2026-08-02T00:00:00Z'),
				 ('40000000-0000-0000-0000-000000000002', '2608.00002', 'Paper two', 'B',
				  '22000000-0000-0000-0000-000000000001', '2026-07-03T00:00:00Z', '2026-07-03T00:00:00Z',
				  NULL, 'Journal 2026', 'https://arxiv.org/pdf/2608.00002', 'PARSE_FAILED', 1, '2026-08-03T00:00:00Z'),
				 ('40000000-0000-0000-0000-000000000003', '2607.99999', 'Outside', 'C',
				  '22000000-0000-0000-0000-000000000001', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z',
				  NULL, NULL, 'https://arxiv.org/pdf/2607.99999', 'PARSED', 1, '2026-07-01T00:00:00Z');

				INSERT INTO paper_categories (paper_id, category_id, relation_type)
				SELECT id, '22000000-0000-0000-0000-000000000001', 'PRIMARY' FROM papers;
				INSERT INTO paper_imports (paper_id, job_id, metadata_source, imported_at)
				VALUES ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
				        'LEGACY_API', '2026-08-02T00:00:00Z'),
				       ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001',
				        'LEGACY_API', '2026-08-03T00:00:00Z');

				INSERT INTO authors (id, normalized_name, display_name) VALUES
				 ('50000000-0000-0000-0000-000000000001', 'alice', 'Alice'),
				 ('50000000-0000-0000-0000-000000000002', 'bob', 'Bob'),
				 ('50000000-0000-0000-0000-000000000003', 'carol', 'Carol');
				INSERT INTO paper_authors (id, paper_id, author_id, author_order, raw_name) VALUES
				 ('51000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
				  '50000000-0000-0000-0000-000000000001', 1, 'Alice'),
				 ('51000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001',
				  '50000000-0000-0000-0000-000000000002', 2, 'Bob'),
				 ('51000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000002',
				  '50000000-0000-0000-0000-000000000003', 1, 'Carol');

				INSERT INTO extraction_runs (
				  id, paper_id, job_id, parser_version, status, document_class, source_format,
				  files_inspected, contacts_found, duration_ms, started_at, completed_at,
				  archive_size_bytes, extracted_size_bytes)
				VALUES
				 ('60000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', NULL,
				  '1.0', 'FAILED', 'article', 'TAR_GZIP', 0, 0, 500, '2026-08-02T00:30:00Z', '2026-08-02T00:31:00Z', NULL, NULL),
				 ('60000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', NULL,
				  '1.0', 'SUCCEEDED', 'article', 'TAR_GZIP', 2, 2, 1000, '2026-08-02T01:00:00Z', '2026-08-02T01:01:00Z', 100, 200),
				 ('60000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000002', NULL,
				  '1.0', 'FAILED', 'revtex4-2', 'TAR_GZIP', 0, 0, 2000, '2026-08-03T01:00:00Z', '2026-08-03T01:01:00Z', NULL, NULL);

				INSERT INTO contacts (
				  id, email_ciphertext, email_nonce, email_hmac, email_domain,
				  display_ciphertext, display_nonce, syntax_valid)
				VALUES
				 ('70000000-0000-0000-0000-000000000001', decode('01','hex'), decode('02','hex'),
				  decode('03','hex'), 'example.edu', decode('04','hex'), decode('05','hex'), true),
				 ('70000000-0000-0000-0000-000000000002', decode('11','hex'), decode('12','hex'),
				  decode('13','hex'), 'gmail.com', decode('14','hex'), decode('15','hex'), true);

				INSERT INTO paper_author_contacts (
				  id, paper_author_id, paper_id, contact_id, extraction_run_id, confidence,
				  corresponding_author, human_verified, verification_status, created_at)
				VALUES
				 ('80000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001',
				  '40000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001',
				  '60000000-0000-0000-0000-000000000001', 'LOW', false, false, 'UNVERIFIED', '2026-08-02T00:31:00Z'),
				 ('80000000-0000-0000-0000-000000000002', '51000000-0000-0000-0000-000000000001',
				  '40000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001',
				  '60000000-0000-0000-0000-000000000002', 'HIGH', true, true, 'CONFIRMED', '2026-08-02T01:01:00Z'),
				 ('80000000-0000-0000-0000-000000000003', '51000000-0000-0000-0000-000000000002',
				  '40000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000002',
				  '60000000-0000-0000-0000-000000000002', 'MEDIUM', false, false, 'UNVERIFIED', '2026-08-02T01:01:00Z');
				INSERT INTO extraction_evidence (
				  paper_author_contact_id, source_relative_path, rule_name, masked_context)
				VALUES ('80000000-0000-0000-0000-000000000002', 'main.tex', 'author-email-nearby', 'a***@example.edu');
				""");
	}

	private void sql(String sql) {
		databaseClient.sql(sql).fetch().rowsUpdated().block();
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
