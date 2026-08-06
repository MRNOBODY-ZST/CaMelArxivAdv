package com.camel_hub.advertisement.analytics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsFilterTest {

	private static final Clock CLOCK = Clock.fixed(
			Instant.parse("2026-08-06T11:30:00Z"), ZoneOffset.UTC);

	@Test
	void defaultsToThirtyInclusiveUtcCalendarDays() {
		AnalyticsFilter filter = new AnalyticsQuery(
				null, null, null, null, null, null, null, null).normalize(CLOCK);

		assertThat(filter.fromDate()).isEqualTo(LocalDate.parse("2026-07-08"));
		assertThat(filter.toDate()).isEqualTo(LocalDate.parse("2026-08-06"));
		assertThat(filter.fromInclusive()).isEqualTo(Instant.parse("2026-07-08T00:00:00Z"));
		assertThat(filter.toExclusive()).isEqualTo(Instant.parse("2026-08-07T00:00:00Z"));
		assertThat(filter.relation()).isEqualTo(AnalyticsQuery.Relation.ALL);
	}

	@Test
	void normalizesCategoryAndDomainWithoutChangingTheirMeaning() {
		AnalyticsFilter filter = new AnalyticsQuery(
				LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-06"),
				"  cs.AI  ", AnalyticsQuery.Relation.PRIMARY, null, null,
				"  Example.EDU ", AnalyticsQuery.Confidence.HIGH).normalize(CLOCK);

		assertThat(filter.categoryId()).isEqualTo("cs.AI");
		assertThat(filter.domain()).isEqualTo("example.edu");
		assertThat(filter.relation()).isEqualTo(AnalyticsQuery.Relation.PRIMARY);
		assertThat(filter.confidence()).isEqualTo(AnalyticsQuery.Confidence.HIGH);
	}

	@Test
	void rejectsReversedOrUnreasonablyWideWindows() {
		assertThatThrownBy(() -> new AnalyticsQuery(
				LocalDate.parse("2026-08-06"), LocalDate.parse("2026-08-05"),
				null, null, null, null, null, null).normalize(CLOCK))
				.isInstanceOf(AnalyticsValidationException.class)
				.hasMessageContaining("before");

		assertThatThrownBy(() -> new AnalyticsQuery(
				LocalDate.parse("2010-01-01"), LocalDate.parse("2026-08-06"),
				null, null, null, null, null, null).normalize(CLOCK))
				.isInstanceOf(AnalyticsValidationException.class)
				.hasMessageContaining("10 years");
	}
}
