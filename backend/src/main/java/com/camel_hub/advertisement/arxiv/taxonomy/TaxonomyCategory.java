package com.camel_hub.advertisement.arxiv.taxonomy;

public record TaxonomyCategory(
		String groupId,
		String groupName,
		String archiveId,
		String archiveName,
		String categoryId,
		String categoryName,
		String description,
		boolean alias,
		String aliasTarget
) {
}
