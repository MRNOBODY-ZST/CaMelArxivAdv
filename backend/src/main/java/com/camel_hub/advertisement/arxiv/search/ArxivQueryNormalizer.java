package com.camel_hub.advertisement.arxiv.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class ArxivQueryNormalizer {

	private static final Pattern CATEGORY_ID = Pattern.compile("^[A-Za-z0-9.-]{1,80}$");
	private static final int MAXIMUM_KEYWORD_LENGTH = 200;
	private static final int MAXIMUM_PAGE = 100_000;

	private final ObjectMapper objectMapper;
	private final int maximumPageSize;

	public ArxivQueryNormalizer(ObjectMapper objectMapper, int maximumPageSize) {
		this.objectMapper = objectMapper.copy()
				.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
				.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		if (maximumPageSize < 1 || maximumPageSize > 200) {
			throw new IllegalArgumentException("preview maximum page size must be between one and 200");
		}
		this.maximumPageSize = maximumPageSize;
	}

	public NormalizedQuery normalize(ArxivSearchCriteria input, Set<String> activeCategoryIds) {
		Objects.requireNonNull(input, "search criteria are required");
		Set<String> active = Set.copyOf(Objects.requireNonNull(
				activeCategoryIds, "active categories are required"));
		List<String> categories = normalizeCategories(input.categoryIds());
		List<String> invalidCategories = categories.stream()
				.filter(category -> !active.contains(category))
				.toList();
		if (!invalidCategories.isEmpty()) {
			throw new IllegalArgumentException(
					"category is inactive or unknown: " + String.join(", ", invalidCategories));
		}
		validateRange(input.submittedFrom(), input.submittedTo(), "submitted date range");
		validateRange(input.updatedFrom(), input.updatedTo(), "updated date range");
		if (input.page() < 1 || input.page() > MAXIMUM_PAGE) {
			throw new IllegalArgumentException("page must be between one and " + MAXIMUM_PAGE);
		}
		if (input.pageSize() < 1 || input.pageSize() > maximumPageSize) {
			throw new IllegalArgumentException(
					"page size must be between one and " + maximumPageSize);
		}

		ArxivSearchCriteria criteria = new ArxivSearchCriteria(
				categories,
				input.categoryMode() == null ? ArxivSearchCriteria.CategoryMode.ANY : input.categoryMode(),
				input.submittedFrom(), input.submittedTo(), input.updatedFrom(), input.updatedTo(),
				normalizeKeywords(input.titleKeywords(), "title keywords"),
				normalizeKeywords(input.abstractKeywords(), "abstract keywords"),
				normalizeKeywords(input.authorKeywords(), "author keywords"),
				input.hasDoi(), input.hasJournalReference(), input.sourceAvailable(),
				input.sortBy() == null ? ArxivSearchCriteria.SortBy.RELEVANCE : input.sortBy(),
				input.sortOrder() == null ? ArxivSearchCriteria.SortOrder.DESCENDING : input.sortOrder(),
				input.page(), input.pageSize());
		if (!hasOfficialFilter(criteria)) {
			throw new IllegalArgumentException(
					"preview requires a category, keyword, or submitted date filter");
		}
		String canonicalJson = json(criteria);
		return new NormalizedQuery(criteria, canonicalJson, sha256(canonicalJson));
	}

	private List<String> normalizeCategories(List<String> categoryIds) {
		if (categoryIds == null || categoryIds.isEmpty()) {
			return List.of();
		}
		if (categoryIds.size() > 100) {
			throw new IllegalArgumentException("at most 100 categories may be selected");
		}
		Set<String> unique = new LinkedHashSet<>();
		for (String raw : categoryIds) {
			String category = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
			if (!CATEGORY_ID.matcher(category).matches()) {
				throw new IllegalArgumentException("category ID is invalid");
			}
			unique.add(category);
		}
		return unique.stream().sorted(Comparator.naturalOrder()).toList();
	}

	private String normalizeKeywords(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		if (raw.codePoints().anyMatch(Character::isISOControl)
				|| raw.indexOf('\\') >= 0 || raw.indexOf('"') >= 0 || raw.indexOf(':') >= 0) {
			throw new IllegalArgumentException(field + " contains unsupported query syntax");
		}
		String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
				.strip().replaceAll("\\s+", " ");
		if (normalized.length() > MAXIMUM_KEYWORD_LENGTH) {
			throw new IllegalArgumentException(field + " must not exceed 200 characters");
		}
		return normalized;
	}

	private void validateRange(LocalDate from, LocalDate to, String field) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalArgumentException(field + " start must not be after end");
		}
	}

	private boolean hasOfficialFilter(ArxivSearchCriteria criteria) {
		return !criteria.categoryIds().isEmpty()
				|| criteria.titleKeywords() != null
				|| criteria.abstractKeywords() != null
				|| criteria.authorKeywords() != null
				|| criteria.submittedFrom() != null
				|| criteria.submittedTo() != null;
	}

	private String json(ArxivSearchCriteria criteria) {
		try {
			return objectMapper.writeValueAsString(criteria);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("search criteria could not be canonicalized", exception);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	public record NormalizedQuery(
			ArxivSearchCriteria criteria,
			String canonicalJson,
			String queryHash
	) {
	}
}
