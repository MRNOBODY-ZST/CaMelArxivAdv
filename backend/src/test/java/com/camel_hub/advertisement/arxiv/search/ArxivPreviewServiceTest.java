package com.camel_hub.advertisement.arxiv.search;

import com.camel_hub.advertisement.arxiv.client.ArxivLegacyClient;
import com.camel_hub.advertisement.arxiv.client.AtomFeed;
import com.camel_hub.advertisement.arxiv.client.AtomFeedParser;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivPreviewServiceTest {

	private ArxivLegacyClient client;
	private ArxivPreviewService service;
	private AtomFeed feed;

	@BeforeEach
	void setUp() throws IOException {
		client = mock(ArxivLegacyClient.class);
		TaxonomyRepository taxonomy = mock(TaxonomyRepository.class);
		when(taxonomy.activeCategoryIds()).thenReturn(Mono.just(Set.of("cs.AI", "cs.LG")));
		feed = new AtomFeedParser().parse(
				getClass().getResourceAsStream("/arxiv/legacy-preview.xml").readAllBytes());
		service = new ArxivPreviewService(
				taxonomy,
				new ArxivQueryNormalizer(new ObjectMapper().findAndRegisterModules(), 100),
				new ArxivLegacyQueryBuilder(),
				client,
				new InMemoryPreviewCache());
	}

	@Test
	void cachesCanonicalQueriesAndMarksDerivedFilters() {
		when(client.fetch(any())).thenReturn(Mono.just(feed));
		ArxivSearchCriteria criteria = criteria(ArxivSearchCriteria.CategoryMode.PRIMARY);

		ArxivPreviewResult first = service.preview(criteria).block();
		ArxivPreviewResult second = service.preview(criteria).block();

		assertThat(first.cacheStatus()).isEqualTo(ArxivPreviewResult.CacheStatus.MISS);
		assertThat(second.cacheStatus()).isEqualTo(ArxivPreviewResult.CacheStatus.HIT);
		assertThat(first.officialTotal()).isEqualTo(42);
		assertThat(first.totalIsEstimate()).isTrue();
		assertThat(first.papers()).hasSize(1);
		assertThat(first.papers().getFirst().arxivId()).isEqualTo("2608.00001");
		assertThat(first.filters()).filteredOn(filter -> filter.field().equals("categoryMode"))
				.singleElement().extracting(ArxivPreviewResult.FilterAnnotation::source)
				.isEqualTo(ArxivPreviewResult.FilterSource.PLATFORM_DERIVED);
		verify(client, times(1)).fetch(any());
	}

	@Test
	void coalescesConcurrentCacheMisses() {
		when(client.fetch(any())).thenReturn(Mono.just(feed).delayElement(Duration.ofMillis(75)));

		Mono.zip(service.preview(criteria(ArxivSearchCriteria.CategoryMode.ANY)),
				service.preview(criteria(ArxivSearchCriteria.CategoryMode.ANY))).block();

		verify(client, times(1)).fetch(any());
	}

	private ArxivSearchCriteria criteria(ArxivSearchCriteria.CategoryMode mode) {
		return new ArxivSearchCriteria(
				List.of("cs.AI"), mode,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-08-05"),
				LocalDate.parse("2026-08-01"), null,
				null, null, null, true, null, true,
				ArxivSearchCriteria.SortBy.RELEVANCE,
				ArxivSearchCriteria.SortOrder.DESCENDING, 1, 20);
	}

	private static final class InMemoryPreviewCache implements ArxivPreviewCache {
		private final ConcurrentHashMap<String, AtomFeed> values = new ConcurrentHashMap<>();

		@Override
		public Mono<AtomFeed> get(String key) {
			return Mono.justOrEmpty(values.get(key));
		}

		@Override
		public Mono<Void> put(String key, AtomFeed value) {
			values.put(key, value);
			return Mono.empty();
		}
	}
}
