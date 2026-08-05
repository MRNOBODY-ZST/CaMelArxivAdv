package com.camel_hub.advertisement.arxiv.search;

import java.time.LocalDate;
import java.util.List;

public record ArxivSearchCriteria(
		List<String> categoryIds,
		CategoryMode categoryMode,
		LocalDate submittedFrom,
		LocalDate submittedTo,
		LocalDate updatedFrom,
		LocalDate updatedTo,
		String titleKeywords,
		String abstractKeywords,
		String authorKeywords,
		Boolean hasDoi,
		Boolean hasJournalReference,
		Boolean sourceAvailable,
		SortBy sortBy,
		SortOrder sortOrder,
		int page,
		int pageSize
) {

	public enum CategoryMode {
		ANY,
		PRIMARY,
		CROSS_LIST
	}

	public enum SortBy {
		RELEVANCE,
		LAST_UPDATED_DATE,
		SUBMITTED_DATE
	}

	public enum SortOrder {
		ASCENDING,
		DESCENDING
	}
}
