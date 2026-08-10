package com.camel_hub.advertisement.arxiv.search;

import com.camel_hub.advertisement.arxiv.client.ArxivLegacyClient;
import com.camel_hub.advertisement.arxiv.client.ArxivPaperPreview;
import com.camel_hub.advertisement.arxiv.client.AtomFeed;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ArxivPreviewService {

	private final TaxonomyRepository taxonomyRepository;
	private final ArxivQueryNormalizer normalizer;
	private final ArxivLegacyQueryBuilder queryBuilder;
	private final ArxivLegacyClient client;
	private final ArxivPreviewCache cache;
	private final ConcurrentHashMap<String, Mono<AtomFeed>> inFlight = new ConcurrentHashMap<>();

	public ArxivPreviewService(
			TaxonomyRepository taxonomyRepository,
			ArxivQueryNormalizer normalizer,
			ArxivLegacyQueryBuilder queryBuilder,
			ArxivLegacyClient client,
			ArxivPreviewCache cache
	) {
		this.taxonomyRepository = taxonomyRepository;
		this.normalizer = normalizer;
		this.queryBuilder = queryBuilder;
		this.client = client;
		this.cache = cache;
	}

	public Mono<ArxivPreviewResult> preview(ArxivSearchCriteria input) {
		return taxonomyRepository.activeCategoryIds()
				.map(categories -> normalizer.normalize(input, categories))
				.flatMap(normalized -> cache.get(normalized.queryHash())
						.map(feed -> new LoadedFeed(feed, ArxivPreviewResult.CacheStatus.HIT))
						.switchIfEmpty(Mono.defer(() -> load(normalized)))
						.map(loaded -> result(normalized, loaded)));
	}

	private Mono<LoadedFeed> load(ArxivQueryNormalizer.NormalizedQuery normalized) {
		Mono<AtomFeed> created = Mono.defer(() -> client.fetch(queryBuilder.build(normalized.criteria())))
				.flatMap(feed -> cache.put(normalized.queryHash(), feed).thenReturn(feed))
				.cache();
		Mono<AtomFeed> existing = inFlight.putIfAbsent(normalized.queryHash(), created);
		if (existing != null) {
			return existing.map(feed -> new LoadedFeed(feed, ArxivPreviewResult.CacheStatus.COALESCED));
		}
		return created
				.map(feed -> new LoadedFeed(feed, ArxivPreviewResult.CacheStatus.MISS))
				.doFinally(signal -> inFlight.remove(normalized.queryHash(), created));
	}

	private ArxivPreviewResult result(
			ArxivQueryNormalizer.NormalizedQuery normalized,
			LoadedFeed loaded
	) {
		ArxivSearchCriteria criteria = normalized.criteria();
		List<ArxivPaperPreview> filtered = loaded.feed().papers().stream()
				.filter(paper -> categoryModeMatches(criteria, paper))
				.filter(paper -> dateMatches(criteria.updatedFrom(), criteria.updatedTo(), paper.updatedAt()
						.atZone(ZoneOffset.UTC).toLocalDate()))
				.filter(paper -> presenceMatches(criteria.hasDoi(), paper.doi()))
				.filter(paper -> presenceMatches(criteria.hasJournalReference(), paper.journalReference()))
				.toList();
		return new ArxivPreviewResult(
				normalized.queryHash(), criteria, loaded.feed().totalResults(), hasDerivedCriteria(criteria),
				criteria.page(), criteria.pageSize(), loaded.status(), annotations(criteria), filtered);
	}

	private boolean categoryModeMatches(ArxivSearchCriteria criteria, ArxivPaperPreview paper) {
		return switch (criteria.categoryMode()) {
			case ANY -> true;
			case PRIMARY -> criteria.categoryIds().contains(paper.primaryCategory());
			case CROSS_LIST -> !criteria.categoryIds().contains(paper.primaryCategory())
					&& paper.categoryIds().stream().anyMatch(criteria.categoryIds()::contains);
		};
	}

	private boolean dateMatches(LocalDate from, LocalDate to, LocalDate value) {
		return (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to));
	}

	private boolean presenceMatches(Boolean expected, String value) {
		if (expected == null) {
			return true;
		}
		boolean present = value != null && !value.isBlank();
		return expected == present;
	}

	private boolean hasDerivedCriteria(ArxivSearchCriteria criteria) {
		return criteria.categoryMode() != ArxivSearchCriteria.CategoryMode.ANY
				|| criteria.updatedFrom() != null || criteria.updatedTo() != null
				|| criteria.hasDoi() != null || criteria.hasJournalReference() != null
				|| criteria.sourceAvailable() != null;
	}

	private List<ArxivPreviewResult.FilterAnnotation> annotations(ArxivSearchCriteria criteria) {
		List<ArxivPreviewResult.FilterAnnotation> filters = new ArrayList<>();
		filters.add(official("categoryIds", "arXiv Category query"));
		filters.add(official("submittedAt", "arXiv submittedDate query"));
		filters.add(official("titleKeywords", "arXiv title query"));
		filters.add(official("abstractKeywords", "arXiv abstract query"));
		filters.add(official("authorKeywords", "arXiv author query"));
		filters.add(derived("categoryMode", true, "Filtered from primary and cross-list metadata in this page"));
		filters.add(derived("updatedAt", true, "Filtered from updated timestamps in this page"));
		filters.add(derived("hasDoi", true, "Filtered from DOI metadata in this page"));
		filters.add(derived("hasJournalReference", true,
				"Filtered from journal reference metadata in this page"));
		filters.add(derived("sourceAvailable", false,
				"Source availability is evaluated after metadata import"));
		return List.copyOf(filters);
	}

	private ArxivPreviewResult.FilterAnnotation official(String field, String description) {
		return new ArxivPreviewResult.FilterAnnotation(
				field, ArxivPreviewResult.FilterSource.OFFICIAL, true, description);
	}

	private ArxivPreviewResult.FilterAnnotation derived(
			String field, boolean applied, String description
	) {
		return new ArxivPreviewResult.FilterAnnotation(
				field, ArxivPreviewResult.FilterSource.PLATFORM_DERIVED, applied, description);
	}

	private record LoadedFeed(AtomFeed feed, ArxivPreviewResult.CacheStatus status) {
	}
}
