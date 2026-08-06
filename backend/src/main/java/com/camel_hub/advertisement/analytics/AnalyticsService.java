package com.camel_hub.advertisement.analytics;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AnalyticsService {

	private final AnalyticsRepository repository;
	private final Clock clock;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;

	public AnalyticsService(AnalyticsRepository repository) {
		this(repository, Clock.systemUTC(), null, null);
	}

	AnalyticsService(AnalyticsRepository repository, Clock clock) {
		this(repository, clock, null, null);
	}

	public AnalyticsService(
			AnalyticsRepository repository,
			AuditService auditService,
			SensitiveValueHasher hasher
	) {
		this(repository, Clock.systemUTC(), auditService, hasher);
	}

	AnalyticsService(
			AnalyticsRepository repository,
			Clock clock,
			AuditService auditService,
			SensitiveValueHasher hasher
	) {
		this.repository = repository;
		this.clock = clock;
		this.auditService = auditService;
		this.hasher = hasher;
	}

	public Mono<AnalyticsDtos.OverviewResponse> overview(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		Instant generatedAt = clock.instant();
		return sequential(
				repository.core(filter),
				repository.dailyImported(filter).collectList(),
				repository.primaryCategories(filter, 10).collectList(),
				repository.funnel(filter),
				repository.activeJobs(filter).collectList(),
				repository.freshness(filter, true)
		).map(values -> new AnalyticsDtos.OverviewResponse(
				window(filter), freshness(result(values, 5), generatedAt),
				overviewMetrics(result(values, 0)), result(values, 1), result(values, 2),
				funnel(result(values, 3)), result(values, 4)));
	}

	public Mono<AnalyticsDtos.IngestionResponse> ingestion(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		Instant generatedAt = clock.instant();
		return sequential(
				repository.core(filter),
				repository.funnel(filter),
				repository.duration(filter),
				repository.dailyImported(filter).collectList(),
				repository.extractionStatuses(filter).collectList(),
				repository.workerErrors(filter).collectList(),
				repository.jobThroughput(filter).collectList(),
				repository.freshness(filter, true)
		).map(values -> new AnalyticsDtos.IngestionResponse(
				window(filter), freshness(result(values, 7), generatedAt),
				ingestionMetrics(result(values, 0), result(values, 2)), funnel(result(values, 1)),
				result(values, 2), result(values, 3), result(values, 4), result(values, 5),
				result(values, 6)));
	}

	public Mono<AnalyticsDtos.PapersResponse> papers(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		Instant generatedAt = clock.instant();
		return sequential(
				repository.core(filter), repository.paperGroups(filter).collectList(),
				repository.paperArchives(filter).collectList(),
				repository.primaryCategories(filter, 20).collectList(),
				repository.allCategories(filter, false, 20).collectList(),
				repository.allCategories(filter, true, 20).collectList(),
				repository.categoryRelations(filter).collectList(),
				repository.paperMonths(filter, false).collectList(),
				repository.paperMonths(filter, true).collectList(),
				repository.authorCountBuckets(filter).collectList(),
				repository.versionCountBuckets(filter).collectList(),
				repository.sourceFormats(filter).collectList(),
				repository.freshness(filter, false)
		).map(values -> new AnalyticsDtos.PapersResponse(
				window(filter), freshness(result(values, 12), generatedAt), paperMetrics(result(values, 0)),
				result(values, 1), result(values, 2), result(values, 3), result(values, 4), result(values, 5),
				result(values, 6), result(values, 7), result(values, 8), result(values, 9), result(values, 10),
				result(values, 11)));
	}

	public Mono<AnalyticsDtos.ContactsResponse> contacts(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		Instant generatedAt = clock.instant();
		return sequential(
				repository.core(filter), repository.contactConfidence(filter).collectList(),
				repository.contactDomains(filter).collectList(),
				repository.inferredDomainClasses(filter).collectList(),
				repository.categoryDiscovery(filter).collectList(),
				repository.documentClasses(filter).collectList(),
				repository.extractionRules(filter).collectList(),
				repository.reuseBuckets(filter).collectList(),
				repository.coauthorPairs(filter).collectList(),
				repository.freshness(filter, false)
		).map(values -> new AnalyticsDtos.ContactsResponse(
				window(filter), freshness(result(values, 9), generatedAt), contactMetrics(result(values, 0)),
				result(values, 1), result(values, 2), result(values, 3), result(values, 4),
				result(values, 5), result(values, 6), result(values, 7), result(values, 8)));
	}

	public Mono<AnalyticsDtos.FilterOptionsResponse> filters(AnalyticsQuery query, boolean includeUsers) {
		AnalyticsFilter filter = query.normalize(clock);
		Mono<List<AnalyticsDtos.Option>> users = includeUsers
				? repository.userOptions().collectList() : Mono.just(List.of());
		return sequential(
				repository.dateBounds(), repository.categoryOptions().collectList(),
				repository.jobOptions(filter).collectList(), users,
				repository.domainOptions(filter).collectList()
		).map(values -> new AnalyticsDtos.FilterOptionsResponse(
				this.<AnalyticsRepository.DateBounds>result(values, 0).minimum(),
				this.<AnalyticsRepository.DateBounds>result(values, 0).maximum(),
				result(values, 1), result(values, 2), result(values, 3), result(values, 4),
				List.of(
						new AnalyticsDtos.Option("HIGH", "High"),
						new AnalyticsDtos.Option("MEDIUM", "Medium"),
						new AnalyticsDtos.Option("LOW", "Low"),
						new AnalyticsDtos.Option("UNMAPPED", "Unmapped")),
				List.of(
						new AnalyticsDtos.Option("ALL", "Primary + cross-list"),
						new AnalyticsDtos.Option("PRIMARY", "Primary"),
						new AnalyticsDtos.Option("CROSS_LIST", "Cross-list"))));
	}

	public Mono<AnalyticsDtos.FilterOptionsResponse> filters(AnalyticsQuery query) {
		return filters(query, false);
	}

	public Mono<CsvExport> export(
			String view,
			String dataset,
			AnalyticsQuery query,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		String normalizedView = view == null ? "" : view.strip().toLowerCase(java.util.Locale.ROOT);
		String normalizedDataset = dataset == null || dataset.isBlank()
				? "all" : dataset.strip().toLowerCase(java.util.Locale.ROOT);
		validateDataset(normalizedView, normalizedDataset);
		Mono<CsvExport> result = switch (normalizedView) {
			case "overview" -> overview(query).map(response -> overviewCsv(response, normalizedDataset));
			case "ingestion" -> ingestion(query).map(response -> ingestionCsv(response, normalizedDataset));
			case "papers" -> papers(query).map(response -> papersCsv(response, normalizedDataset));
			case "contacts" -> contacts(query).map(response -> contactsCsv(response, normalizedDataset));
			default -> Mono.error(new AnalyticsValidationException("Unknown analytics export view"));
		};
		return result.flatMap(export -> auditExport(
				actorId, normalizedView, normalizedDataset, query.normalize(clock), context).thenReturn(export));
	}

	private AnalyticsDtos.Window window(AnalyticsFilter filter) {
		return new AnalyticsDtos.Window(
				filter.fromDate(), filter.toDate(), "papers.imported_at", "UTC");
	}

	private AnalyticsDtos.Freshness freshness(
			AnalyticsRepository.DataFreshness freshness, Instant generatedAt
	) {
		return new AnalyticsDtos.Freshness(
				freshness.dataThrough(), freshness.dataThrough() == null ? "NO_DATA" : "CURRENT", generatedAt);
	}

	private Mono<List<Object>> sequential(Mono<?>... sources) {
		return Flux.fromArray(sources)
				.concatMap(source -> source.map(value -> (Object) value)
						.switchIfEmpty(Mono.error(new IllegalStateException("analytics query returned no row"))))
				.collectList();
	}

	@SuppressWarnings("unchecked")
	private <T> T result(List<Object> values, int index) {
		return (T) values.get(index);
	}

	private List<AnalyticsDtos.Metric> overviewMetrics(AnalyticsRepository.CoreStats stats) {
		return List.of(
				countMetric("cohortPapers", "已导入论文", stats.cohortPapers(),
						"Distinct non-deleted papers imported in the selected UTC date window"),
				rateMetric("parsedCoverage", "解析覆盖率", stats.parsedPapers(), stats.cohortPapers(),
						"Papers whose latest extraction succeeded or partially succeeded divided by imported papers"),
				rateMetric("emailDiscovery", "邮箱发现率", stats.papersWithEmail(), stats.cohortPapers(),
						"Papers with at least one latest contact mapping divided by imported papers"));
	}

	private List<AnalyticsDtos.Metric> ingestionMetrics(
			AnalyticsRepository.CoreStats stats, AnalyticsDtos.DurationStats duration
	) {
		return List.of(
				countMetric("queryMatched", "查询匹配量", stats.queryMatched(),
						"Sum of total_count on matching arXiv import jobs created in the selected UTC window"),
				countMetric("papersImported", "已导入论文", stats.cohortPapers(),
						"Distinct non-deleted papers imported in the selected UTC date window"),
				rateMetric("parseCoverage", "解析覆盖率", stats.parsedPapers(), stats.cohortPapers(),
						"Latest successful or partially successful extraction divided by imported papers"),
				new AnalyticsDtos.Metric(
						"averageDuration", "平均解析耗时", duration.averageMs(),
						Math.round(duration.averageMs() * duration.samples()),
						duration.samples(), "milliseconds",
						"Arithmetic mean duration of the latest extraction run when duration is present"));
	}

	private List<AnalyticsDtos.Metric> paperMetrics(AnalyticsRepository.CoreStats stats) {
		return List.of(
				countMetric("cohortPapers", "论文数", stats.cohortPapers(),
						"Distinct non-deleted papers imported in the selected UTC date window"),
				rateMetric("doiCoverage", "DOI 覆盖率", stats.doiPapers(), stats.cohortPapers(),
						"Papers with a DOI divided by imported papers"),
				rateMetric("journalCoverage", "期刊引用覆盖率", stats.journalPapers(), stats.cohortPapers(),
						"Papers with a journal reference divided by imported papers"));
	}

	private List<AnalyticsDtos.Metric> contactMetrics(AnalyticsRepository.CoreStats stats) {
		return List.of(
				countMetric("uniqueAuthors", "已映射作者", stats.uniqueAuthors(),
						"Distinct authors associated with a latest contact mapping"),
				countMetric("uniqueEmails", "唯一邮箱", stats.uniqueContacts(),
						"Distinct encrypted contact records in latest mappings; full addresses are never exposed"),
				averageMetric("emailsPerPaper", "每篇论文邮箱数", stats.mappings(), stats.cohortPapers(),
						"Latest paper-contact mappings divided by imported papers"),
				rateMetric("discoveryRate", "邮箱发现率", stats.papersWithEmail(), stats.cohortPapers(),
						"Papers with at least one latest contact mapping divided by imported papers"),
				rateMetric("correspondingRate", "通讯作者率", stats.correspondingMappings(), stats.mappings(),
						"Latest mappings marked corresponding author divided by latest mappings"),
				rateMetric("confirmedRate", "人工确认率", stats.confirmedMappings(), stats.mappings(),
						"Latest mappings human-confirmed as CONFIRMED divided by latest mappings"));
	}

	private AnalyticsDtos.Metric countMetric(String key, String label, long count, String definition) {
		return new AnalyticsDtos.Metric(key, label, count, count, 1, "count", definition);
	}

	private AnalyticsDtos.Metric rateMetric(
			String key, String label, long numerator, long denominator, String definition
	) {
		return new AnalyticsDtos.Metric(
				key, label, AnalyticsRepository.rate(numerator, denominator),
				numerator, denominator, "rate", definition);
	}

	private AnalyticsDtos.Metric averageMetric(
			String key, String label, long numerator, long denominator, String definition
	) {
		return new AnalyticsDtos.Metric(
				key, label, denominator == 0 ? 0 : (double) numerator / denominator,
				numerator, denominator, "average", definition);
	}

	private List<AnalyticsDtos.FunnelStep> funnel(AnalyticsRepository.FunnelCounts counts) {
		return List.of(
				step("imported", "已导入", counts.imported(), counts.imported()),
				step("attempted", "已尝试 Source", counts.attempted(), counts.imported()),
				step("available", "Source 可用", counts.available(), counts.attempted()),
				step("downloaded", "已下载", counts.downloaded(), counts.available()),
				step("unpacked", "已解包", counts.unpacked(), counts.downloaded()),
				step("texFound", "发现 TeX", counts.texFound(), counts.unpacked()),
				step("parsed", "已解析", counts.parsed(), counts.texFound()),
				step("emailFound", "发现邮箱", counts.emailFound(), counts.parsed()));
	}

	private AnalyticsDtos.FunnelStep step(String key, String label, long count, long previous) {
		return new AnalyticsDtos.FunnelStep(
				key, label, count, previous, AnalyticsRepository.rate(count, previous));
	}

	private CsvExport overviewCsv(AnalyticsDtos.OverviewResponse response, String dataset) {
		List<String> rows = new ArrayList<>();
		if (dataset.equals("all")) addContext(rows, response.window(), response.freshness());
		if (included(dataset, "metrics")) addMetrics(rows, response.metrics());
		if (included(dataset, "daily-imported")) addDaily(rows, "daily-imported", response.dailyImported());
		if (included(dataset, "primary-categories")) addNamed(rows, "primary-categories", response.primaryCategories());
		if (included(dataset, "funnel")) addFunnel(rows, response.funnel());
		if (included(dataset, "active-jobs")) addNamed(rows, "active-jobs", response.activeJobs());
		return csv("overview", dataset, rows);
	}

	private CsvExport ingestionCsv(AnalyticsDtos.IngestionResponse response, String dataset) {
		List<String> rows = new ArrayList<>();
		if (dataset.equals("all")) addContext(rows, response.window(), response.freshness());
		if (included(dataset, "metrics")) addMetrics(rows, response.metrics());
		if (included(dataset, "funnel")) addFunnel(rows, response.funnel());
		if (included(dataset, "duration")) addDuration(rows, response.duration());
		if (included(dataset, "daily-imported")) addDaily(rows, "daily-imported", response.dailyImported());
		if (included(dataset, "extraction-statuses")) addNamed(rows, "extraction-statuses", response.extractionStatuses());
		if (included(dataset, "worker-errors")) addNamed(rows, "worker-errors", response.workerErrors());
		if (included(dataset, "job-throughput")) addDailySeries(rows, "job-throughput", response.jobThroughput());
		return csv("ingestion", dataset, rows);
	}

	private CsvExport papersCsv(AnalyticsDtos.PapersResponse response, String dataset) {
		List<String> rows = new ArrayList<>();
		if (dataset.equals("all")) addContext(rows, response.window(), response.freshness());
		if (included(dataset, "metrics")) addMetrics(rows, response.metrics());
		if (included(dataset, "groups")) addNamed(rows, "groups", response.groups());
		if (included(dataset, "archives")) addNamed(rows, "archives", response.archives());
		if (included(dataset, "primary-categories")) addNamed(rows, "primary-categories", response.categories());
		if (included(dataset, "all-categories")) addNamed(rows, "all-categories", response.allCategories());
		if (included(dataset, "cross-list-categories")) addNamed(rows, "cross-list-categories", response.crossListCategories());
		if (included(dataset, "category-relations")) addNamed(rows, "category-relations", response.categoryRelations());
		if (included(dataset, "publication-months")) addNamed(rows, "publication-months", response.publicationMonths());
		if (included(dataset, "update-months")) addNamed(rows, "update-months", response.updateMonths());
		if (included(dataset, "author-counts")) addNamed(rows, "author-counts", response.authorCounts());
		if (included(dataset, "version-counts")) addNamed(rows, "version-counts", response.versionCounts());
		if (included(dataset, "source-formats")) addNamed(rows, "source-formats", response.sourceFormats());
		return csv("papers", dataset, rows);
	}

	private CsvExport contactsCsv(AnalyticsDtos.ContactsResponse response, String dataset) {
		List<String> rows = new ArrayList<>();
		if (dataset.equals("all")) addContext(rows, response.window(), response.freshness());
		if (included(dataset, "metrics")) addMetrics(rows, response.metrics());
		if (included(dataset, "confidence")) addNamed(rows, "confidence", response.confidence());
		if (included(dataset, "domains")) addNamed(rows, "domains", response.domains());
		if (included(dataset, "domain-classes")) addNamed(rows, "domain-classes", response.inferredDomainClasses());
		if (included(dataset, "category-discovery")) addBreakdowns(rows, "category-discovery", response.categoryDiscovery());
		if (included(dataset, "document-classes")) addBreakdowns(rows, "document-classes", response.documentClasses());
		if (included(dataset, "extraction-rules")) addNamed(rows, "extraction-rules", response.extractionRules());
		if (included(dataset, "reuse-buckets")) addNamed(rows, "reuse-buckets", response.reuseBuckets());
		if (included(dataset, "coauthor-pairs")) addNamed(rows, "coauthor-pairs", response.coauthorPairs());
		return csv("contacts", dataset, rows);
	}

	private CsvExport csv(String view, String dataset, List<String> dataRows) {
		List<String> rows = new ArrayList<>();
		rows.add("section,key,label,value,numerator,denominator,unit,date,definition");
		rows.addAll(dataRows);
		return new CsvExport("camel-arxiv-" + view + "-" + dataset + "-" + LocalDate.now(clock) + ".csv",
				"\uFEFF" + String.join("\r\n", rows) + "\r\n");
	}

	private void addMetrics(List<String> rows, List<AnalyticsDtos.Metric> metrics) {
		metrics.forEach(metric -> rows.add(String.join(",",
				"metric", escaped(metric.key()), escaped(metric.label()), Double.toString(metric.value()),
				Long.toString(metric.numerator()), Long.toString(metric.denominator()), escaped(metric.unit()), "",
				escaped(metric.definition()))));
	}

	private void addContext(
			List<String> rows, AnalyticsDtos.Window window, AnalyticsDtos.Freshness freshness
	) {
		rows.add(String.join(",", "window", "from", "From", "", "", "", "date",
				window.from().toString(), ""));
		rows.add(String.join(",", "window", "to", "To", "", "", "", "date",
				window.to().toString(), ""));
		rows.add(String.join(",", "window", "date-basis", escaped(window.dateBasis()), "", "", "",
				"text", "", ""));
		rows.add(String.join(",", "window", "timezone", escaped(window.timezone()), "", "", "",
				"text", "", ""));
		rows.add(String.join(",", "freshness", "data-through", "Data through", "", "", "",
				"instant", freshness.dataThrough() == null ? "" : freshness.dataThrough().toString(), ""));
		rows.add(String.join(",", "freshness", "status", escaped(freshness.status()), "", "", "",
				"text", "", ""));
		rows.add(String.join(",", "freshness", "generated-at", "Generated at", "", "", "",
				"instant", freshness.generatedAt().toString(), ""));
	}

	private void addDaily(List<String> rows, String section, List<AnalyticsDtos.DailyCount> daily) {
		daily.forEach(point -> rows.add(String.join(",", section, "imported", "Imported papers",
				Long.toString(point.count()), "", "", "count",
				point.date().toString(), "")));
	}

	private void addNamed(List<String> rows, String section, List<AnalyticsDtos.NamedCount> values) {
		values.forEach(item -> rows.add(String.join(",", escaped(section), escaped(item.key()),
				escaped(item.label()), Long.toString(item.count()),
				"", "", "count", "", "")));
	}

	private void addBreakdowns(List<String> rows, String section, List<AnalyticsDtos.Breakdown> values) {
		values.forEach(item -> rows.add(String.join(",", escaped(section), escaped(item.key()),
				escaped(item.label()), Double.toString(item.rate()), Long.toString(item.numerator()),
				Long.toString(item.denominator()), "rate", "", "")));
	}

	private void addFunnel(List<String> rows, List<AnalyticsDtos.FunnelStep> funnel) {
		funnel.forEach(item -> rows.add(String.join(",",
				"funnel", escaped(item.key()), escaped(item.label()), Double.toString(item.rateFromPrevious()),
				Long.toString(item.count()), Long.toString(item.previousCount()), "rate", "", "")));
	}

	private void addDuration(List<String> rows, AnalyticsDtos.DurationStats duration) {
		rows.add(String.join(",", "duration", "average", "Average", Double.toString(duration.averageMs()),
				"", Long.toString(duration.samples()), "milliseconds", "", ""));
		rows.add(String.join(",", "duration", "p50", "P50", Long.toString(duration.p50Ms()), "", "",
				"milliseconds", "", ""));
		rows.add(String.join(",", "duration", "p90", "P90", Long.toString(duration.p90Ms()), "", "",
				"milliseconds", "", ""));
		rows.add(String.join(",", "duration", "p95", "P95", Long.toString(duration.p95Ms()), "", "",
				"milliseconds", "", ""));
		rows.add(String.join(",", "duration", "p99", "P99", Long.toString(duration.p99Ms()), "", "",
				"milliseconds", "", ""));
	}

	private void addDailySeries(
			List<String> rows, String section, List<AnalyticsDtos.DailySeriesPoint> values
	) {
		values.forEach(item -> rows.add(String.join(",", escaped(section), escaped(item.key()),
				escaped(item.label()), Long.toString(item.count()), "", "", "count",
				item.date().toString(), "")));
	}

	private boolean included(String selected, String dataset) {
		return selected.equals("all") || selected.equals(dataset);
	}

	private void validateDataset(String view, String dataset) {
		Set<String> allowed = switch (view) {
			case "overview" -> Set.of("all", "metrics", "daily-imported", "primary-categories", "funnel", "active-jobs");
			case "ingestion" -> Set.of("all", "metrics", "funnel", "duration", "daily-imported", "extraction-statuses", "worker-errors", "job-throughput");
			case "papers" -> Set.of("all", "metrics", "groups", "archives", "primary-categories", "all-categories", "cross-list-categories", "category-relations", "publication-months", "update-months", "author-counts", "version-counts", "source-formats");
			case "contacts" -> Set.of("all", "metrics", "confidence", "domains", "domain-classes", "category-discovery", "document-classes", "extraction-rules", "reuse-buckets", "coauthor-pairs");
			default -> throw new AnalyticsValidationException("Unknown analytics export view");
		};
		if (!allowed.contains(dataset)) {
			throw new AnalyticsValidationException("Unknown analytics export dataset");
		}
	}

	private String escaped(String value) {
		if (value == null) {
			return "";
		}
		String safe = value;
		if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
			safe = "'" + safe;
		}
		return "\"" + safe.replace("\"", "\"\"") + "\"";
	}

	private Mono<Void> auditExport(
			UUID actorId,
			String view,
			String dataset,
			AnalyticsFilter filter,
			AuthenticationRequestContext context
	) {
		if (auditService == null || hasher == null) {
			return Mono.empty();
		}
		return auditService.record(new AuditEvent(
				actorId, "ANALYTICS_EXPORT_CREATED", "ANALYTICS_VIEW", view,
				hasher.hash(context.ipAddress()), context.userAgentSummary(), context.traceId(), Map.of(),
				Map.of("from", filter.fromDate().toString(), "to", filter.toDate().toString(),
						"dateBasis", "papers.imported_at", "dataset", dataset), AuditResult.SUCCESS, null));
	}

	public record CsvExport(String filename, String content) { }
}
