package com.camel_hub.advertisement.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AnalyticsFilter(
		LocalDate fromDate,
		LocalDate toDate,
		Instant fromInclusive,
		Instant toExclusive,
		String categoryId,
		AnalyticsQuery.Relation relation,
		UUID jobId,
		UUID userId,
		String domain,
		AnalyticsQuery.Confidence confidence
) {
}
