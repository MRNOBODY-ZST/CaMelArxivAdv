package com.camel_hub.advertisement.arxiv.search;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ArxivLegacyQueryBuilder {

	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

	public LegacyQuery build(ArxivSearchCriteria criteria) {
		List<String> expressions = new ArrayList<>();
		if (!criteria.categoryIds().isEmpty()) {
			List<String> categoryExpressions = criteria.categoryIds().stream()
					.map(category -> "cat:" + category)
					.toList();
			expressions.add(categoryExpressions.size() == 1
					? categoryExpressions.getFirst()
					: "(" + String.join(" OR ", categoryExpressions) + ")");
		}
		addPhrase(expressions, "ti", criteria.titleKeywords());
		addPhrase(expressions, "abs", criteria.abstractKeywords());
		addPhrase(expressions, "au", criteria.authorKeywords());
		if (criteria.submittedFrom() != null || criteria.submittedTo() != null) {
			expressions.add("submittedDate:[" + start(criteria.submittedFrom())
					+ " TO " + end(criteria.submittedTo()) + "]");
		}
		if (expressions.isEmpty()) {
			throw new IllegalArgumentException("Legacy API query requires an official filter");
		}
		int start = Math.multiplyExact(criteria.page() - 1, criteria.pageSize());
		return new LegacyQuery(
				String.join(" AND ", expressions), start, criteria.pageSize(),
				sortBy(criteria.sortBy()), sortOrder(criteria.sortOrder()));
	}

	private void addPhrase(List<String> expressions, String field, String phrase) {
		if (phrase != null) {
			expressions.add(field + ":\"" + phrase + "\"");
		}
	}

	private String start(LocalDate date) {
		return date == null ? "000101010000" : DATE.format(date) + "0000";
	}

	private String end(LocalDate date) {
		return date == null ? "999912312359" : DATE.format(date) + "2359";
	}

	private String sortBy(ArxivSearchCriteria.SortBy value) {
		return switch (value) {
			case RELEVANCE -> "relevance";
			case LAST_UPDATED_DATE -> "lastUpdatedDate";
			case SUBMITTED_DATE -> "submittedDate";
		};
	}

	private String sortOrder(ArxivSearchCriteria.SortOrder value) {
		return switch (value) {
			case ASCENDING -> "ascending";
			case DESCENDING -> "descending";
		};
	}

	public record LegacyQuery(
			String searchQuery,
			int start,
			int maxResults,
			String sortBy,
			String sortOrder
	) {
	}
}
