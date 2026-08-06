package com.camel_hub.advertisement.analytics;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

public record AnalyticsQuery(
		LocalDate from,
		LocalDate to,
		String categoryId,
		Relation relation,
		UUID jobId,
		UUID userId,
		String domain,
		Confidence confidence
) {

	private static final long MAXIMUM_DAYS = 3_653;

	public AnalyticsFilter normalize(Clock clock) {
		LocalDate today = LocalDate.now(clock);
		LocalDate normalizedTo = to == null ? today : to;
		LocalDate normalizedFrom = from == null ? normalizedTo.minusDays(29) : from;
		if (normalizedTo.isBefore(normalizedFrom)) {
			throw new AnalyticsValidationException("The to date cannot be before the from date");
		}
		if (ChronoUnit.DAYS.between(normalizedFrom, normalizedTo) > MAXIMUM_DAYS) {
			throw new AnalyticsValidationException("Analytics date ranges cannot exceed 10 years");
		}
		String normalizedCategory = trimmed(categoryId);
		String normalizedDomain = trimmed(domain);
		if (normalizedDomain != null) {
			normalizedDomain = normalizedDomain.toLowerCase(Locale.ROOT);
		}
		return new AnalyticsFilter(
				normalizedFrom,
				normalizedTo,
				normalizedFrom.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
				normalizedTo.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
				normalizedCategory,
				relation == null ? Relation.ALL : relation,
				jobId,
				userId,
				normalizedDomain,
				confidence);
	}

	private String trimmed(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
	}

	public enum Relation {
		ALL, PRIMARY, CROSS_LIST
	}

	public enum Confidence {
		HIGH, MEDIUM, LOW, UNMAPPED
	}
}
