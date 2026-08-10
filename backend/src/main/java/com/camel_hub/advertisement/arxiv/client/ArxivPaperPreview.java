package com.camel_hub.advertisement.arxiv.client;

import java.time.Instant;
import java.util.List;

public record ArxivPaperPreview(
		String arxivId,
		String title,
		String abstractText,
		List<Author> authors,
		String primaryCategory,
		List<String> categoryIds,
		Instant publishedAt,
		Instant updatedAt,
		String doi,
		String journalReference,
		String comment,
		String licenseUrl,
		String pdfUrl,
		int versionCount
) {

	public record Author(String name, List<String> affiliations) {
	}
}
