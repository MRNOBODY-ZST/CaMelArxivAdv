package com.camel_hub.advertisement.analytics;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
		return Mono.zip(
				repository.core(filter),
				repository.dailyImported(filter).collectList(),
				repository.primaryCategories(filter, 10).collectList(),
				repository.funnel(filter),
				repository.activeJobs(filter).collectList(),
				repository.freshness(filter)
		).map(values -> new AnalyticsDtos.OverviewResponse(
				window(filter), freshness(values.getT6(), generatedAt),
				overviewMetrics(values.getT1()), values.getT2(), values.getT3(),
				funnel(values.getT4()), values.getT5()));
	}

	public Mono<AnalyticsDtos.IngestionResponse> ingestion(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		Instant generatedAt = clock.instant();
		return Mono.zip(
				repository.core(filter),
				repository.funnel(filter),
				repository.duration(filter),
				repository.dailyImported(filter).collectList(),
				repository.extractionStatuses(filter).collectList(),
				repository.workerErrors(filter).collectList(),
				repository.jobThroughput(filter).collectList(),
				repository.freshness(filter)
		).map(values -> new AnalyticsDtos.IngestionResponse(
				window(filter), freshness(values.getT8(), generatedAt),
				ingestionMetrics(values.getT1(), values.getT3()), funnel(values.getT2()),
				values.getT3(), values.getT4(), values.getT5(), values.getT6(), values.getT7()));
	}

	public Mono<AnalyticsDtos.PapersResponse> papers(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		Instant generatedAt = clock.instant();
		var first = Mono.zip(
				repository.core(filter), repository.paperGroups(filter).collectList(),
				repository.paperArchives(filter).collectList(),
				repository.primaryCategories(filter, 20).collectList(),
				repository.categoryRelations(filter).collectList());
		var second = Mono.zip(
				repository.paperMonths(filter, false).collectList(),
				repository.paperMonths(filter, true).collectList(),
				repository.authorCountBuckets(filter).collectList(),
				repository.versionCountBuckets(filter).collectList(),
				repository.sourceFormats(filter).collectList(),
				repository.freshness(filter));
		return Mono.zip(first, second).map(values -> {
			var left = values.getT1();
			var right = values.getT2();
			return new AnalyticsDtos.PapersResponse(
					window(filter), freshness(right.getT6(), generatedAt), paperMetrics(left.getT1()),
					left.getT2(), left.getT3(), left.getT4(), left.getT5(),
					right.getT1(), right.getT2(), right.getT3(), right.getT4(), right.getT5());
		});
	}

	public Mono<AnalyticsDtos.ContactsResponse> contacts(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		Instant generatedAt = clock.instant();
		var first = Mono.zip(
				repository.core(filter), repository.contactConfidence(filter).collectList(),
				repository.contactDomains(filter).collectList(),
				repository.inferredDomainClasses(filter).collectList(),
				repository.categoryDiscovery(filter).collectList());
		var second = Mono.zip(
				repository.documentClasses(filter).collectList(),
				repository.extractionRules(filter).collectList(),
				repository.reuseBuckets(filter).collectList(),
				repository.coauthorPairs(filter).collectList(),
				repository.freshness(filter));
		return Mono.zip(first, second).map(values -> {
			var left = values.getT1();
			var right = values.getT2();
			return new AnalyticsDtos.ContactsResponse(
					window(filter), freshness(right.getT5(), generatedAt), contactMetrics(left.getT1()),
					left.getT2(), left.getT3(), left.getT4(), left.getT5(),
					right.getT1(), right.getT2(), right.getT3(), right.getT4());
		});
	}

	public Mono<AnalyticsDtos.FilterOptionsResponse> filters(AnalyticsQuery query) {
		AnalyticsFilter filter = query.normalize(clock);
		return Mono.zip(
				repository.dateBounds(), repository.categoryOptions().collectList(),
				repository.jobOptions(filter).collectList(), repository.userOptions().collectList(),
				repository.domainOptions(filter).collectList()
		).map(values -> new AnalyticsDtos.FilterOptionsResponse(
				values.getT1().minimum(), values.getT1().maximum(), values.getT2(), values.getT3(),
				values.getT4(), values.getT5(),
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

	public Mono<CsvExport> export(
			String view,
			AnalyticsQuery query,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		String normalizedView = view == null ? "" : view.strip().toLowerCase(java.util.Locale.ROOT);
		Mono<CsvExport> result = switch (normalizedView) {
			case "overview" -> overview(query).map(response -> csv(
					"overview", response.metrics(), response.dailyImported(),
					response.primaryCategories(), response.funnel()));
			case "ingestion" -> ingestion(query).map(response -> csv(
					"ingestion", response.metrics(), response.dailyImported(),
					response.extractionStatuses(), response.funnel()));
			case "papers" -> papers(query).map(response -> csv(
					"papers", response.metrics(), List.of(), response.categories(), List.of()));
			case "contacts" -> contacts(query).map(response -> csv(
					"contacts", response.metrics(), List.of(), response.domains(), List.of()));
			default -> Mono.error(new AnalyticsValidationException("Unknown analytics export view"));
		};
		return result.flatMap(export -> auditExport(
				actorId, normalizedView, query.normalize(clock), context).thenReturn(export));
	}

	private AnalyticsDtos.Window window(AnalyticsFilter filter) {
		return new AnalyticsDtos.Window(
				filter.fromDate(), filter.toDate(), "papers.imported_at", "UTC");
	}

	private AnalyticsDtos.Freshness freshness(Instant dataThrough, Instant generatedAt) {
		return new AnalyticsDtos.Freshness(dataThrough, generatedAt);
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

	private CsvExport csv(
			String view,
			List<AnalyticsDtos.Metric> metrics,
			List<AnalyticsDtos.DailyCount> daily,
			List<AnalyticsDtos.NamedCount> breakdown,
			List<AnalyticsDtos.FunnelStep> funnel
	) {
		List<String> rows = new ArrayList<>();
		rows.add("section,key,label,value,numerator,denominator,unit,date,definition");
		metrics.forEach(metric -> rows.add(String.join(",",
				"metric", escaped(metric.key()), escaped(metric.label()), Double.toString(metric.value()),
				Long.toString(metric.numerator()), Long.toString(metric.denominator()), escaped(metric.unit()), "",
				escaped(metric.definition()))));
		daily.forEach(point -> rows.add(String.join(",",
				"daily", "imported", "Imported papers", Long.toString(point.count()), "", "", "count",
				point.date().toString(), "")));
		breakdown.forEach(item -> rows.add(String.join(",",
				"breakdown", escaped(item.key()), escaped(item.label()), Long.toString(item.count()),
				"", "", "count", "", "")));
		funnel.forEach(item -> rows.add(String.join(",",
				"funnel", escaped(item.key()), escaped(item.label()), Long.toString(item.count()),
				Long.toString(item.count()), Long.toString(item.previousCount()), "count", "", "")));
		return new CsvExport(
				"camel-arxiv-" + view + "-" + LocalDate.now(clock) + ".csv",
				"\uFEFF" + String.join("\r\n", rows) + "\r\n");
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
						"dateBasis", "papers.imported_at"), AuditResult.SUCCESS, null));
	}

	public record CsvExport(String filename, String content) { }
}
