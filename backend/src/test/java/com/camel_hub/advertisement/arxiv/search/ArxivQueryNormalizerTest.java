package com.camel_hub.advertisement.arxiv.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ArxivQueryNormalizerTest {

	private final ArxivQueryNormalizer normalizer = new ArxivQueryNormalizer(
			new ObjectMapper().findAndRegisterModules(), 100);

	@Test
	void canonicalizesEquivalentQueriesToTheSameHash() {
		ArxivSearchCriteria first = criteria(
				List.of(" cs.LG ", "cs.AI", "cs.LG"), "  graph   neural  networks ", 1, 20);
		ArxivSearchCriteria second = criteria(
				List.of("cs.AI", "cs.LG"), "graph neural networks", 1, 20);

		ArxivQueryNormalizer.NormalizedQuery normalizedFirst = normalizer.normalize(
				first, Set.of("cs.AI", "cs.LG"));
		ArxivQueryNormalizer.NormalizedQuery normalizedSecond = normalizer.normalize(
				second, Set.of("cs.AI", "cs.LG"));

		assertThat(normalizedFirst.criteria().categoryIds()).containsExactly("cs.AI", "cs.LG");
		assertThat(normalizedFirst.criteria().titleKeywords()).isEqualTo("graph neural networks");
		assertThat(normalizedFirst.canonicalJson()).isEqualTo(normalizedSecond.canonicalJson());
		assertThat(normalizedFirst.queryHash()).isEqualTo(normalizedSecond.queryHash())
				.matches("[0-9a-f]{64}");
	}

	@Test
	void rejectsUnknownCategoriesRangesAndOversizedPages() {
		assertThatIllegalArgumentException().isThrownBy(() -> normalizer.normalize(
				criteria(List.of("cs.UNKNOWN"), "AI", 1, 20), Set.of("cs.AI")))
				.withMessageContaining("inactive or unknown");
		assertThatIllegalArgumentException().isThrownBy(() -> normalizer.normalize(
				new ArxivSearchCriteria(
						List.of("cs.AI"), ArxivSearchCriteria.CategoryMode.ANY,
						LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-01"),
						null, null, "AI", null, null,
						null, null, null,
						ArxivSearchCriteria.SortBy.RELEVANCE,
						ArxivSearchCriteria.SortOrder.DESCENDING, 1, 20), Set.of("cs.AI")))
				.withMessageContaining("submitted date range");
		assertThatIllegalArgumentException().isThrownBy(() -> normalizer.normalize(
				criteria(List.of("cs.AI"), "AI", 1, 101), Set.of("cs.AI")))
				.withMessageContaining("page size");
	}

	@Test
	void rejectsRawQuerySyntaxAndControlCharacters() {
		for (String unsafe : List.of(
				"AI\" OR all:*", "AI\\escape", "AI\nAND cat:cs.CR", "title:secret")) {
			assertThatIllegalArgumentException().isThrownBy(() -> normalizer.normalize(
					criteria(List.of("cs.AI"), unsafe, 1, 20), Set.of("cs.AI")))
					.withMessageContaining("title keywords");
		}
	}

	private ArxivSearchCriteria criteria(
			List<String> categories, String title, int page, int pageSize
	) {
		return new ArxivSearchCriteria(
				categories, ArxivSearchCriteria.CategoryMode.ANY,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-08-05"),
				null, null, title, null, null,
				null, null, null,
				ArxivSearchCriteria.SortBy.RELEVANCE,
				ArxivSearchCriteria.SortOrder.DESCENDING, page, pageSize);
	}
}
