package com.camel_hub.advertisement.arxiv.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ArxivTaxonomyDtos {

	private ArxivTaxonomyDtos() {
	}

	public record TaxonomyResponse(
			String snapshotVersion,
			String sourceType,
			List<String> sourceUrls,
			Instant sourceUpdatedAt,
			Instant syncedAt,
			List<GroupResponse> groups
	) {
	}

	public record GroupResponse(String groupId, String groupName, List<ArchiveResponse> archives) {
	}

	public record ArchiveResponse(
			String archiveId, String archiveName, List<CategoryResponse> categories
	) {
	}

	public record CategoryResponse(
			String categoryId,
			String categoryName,
			String description,
			boolean alias,
			String aliasTarget
	) {
	}

	public record TaxonomySyncResponse(UUID jobId, String status, boolean created) {
	}
}
