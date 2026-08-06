package com.camel_hub.advertisement.analytics;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class AnalyticsRepository {

	private static final UUID EMPTY_UUID = new UUID(0, 0);
	private static final String FILTERED_PAPERS = """
			WITH filtered_papers AS (
			  SELECT p.*
			  FROM papers p
			  WHERE p.deleted_at IS NULL
			    AND p.imported_at >= :fromInclusive
			    AND p.imported_at < :toExclusive
			    AND ((:categoryEmpty AND :relationEmpty) OR EXISTS (
			      SELECT 1
			      FROM paper_categories pc
			      JOIN arxiv_categories fc ON fc.id = pc.category_id
			      WHERE pc.paper_id = p.id
			        AND (:categoryEmpty OR fc.category_id = :categoryId)
			        AND (:relationEmpty OR pc.relation_type = :relation)
			    ))
			    AND (:jobEmpty OR EXISTS (
			      SELECT 1 FROM paper_imports pi WHERE pi.paper_id = p.id AND pi.job_id = :jobId
			    ) OR EXISTS (
			      SELECT 1 FROM extraction_runs jer WHERE jer.paper_id = p.id AND jer.job_id = :jobId
			    ))
			    AND (:userEmpty OR EXISTS (
			      SELECT 1 FROM paper_imports pi JOIN jobs ij ON ij.id = pi.job_id
			      WHERE pi.paper_id = p.id AND ij.created_by = :userId
			    ) OR EXISTS (
			      SELECT 1 FROM extraction_runs uer JOIN jobs ej ON ej.id = uer.job_id
			      WHERE uer.paper_id = p.id AND ej.created_by = :userId
			    ))
			    AND (:contactFilterEmpty OR EXISTS (
			      SELECT 1 FROM (
			        SELECT DISTINCT ON (fpac.contact_id)
			          fpac.confidence, fc.email_domain
			        FROM paper_author_contacts fpac
			        JOIN contacts fc ON fc.id = fpac.contact_id AND fc.deleted_at IS NULL
			        JOIN extraction_runs fer ON fer.id = fpac.extraction_run_id
			        WHERE fpac.paper_id = p.id
			        ORDER BY fpac.contact_id, fer.completed_at DESC NULLS LAST,
			                 fpac.created_at DESC, fpac.id DESC
			      ) current_contact
			      WHERE (:domainEmpty OR current_contact.email_domain = :domain)
			        AND (:confidenceEmpty OR current_contact.confidence = :confidence)
			    ))
			),
			latest_runs AS (
			  SELECT DISTINCT ON (er.paper_id)
			    er.*
			  FROM extraction_runs er
			  JOIN filtered_papers fp ON fp.id = er.paper_id
			  ORDER BY er.paper_id, er.started_at DESC, er.id DESC
			),
			latest_mapping_candidates AS (
			  SELECT DISTINCT ON (pac.paper_id, pac.contact_id)
			    pac.*
			  FROM paper_author_contacts pac
			  JOIN filtered_papers fp ON fp.id = pac.paper_id
			  JOIN contacts c ON c.id = pac.contact_id AND c.deleted_at IS NULL
			  JOIN extraction_runs er ON er.id = pac.extraction_run_id
			  ORDER BY pac.paper_id, pac.contact_id,
			           er.completed_at DESC NULLS LAST, pac.created_at DESC, pac.id DESC
			),
			latest_mappings AS (
			  SELECT lmc.*
			  FROM latest_mapping_candidates lmc
			  JOIN contacts c ON c.id = lmc.contact_id AND c.deleted_at IS NULL
			  WHERE (:domainEmpty OR c.email_domain = :domain)
			    AND (:confidenceEmpty OR lmc.confidence = :confidence)
			)
			""";

	private final DatabaseClient databaseClient;

	public AnalyticsRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	Mono<CoreStats> core(AnalyticsFilter filter) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				SELECT
				  (SELECT count(*) FROM filtered_papers) AS cohort_papers,
				  (SELECT count(*) FROM latest_runs WHERE status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED'))
				    AS parsed_papers,
				  (SELECT count(DISTINCT paper_id) FROM latest_mappings) AS papers_with_email,
				  (SELECT count(DISTINCT pa.author_id) FROM latest_mappings lm
				     JOIN paper_authors pa ON pa.id = lm.paper_author_id) AS unique_authors,
				  (SELECT count(DISTINCT contact_id) FROM latest_mappings) AS unique_contacts,
				  (SELECT count(*) FROM latest_mappings) AS mappings,
				  (SELECT count(*) FROM latest_mappings WHERE corresponding_author) AS corresponding_mappings,
				  (SELECT count(*) FROM latest_mappings
				     WHERE human_verified AND verification_status = 'CONFIRMED') AS confirmed_mappings,
				  (SELECT count(*) FROM filtered_papers WHERE doi IS NOT NULL) AS doi_papers,
				  (SELECT count(*) FROM filtered_papers WHERE journal_reference IS NOT NULL) AS journal_papers,
				  (SELECT coalesce(sum(j.total_count), 0) FROM jobs j
				     WHERE j.type IN ('ARXIV_IMPORT_METADATA', 'ARXIV_SYNC_OAI')
				       AND j.created_at >= :fromInclusive AND j.created_at < :toExclusive
				       AND (:jobEmpty OR j.id = :jobId)
				       AND (:userEmpty OR j.created_by = :userId)) AS query_matched
				"""), filter).map((row, metadata) -> new CoreStats(
				longValue(row, "cohort_papers"), longValue(row, "parsed_papers"),
				longValue(row, "papers_with_email"), longValue(row, "unique_authors"),
				longValue(row, "unique_contacts"), longValue(row, "mappings"),
				longValue(row, "corresponding_mappings"), longValue(row, "confirmed_mappings"),
				longValue(row, "doi_papers"), longValue(row, "journal_papers"),
				longValue(row, "query_matched"))).one();
	}

	Flux<AnalyticsDtos.DailyCount> dailyImported(AnalyticsFilter filter) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				, dates AS (
				  SELECT generate_series(:fromInclusive::date, (:toExclusive - interval '1 day')::date,
				                         interval '1 day')::date AS day
				), imported AS (
				  SELECT (imported_at AT TIME ZONE 'UTC')::date AS day, count(*) AS count
				  FROM filtered_papers GROUP BY day
				)
				SELECT dates.day, coalesce(imported.count, 0) AS count
				FROM dates LEFT JOIN imported USING (day) ORDER BY dates.day
				"""), filter).map((row, metadata) -> new AnalyticsDtos.DailyCount(
				row.get("day", LocalDate.class), longValue(row, "count"))).all();
	}

	Mono<FunnelCounts> funnel(AnalyticsFilter filter) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				SELECT
				  (SELECT count(*) FROM filtered_papers) AS imported,
				  (SELECT count(*) FROM latest_runs) AS attempted,
				  (SELECT count(*) FROM latest_runs WHERE status <> 'SOURCE_UNAVAILABLE') AS available,
				  (SELECT count(*) FROM latest_runs WHERE archive_size_bytes > 0) AS downloaded,
				  (SELECT count(*) FROM latest_runs WHERE extracted_size_bytes > 0) AS unpacked,
				  (SELECT count(*) FROM latest_runs WHERE files_inspected > 0) AS tex_found,
				  (SELECT count(*) FROM latest_runs
				     WHERE status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')) AS parsed,
				  (SELECT count(DISTINCT paper_id) FROM latest_mappings) AS email_found
				"""), filter).map((row, metadata) -> new FunnelCounts(
				longValue(row, "imported"), longValue(row, "attempted"),
				longValue(row, "available"), longValue(row, "downloaded"),
				longValue(row, "unpacked"), longValue(row, "tex_found"),
				longValue(row, "parsed"), longValue(row, "email_found"))).one();
	}

	Mono<AnalyticsDtos.DurationStats> duration(AnalyticsFilter filter) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				SELECT count(duration_ms) AS samples,
				       coalesce(avg(duration_ms), 0) AS average_ms,
				       coalesce(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms), 0) AS p50,
				       coalesce(percentile_cont(0.90) WITHIN GROUP (ORDER BY duration_ms), 0) AS p90,
				       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95,
				       coalesce(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0) AS p99
				FROM latest_runs WHERE duration_ms IS NOT NULL
				"""), filter).map((row, metadata) -> new AnalyticsDtos.DurationStats(
				longValue(row, "samples"), doubleValue(row, "average_ms"),
				longValue(row, "p50"), longValue(row, "p90"),
				longValue(row, "p95"), longValue(row, "p99"))).one();
	}

	Flux<AnalyticsDtos.NamedCount> primaryCategories(AnalyticsFilter filter, int limit) {
		String sql = """
				SELECT coalesce(c.category_id, 'uncategorized') AS key,
				       coalesce(c.category_name, 'Uncategorized') AS label, count(*) AS count
				FROM filtered_papers fp LEFT JOIN arxiv_categories c ON c.id = fp.primary_category_id
				GROUP BY key, label ORDER BY count DESC, key LIMIT %d
				""".formatted(limit);
		return counts(filter, sql);
	}

	Flux<AnalyticsDtos.NamedCount> allCategories(AnalyticsFilter filter, boolean crossListOnly, int limit) {
		String sql = """
				SELECT c.category_id AS key, c.category_name AS label,
				       count(DISTINCT pc.paper_id) AS count
				FROM paper_categories pc
				JOIN filtered_papers fp ON fp.id = pc.paper_id
				JOIN arxiv_categories c ON c.id = pc.category_id
				WHERE (:allCategoryRelations OR pc.relation_type = 'CROSS_LIST')
				GROUP BY c.category_id, c.category_name
				ORDER BY count DESC, c.category_id LIMIT %d
				""".formatted(limit);
		return bind(databaseClient.sql(FILTERED_PAPERS + sql), filter)
				.bind("allCategoryRelations", !crossListOnly).map(this::namedCount).all();
	}

	Flux<AnalyticsDtos.NamedCount> extractionStatuses(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT status AS key, initcap(replace(status, '_', ' ')) AS label, count(*) AS count
				FROM latest_runs GROUP BY status ORDER BY count DESC, status
				""");
	}

	Flux<AnalyticsDtos.NamedCount> workerErrors(AnalyticsFilter filter) {
		return bindOperational(databaseClient.sql("""
				SELECT je.code AS key, je.code AS label, count(*) AS count
				FROM job_errors je JOIN jobs j ON j.id = je.job_id
				WHERE je.occurred_at >= :fromInclusive AND je.occurred_at < :toExclusive
				  AND j.type LIKE 'ARXIV_%'
				  AND (:jobEmpty OR j.id = :jobId)
				  AND (:userEmpty OR j.created_by = :userId)
				GROUP BY je.code ORDER BY count DESC, je.code LIMIT 12
				"""), filter).map(this::namedCount).all();
	}

	Flux<AnalyticsDtos.DailySeriesPoint> jobThroughput(AnalyticsFilter filter) {
		return bindOperational(databaseClient.sql("""
				WITH dates AS (
				  SELECT generate_series(:fromInclusive::date, (:toExclusive - interval '1 day')::date,
				                         interval '1 day')::date AS day
				), scoped_jobs AS (
				  SELECT (j.created_at AT TIME ZONE 'UTC')::date AS day, j.status, j.processed_count
				  FROM jobs j
				  WHERE j.created_at >= :fromInclusive AND j.created_at < :toExclusive
				    AND j.type LIKE 'ARXIV_%'
				    AND (:jobEmpty OR j.id = :jobId)
				    AND (:userEmpty OR j.created_by = :userId)
				), statuses AS (
				  SELECT DISTINCT status FROM scoped_jobs
				), totals AS (
				  SELECT day, status, sum(processed_count) AS count
				  FROM scoped_jobs GROUP BY day, status
				)
				SELECT dates.day, statuses.status AS key,
				       initcap(replace(statuses.status, '_', ' ')) AS label,
				       coalesce(totals.count, 0) AS count
				FROM dates CROSS JOIN statuses
				LEFT JOIN totals ON totals.day = dates.day AND totals.status = statuses.status
				ORDER BY dates.day, statuses.status
				"""), filter).map((row, metadata) -> new AnalyticsDtos.DailySeriesPoint(
				row.get("day", LocalDate.class), row.get("key", String.class),
				row.get("label", String.class), longValue(row, "count"))).all();
	}

	Flux<AnalyticsDtos.NamedCount> activeJobs(AnalyticsFilter filter) {
		return bindOperational(databaseClient.sql("""
				SELECT j.status AS key, initcap(replace(j.status, '_', ' ')) AS label, count(*) AS count
				FROM jobs j
				WHERE j.status IN ('PENDING', 'QUEUED', 'RUNNING', 'PAUSED')
				  AND j.type LIKE 'ARXIV_%'
				  AND j.created_at >= :fromInclusive AND j.created_at < :toExclusive
				  AND (:jobEmpty OR j.id = :jobId)
				  AND (:userEmpty OR j.created_by = :userId)
				GROUP BY j.status ORDER BY j.status
				"""), filter).map(this::namedCount).all();
	}

	Flux<AnalyticsDtos.NamedCount> paperGroups(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT coalesce(c.group_id, 'uncategorized') AS key,
				       coalesce(c.group_name, 'Uncategorized') AS label, count(*) AS count
				FROM filtered_papers fp LEFT JOIN arxiv_categories c ON c.id = fp.primary_category_id
				GROUP BY key, label ORDER BY count DESC, key
				""");
	}

	Flux<AnalyticsDtos.NamedCount> paperArchives(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT coalesce(c.archive_id, c.group_id, 'uncategorized') AS key,
				       coalesce(c.archive_name, c.group_name, 'Uncategorized') AS label, count(*) AS count
				FROM filtered_papers fp LEFT JOIN arxiv_categories c ON c.id = fp.primary_category_id
				GROUP BY key, label ORDER BY count DESC, key
				""");
	}

	Flux<AnalyticsDtos.NamedCount> categoryRelations(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT pc.relation_type AS key,
				       initcap(replace(pc.relation_type, '_', ' ')) AS label,
				       count(DISTINCT pc.paper_id) AS count
				FROM paper_categories pc JOIN filtered_papers fp ON fp.id = pc.paper_id
				GROUP BY pc.relation_type ORDER BY count DESC, pc.relation_type
				""");
	}

	Flux<AnalyticsDtos.NamedCount> paperMonths(AnalyticsFilter filter, boolean updated) {
		String column = updated ? "updated_at" : "submitted_at";
		String sql = """
				SELECT to_char(date_trunc('month', %s AT TIME ZONE 'UTC'), 'YYYY-MM') AS key,
				       to_char(date_trunc('month', %s AT TIME ZONE 'UTC'), 'YYYY-MM') AS label,
				       count(*) AS count
				FROM filtered_papers GROUP BY key, label ORDER BY key
				""".formatted(column, column);
		return counts(filter, sql);
	}

	Flux<AnalyticsDtos.NamedCount> authorCountBuckets(AnalyticsFilter filter) {
		return counts(filter, """
				, author_totals AS (
				  SELECT fp.id, count(pa.id) AS author_count FROM filtered_papers fp
				  LEFT JOIN paper_authors pa ON pa.paper_id = fp.id GROUP BY fp.id
				)
				SELECT CASE WHEN author_count = 0 THEN '0' WHEN author_count = 1 THEN '1'
				            WHEN author_count <= 3 THEN '2-3' WHEN author_count <= 5 THEN '4-5'
				            WHEN author_count <= 10 THEN '6-10' ELSE '11+' END AS key,
				       CASE WHEN author_count = 0 THEN '0' WHEN author_count = 1 THEN '1'
				            WHEN author_count <= 3 THEN '2-3' WHEN author_count <= 5 THEN '4-5'
				            WHEN author_count <= 10 THEN '6-10' ELSE '11+' END AS label,
				       count(*) AS count
				FROM author_totals GROUP BY key, label
				ORDER BY min(author_count)
				""");
	}

	Flux<AnalyticsDtos.NamedCount> versionCountBuckets(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT CASE WHEN version_count = 1 THEN '1' WHEN version_count = 2 THEN '2'
				            WHEN version_count = 3 THEN '3' ELSE '4+' END AS key,
				       CASE WHEN version_count = 1 THEN '1' WHEN version_count = 2 THEN '2'
				            WHEN version_count = 3 THEN '3' ELSE '4+' END AS label,
				       count(*) AS count
				FROM filtered_papers GROUP BY key, label ORDER BY min(version_count)
				""");
	}

	Flux<AnalyticsDtos.NamedCount> sourceFormats(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT coalesce(lr.source_format, fp.source_format, 'UNKNOWN') AS key,
				       coalesce(lr.source_format, fp.source_format, 'Unknown') AS label,
				       count(*) AS count
				FROM filtered_papers fp LEFT JOIN latest_runs lr ON lr.paper_id = fp.id
				GROUP BY key, label ORDER BY count DESC, key
				""");
	}

	Flux<AnalyticsDtos.NamedCount> contactConfidence(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT confidence AS key, initcap(lower(confidence)) AS label, count(*) AS count
				FROM latest_mappings GROUP BY confidence ORDER BY count DESC, confidence
				""");
	}

	Flux<AnalyticsDtos.NamedCount> contactDomains(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT c.email_domain AS key, c.email_domain AS label,
				       count(DISTINCT lm.contact_id) AS count
				FROM latest_mappings lm JOIN contacts c ON c.id = lm.contact_id
				GROUP BY c.email_domain ORDER BY count DESC, c.email_domain LIMIT 20
				""");
	}

	Flux<AnalyticsDtos.NamedCount> inferredDomainClasses(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT CASE WHEN lower(c.email_domain) IN (
				         'gmail.com', 'googlemail.com', 'outlook.com', 'hotmail.com', 'live.com',
				         'yahoo.com', 'icloud.com', 'me.com', 'proton.me', 'protonmail.com', 'qq.com', '163.com'
				       ) THEN 'COMMON_PROVIDER' ELSE 'OTHER_DOMAIN' END AS key,
				       CASE WHEN lower(c.email_domain) IN (
				         'gmail.com', 'googlemail.com', 'outlook.com', 'hotmail.com', 'live.com',
				         'yahoo.com', 'icloud.com', 'me.com', 'proton.me', 'protonmail.com', 'qq.com', '163.com'
				       ) THEN 'Common provider (inferred)' ELSE 'Other domain (inferred)' END AS label,
				       count(DISTINCT lm.contact_id) AS count
				FROM latest_mappings lm JOIN contacts c ON c.id = lm.contact_id
				GROUP BY key, label ORDER BY count DESC, key
				""");
	}

	Flux<AnalyticsDtos.Breakdown> categoryDiscovery(AnalyticsFilter filter) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				SELECT coalesce(c.category_id, 'uncategorized') AS key,
				       coalesce(c.category_name, 'Uncategorized') AS label,
				       count(DISTINCT fp.id) FILTER (WHERE lm.paper_id IS NOT NULL) AS numerator,
				       count(DISTINCT fp.id) AS denominator
				FROM filtered_papers fp
				LEFT JOIN arxiv_categories c ON c.id = fp.primary_category_id
				LEFT JOIN latest_mappings lm ON lm.paper_id = fp.id
				GROUP BY key, label ORDER BY denominator DESC, key LIMIT 20
				"""), filter).map((row, metadata) -> {
			long numerator = longValue(row, "numerator");
			long denominator = longValue(row, "denominator");
			return new AnalyticsDtos.Breakdown(
					row.get("key", String.class), row.get("label", String.class),
					numerator, denominator, rate(numerator, denominator));
		}).all();
	}

	Flux<AnalyticsDtos.Breakdown> documentClasses(AnalyticsFilter filter) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				SELECT coalesce(lr.document_class, 'UNKNOWN') AS key,
				       coalesce(lr.document_class, 'Unknown') AS label,
				       count(DISTINCT lr.paper_id) FILTER (WHERE lm.paper_id IS NOT NULL) AS numerator,
				       count(DISTINCT lr.paper_id) AS denominator
				FROM latest_runs lr LEFT JOIN latest_mappings lm ON lm.paper_id = lr.paper_id
				GROUP BY key, label ORDER BY denominator DESC, key
				"""), filter).map((row, metadata) -> {
			long numerator = longValue(row, "numerator");
			long denominator = longValue(row, "denominator");
			return new AnalyticsDtos.Breakdown(row.get("key", String.class),
					row.get("label", String.class), numerator, denominator, rate(numerator, denominator));
		}).all();
	}

	Flux<AnalyticsDtos.NamedCount> extractionRules(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT ee.rule_name AS key, ee.rule_name AS label, count(*) AS count
				FROM extraction_evidence ee JOIN latest_mappings lm
				  ON lm.id = ee.paper_author_contact_id
				GROUP BY ee.rule_name ORDER BY count DESC, ee.rule_name LIMIT 20
				""");
	}

	Flux<AnalyticsDtos.NamedCount> reuseBuckets(AnalyticsFilter filter) {
		return counts(filter, """
				, reuse AS (
				  SELECT contact_id, count(DISTINCT paper_id) AS papers
				  FROM latest_mappings GROUP BY contact_id
				)
				SELECT CASE WHEN papers = 1 THEN '1' WHEN papers <= 3 THEN '2-3'
				            WHEN papers <= 10 THEN '4-10' ELSE '11+' END AS key,
				       CASE WHEN papers = 1 THEN '1 paper' WHEN papers <= 3 THEN '2-3 papers'
				            WHEN papers <= 10 THEN '4-10 papers' ELSE '11+ papers' END AS label,
				       count(*) AS count
				FROM reuse GROUP BY key, label ORDER BY min(papers)
				""");
	}

	Flux<AnalyticsDtos.NamedCount> coauthorPairs(AnalyticsFilter filter) {
		return counts(filter, """
				SELECT least(a1.display_name, a2.display_name) || ' · ' ||
				       greatest(a1.display_name, a2.display_name) AS key,
				       least(a1.display_name, a2.display_name) || ' · ' ||
				       greatest(a1.display_name, a2.display_name) AS label,
				       count(DISTINCT pa1.paper_id) AS count
				FROM paper_authors pa1
				JOIN paper_authors pa2 ON pa2.paper_id = pa1.paper_id AND pa2.author_order > pa1.author_order
				JOIN filtered_papers fp ON fp.id = pa1.paper_id
				JOIN authors a1 ON a1.id = pa1.author_id
				JOIN authors a2 ON a2.id = pa2.author_id
				GROUP BY key, label ORDER BY count DESC, key LIMIT 20
				""");
	}

	Mono<DataFreshness> freshness(AnalyticsFilter filter, boolean includeOperational) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				SELECT greatest(
				  (SELECT max(imported_at) FROM filtered_papers),
				  (SELECT max(completed_at) FROM latest_runs),
				  (SELECT max(greatest(created_at, verified_at)) FROM latest_mappings),
				  (SELECT max(c.last_extracted_at) FROM contacts c
				     JOIN latest_mappings lm ON lm.contact_id = c.id),
				  (SELECT max(j.updated_at) FROM jobs j
				     WHERE :includeOperational AND j.type LIKE 'ARXIV_%'
				       AND j.created_at >= :fromInclusive AND j.created_at < :toExclusive
				       AND (:jobEmpty OR j.id = :jobId)
				       AND (:userEmpty OR j.created_by = :userId)),
				  (SELECT max(je.occurred_at) FROM job_errors je JOIN jobs j ON j.id = je.job_id
				     WHERE :includeOperational AND j.type LIKE 'ARXIV_%'
				       AND je.occurred_at >= :fromInclusive AND je.occurred_at < :toExclusive
				       AND (:jobEmpty OR j.id = :jobId)
				       AND (:userEmpty OR j.created_by = :userId))
				) AS data_through
				"""), filter).bind("includeOperational", includeOperational)
				.map((row, metadata) -> new DataFreshness(
				row.get("data_through", Instant.class))).one();
	}

	Mono<DateBounds> dateBounds() {
		return databaseClient.sql("""
				SELECT min((imported_at AT TIME ZONE 'UTC')::date) AS minimum_date,
				       max((imported_at AT TIME ZONE 'UTC')::date) AS maximum_date
				FROM papers WHERE deleted_at IS NULL
				""").map((row, metadata) -> new DateBounds(
				row.get("minimum_date", LocalDate.class), row.get("maximum_date", LocalDate.class))).one();
	}

	Flux<AnalyticsDtos.Option> categoryOptions() {
		return databaseClient.sql("""
				SELECT category_id AS id, category_id || ' — ' || category_name AS label
				FROM arxiv_categories WHERE active ORDER BY category_id
				""").map(this::option).all();
	}

	Flux<AnalyticsDtos.Option> jobOptions(AnalyticsFilter filter) {
		return databaseClient.sql("""
				SELECT id::text AS id,
				       type || ' · ' || left(id::text, 8) || ' · ' || status AS label
				FROM jobs
				WHERE type LIKE 'ARXIV_%' AND created_at >= :fromInclusive AND created_at < :toExclusive
				ORDER BY created_at DESC LIMIT 200
				""").bind("fromInclusive", filter.fromInclusive())
				.bind("toExclusive", filter.toExclusive()).map(this::option).all();
	}

	Flux<AnalyticsDtos.Option> userOptions() {
		return databaseClient.sql("""
				SELECT DISTINCT u.id::text AS id, u.display_name || ' (' || u.username || ')' AS label
				FROM users u JOIN jobs j ON j.created_by = u.id WHERE j.type LIKE 'ARXIV_%'
				ORDER BY label
				""").map(this::option).all();
	}

	Flux<AnalyticsDtos.Option> domainOptions(AnalyticsFilter filter) {
		return bind(databaseClient.sql(FILTERED_PAPERS + """
				SELECT DISTINCT c.email_domain AS id, c.email_domain AS label
				FROM contacts c JOIN latest_mappings lm ON lm.contact_id = c.id
				ORDER BY c.email_domain LIMIT 500
				"""), filter).map(this::option).all();
	}

	private Flux<AnalyticsDtos.NamedCount> counts(AnalyticsFilter filter, String sql) {
		return bind(databaseClient.sql(FILTERED_PAPERS + sql), filter).map(this::namedCount).all();
	}

	private AnalyticsDtos.NamedCount namedCount(
			io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata
	) {
		return new AnalyticsDtos.NamedCount(
				row.get("key", String.class), row.get("label", String.class), longValue(row, "count"));
	}

	private AnalyticsDtos.Option option(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new AnalyticsDtos.Option(row.get("id", String.class), row.get("label", String.class));
	}

	private DatabaseClient.GenericExecuteSpec bind(
			DatabaseClient.GenericExecuteSpec statement, AnalyticsFilter filter
	) {
		boolean relationEmpty = filter.relation() == AnalyticsQuery.Relation.ALL;
		boolean contactFilterEmpty = filter.domain() == null && filter.confidence() == null;
		return bindOperational(statement, filter)
				.bind("categoryEmpty", filter.categoryId() == null)
				.bind("categoryId", value(filter.categoryId()))
				.bind("relationEmpty", relationEmpty)
				.bind("relation", relationEmpty ? "" : filter.relation().name())
				.bind("contactFilterEmpty", contactFilterEmpty)
				.bind("domainEmpty", filter.domain() == null)
				.bind("domain", value(filter.domain()))
				.bind("confidenceEmpty", filter.confidence() == null)
				.bind("confidence", filter.confidence() == null ? "" : filter.confidence().name());
	}

	private DatabaseClient.GenericExecuteSpec bindOperational(
			DatabaseClient.GenericExecuteSpec statement, AnalyticsFilter filter
	) {
		return statement.bind("fromInclusive", filter.fromInclusive())
				.bind("toExclusive", filter.toExclusive())
				.bind("jobEmpty", filter.jobId() == null)
				.bind("jobId", filter.jobId() == null ? EMPTY_UUID : filter.jobId())
				.bind("userEmpty", filter.userId() == null)
				.bind("userId", filter.userId() == null ? EMPTY_UUID : filter.userId());
	}

	private String value(String value) {
		return value == null ? "" : value;
	}

	private long longValue(io.r2dbc.spi.Row row, String column) {
		Number value = row.get(column, Number.class);
		return value == null ? 0 : Math.round(value.doubleValue());
	}

	private double doubleValue(io.r2dbc.spi.Row row, String column) {
		Number value = row.get(column, Number.class);
		return value == null ? 0 : value.doubleValue();
	}

	static double rate(long numerator, long denominator) {
		if (denominator == 0) {
			return 0;
		}
		return BigDecimal.valueOf(numerator)
				.divide(BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_UP)
				.doubleValue();
	}

	record CoreStats(
			long cohortPapers,
			long parsedPapers,
			long papersWithEmail,
			long uniqueAuthors,
			long uniqueContacts,
			long mappings,
			long correspondingMappings,
			long confirmedMappings,
			long doiPapers,
			long journalPapers,
			long queryMatched
	) { }

	record FunnelCounts(
			long imported, long attempted, long available, long downloaded,
			long unpacked, long texFound, long parsed, long emailFound
	) { }

	record DateBounds(LocalDate minimum, LocalDate maximum) { }

	record DataFreshness(Instant dataThrough) { }
}
