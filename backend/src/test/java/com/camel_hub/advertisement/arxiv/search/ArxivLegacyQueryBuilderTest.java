package com.camel_hub.advertisement.arxiv.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArxivLegacyQueryBuilderTest {

	private final ArxivLegacyQueryBuilder builder = new ArxivLegacyQueryBuilder();

	@Test
	void buildsOnlyOfficialLegacyFieldsWithZeroBasedPaging() {
		ArxivSearchCriteria criteria = new ArxivSearchCriteria(
				List.of("cs.AI", "cs.LG"), ArxivSearchCriteria.CategoryMode.PRIMARY,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"),
				LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-20"),
				"graph neural networks", "model robustness", "Ada Lovelace",
				true, false, true,
				ArxivSearchCriteria.SortBy.LAST_UPDATED_DATE,
				ArxivSearchCriteria.SortOrder.ASCENDING, 3, 25);

		ArxivLegacyQueryBuilder.LegacyQuery query = builder.build(criteria);

		assertThat(query.searchQuery()).isEqualTo(
				"(cat:cs.AI OR cat:cs.LG) AND ti:\"graph neural networks\""
						+ " AND abs:\"model robustness\" AND au:\"Ada Lovelace\""
						+ " AND submittedDate:[202601010000 TO 202601312359]");
		assertThat(query.start()).isEqualTo(50);
		assertThat(query.maxResults()).isEqualTo(25);
		assertThat(query.sortBy()).isEqualTo("lastUpdatedDate");
		assertThat(query.sortOrder()).isEqualTo("ascending");
		assertThat(query.searchQuery()).doesNotContain(
				"primary", "updatedDate", "doi", "source", "journalReference");
	}

	@Test
	void buildsAnOpenEndedSubmittedDateRange() {
		ArxivSearchCriteria criteria = new ArxivSearchCriteria(
				List.of("math.NA"), ArxivSearchCriteria.CategoryMode.ANY,
				LocalDate.parse("2025-01-01"), null,
				null, null, null, null, null, null, null, null,
				ArxivSearchCriteria.SortBy.SUBMITTED_DATE,
				ArxivSearchCriteria.SortOrder.DESCENDING, 1, 10);

		assertThat(builder.build(criteria).searchQuery())
				.isEqualTo("cat:math.NA AND submittedDate:[202501010000 TO 999912312359]");
	}
}
