package com.camel_hub.advertisement.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AnalyticsDtos {

	private AnalyticsDtos() {
	}

	public record Window(LocalDate from, LocalDate to, String dateBasis, String timezone) { }

	public record Freshness(Instant dataThrough, String status, Instant generatedAt) { }

	public record Metric(
			String key,
			String label,
			double value,
			long numerator,
			long denominator,
			String unit,
			String definition
	) { }

	public record NamedCount(String key, String label, long count) { }

	public record DailyCount(LocalDate date, long count) { }

	public record DailySeriesPoint(LocalDate date, String key, String label, long count) { }

	public record Breakdown(
			String key, String label, long numerator, long denominator, double rate
	) { }

	public record FunnelStep(
			String key,
			String label,
			long count,
			long previousCount,
			double rateFromPrevious
	) { }

	public record DurationStats(
			long samples,
			double averageMs,
			long p50Ms,
			long p90Ms,
			long p95Ms,
			long p99Ms
	) { }

	public record OverviewResponse(
			Window window,
			Freshness freshness,
			List<Metric> metrics,
			List<DailyCount> dailyImported,
			List<NamedCount> primaryCategories,
			List<FunnelStep> funnel,
			List<NamedCount> activeJobs
	) { }

	public record IngestionResponse(
			Window window,
			Freshness freshness,
			List<Metric> metrics,
			List<FunnelStep> funnel,
			DurationStats duration,
			List<DailyCount> dailyImported,
			List<NamedCount> extractionStatuses,
			List<NamedCount> workerErrors,
			List<DailySeriesPoint> jobThroughput
	) { }

	public record PapersResponse(
			Window window,
			Freshness freshness,
			List<Metric> metrics,
			List<NamedCount> groups,
			List<NamedCount> archives,
			List<NamedCount> categories,
			List<NamedCount> allCategories,
			List<NamedCount> crossListCategories,
			List<NamedCount> categoryRelations,
			List<NamedCount> publicationMonths,
			List<NamedCount> updateMonths,
			List<NamedCount> authorCounts,
			List<NamedCount> versionCounts,
			List<NamedCount> sourceFormats
	) { }

	public record ContactsResponse(
			Window window,
			Freshness freshness,
			List<Metric> metrics,
			List<NamedCount> confidence,
			List<NamedCount> domains,
			List<NamedCount> inferredDomainClasses,
			List<Breakdown> categoryDiscovery,
			List<Breakdown> documentClasses,
			List<NamedCount> extractionRules,
			List<NamedCount> reuseBuckets,
			List<NamedCount> coauthorPairs
	) { }

	public record Option(String id, String label) { }

	public record FilterOptionsResponse(
			LocalDate minimumDate,
			LocalDate maximumDate,
			List<Option> categories,
			List<Option> jobs,
			List<Option> users,
			List<Option> domains,
			List<Option> confidenceLevels,
			List<Option> relationTypes
	) { }
}
