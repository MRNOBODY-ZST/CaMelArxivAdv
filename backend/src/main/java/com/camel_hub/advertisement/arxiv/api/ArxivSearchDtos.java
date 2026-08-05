package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public final class ArxivSearchDtos {

	private ArxivSearchDtos() {
	}

	public record PreviewRequest(
			@Size(max = 100) List<@Pattern(regexp = "[A-Za-z0-9.-]{1,80}") String> categoryIds,
			ArxivSearchCriteria.CategoryMode categoryMode,
			LocalDate submittedFrom,
			LocalDate submittedTo,
			LocalDate updatedFrom,
			LocalDate updatedTo,
			@Size(max = 200) String titleKeywords,
			@Size(max = 200) String abstractKeywords,
			@Size(max = 200) String authorKeywords,
			Boolean hasDoi,
			Boolean hasJournalReference,
			Boolean sourceAvailable,
			ArxivSearchCriteria.SortBy sortBy,
			ArxivSearchCriteria.SortOrder sortOrder,
			@Min(1) @Max(100_000) Integer page,
			@Min(1) @Max(200) Integer pageSize
	) {
		ArxivSearchCriteria criteria() {
			return new ArxivSearchCriteria(
					categoryIds == null ? List.of() : categoryIds,
					categoryMode, submittedFrom, submittedTo, updatedFrom, updatedTo,
					titleKeywords, abstractKeywords, authorKeywords,
					hasDoi, hasJournalReference, sourceAvailable, sortBy, sortOrder,
					page == null ? 1 : page, pageSize == null ? 20 : pageSize);
		}
	}
}
