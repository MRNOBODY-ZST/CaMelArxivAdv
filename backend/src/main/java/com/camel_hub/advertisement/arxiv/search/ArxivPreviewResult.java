package com.camel_hub.advertisement.arxiv.search;

import com.camel_hub.advertisement.arxiv.client.ArxivPaperPreview;

import java.util.List;

public record ArxivPreviewResult(
		String queryHash,
		ArxivSearchCriteria criteria,
		long officialTotal,
		boolean totalIsEstimate,
		int page,
		int pageSize,
		CacheStatus cacheStatus,
		List<FilterAnnotation> filters,
		List<ArxivPaperPreview> papers
) {

	public enum CacheStatus {
		HIT,
		MISS,
		COALESCED
	}

	public enum FilterSource {
		OFFICIAL,
		PLATFORM_DERIVED
	}

	public record FilterAnnotation(
			String field,
			FilterSource source,
			boolean appliedToPreview,
			String description
	) {
	}
}
