package com.camel_hub.advertisement.arxiv.client;

import java.util.List;

public record AtomFeed(
		long totalResults,
		int startIndex,
		int itemsPerPage,
		List<ArxivPaperPreview> papers
) {
}
