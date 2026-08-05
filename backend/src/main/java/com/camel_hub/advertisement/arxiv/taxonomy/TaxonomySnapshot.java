package com.camel_hub.advertisement.arxiv.taxonomy;

import java.time.Instant;
import java.util.List;

public record TaxonomySnapshot(
		String snapshotVersion,
		String sourceType,
		List<String> sourceUrls,
		Instant sourceUpdatedAt,
		Instant generatedAt,
		String payloadSha256,
		List<TaxonomyCategory> categories
) {
}
