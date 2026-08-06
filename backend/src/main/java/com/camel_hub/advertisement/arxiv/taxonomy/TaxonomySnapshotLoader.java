package com.camel_hub.advertisement.arxiv.taxonomy;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class TaxonomySnapshotLoader {

	private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9.-]+$");
	private static final Pattern SHA_256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
	private static final String DEFAULT_RESOURCE = "arxiv/taxonomy-2026-08.json";

	private final ObjectMapper objectMapper;
	private final String configuredResource;

	public TaxonomySnapshotLoader(ObjectMapper objectMapper) {
		this(objectMapper, "classpath:" + DEFAULT_RESOURCE);
	}

	public TaxonomySnapshotLoader(ObjectMapper objectMapper, String configuredResource) {
		this.objectMapper = objectMapper.copy()
				.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
				.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		this.configuredResource = configuredResource;
	}

	public TaxonomySnapshot loadDefault() {
		String resourcePath = configuredResource.startsWith("classpath:")
				? configuredResource.substring("classpath:".length()) : configuredResource;
		try (InputStream input = new ClassPathResource(resourcePath).getInputStream()) {
			return load(input, configuredResource);
		}
		catch (IOException exception) {
			throw new IllegalStateException("cannot load arXiv taxonomy snapshot " + configuredResource, exception);
		}
	}

	public TaxonomySnapshot load(InputStream input, String sourceName) {
		try {
			TaxonomySnapshot snapshot = objectMapper.readValue(input, TaxonomySnapshot.class);
			validate(snapshot);
			return snapshot;
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("invalid arXiv taxonomy snapshot " + sourceName, exception);
		}
	}

	private void validate(TaxonomySnapshot snapshot) {
		requireText(snapshot.snapshotVersion(), "snapshot version");
		if (snapshot.snapshotVersion().length() > 80) {
			throw new IllegalArgumentException("snapshot version is too long");
		}
		if (!"OFFLINE_SNAPSHOT".equals(snapshot.sourceType())) {
			throw new IllegalArgumentException("offline taxonomy source type is invalid");
		}
		if (snapshot.sourceUrls() == null || snapshot.sourceUrls().isEmpty()) {
			throw new IllegalArgumentException("taxonomy source URLs are required");
		}
		if (snapshot.sourceUpdatedAt() == null || snapshot.generatedAt() == null) {
			throw new IllegalArgumentException("taxonomy source and generation timestamps are required");
		}
		if (snapshot.payloadSha256() == null
				|| !SHA_256_PATTERN.matcher(snapshot.payloadSha256()).matches()) {
			throw new IllegalArgumentException("taxonomy payload SHA-256 is invalid");
		}
		List<TaxonomyCategory> categories = snapshot.categories();
		if (categories == null || categories.isEmpty()) {
			throw new IllegalArgumentException("taxonomy categories are required");
		}
		Set<String> categoryIds = new HashSet<>();
		for (TaxonomyCategory category : categories) {
			validateCategory(category);
			if (!categoryIds.add(category.categoryId())) {
				throw new IllegalArgumentException("duplicate category " + category.categoryId());
			}
		}
		for (TaxonomyCategory category : categories) {
			if (category.alias() && !categoryIds.contains(category.aliasTarget())) {
				throw new IllegalArgumentException(
						"category " + category.categoryId() + " has unknown target " + category.aliasTarget());
			}
		}
		String actualHash = sha256(categories);
		if (!MessageDigest.isEqual(
				snapshot.payloadSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
				actualHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
			throw new IllegalArgumentException("taxonomy payload SHA-256 does not match categories");
		}
	}

	private void validateCategory(TaxonomyCategory category) {
		if (category == null) {
			throw new IllegalArgumentException("taxonomy category must not be null");
		}
		requireId(category.groupId(), 40, "group ID");
		requireText(category.groupName(), 120, "group name");
		requireId(category.archiveId(), 40, "archive ID");
		requireText(category.archiveName(), 160, "archive name");
		requireId(category.categoryId(), 80, "category ID");
		requireText(category.categoryName(), 200, "category name");
		if (category.description() == null) {
			throw new IllegalArgumentException("category description must not be null");
		}
		if (category.alias()) {
			requireId(category.aliasTarget(), 80, "alias target");
		}
		else if (category.aliasTarget() != null) {
			throw new IllegalArgumentException("non-alias category must not have an alias target");
		}
	}

	private String sha256(List<TaxonomyCategory> categories) {
		try {
			byte[] canonical = objectMapper.writeValueAsBytes(categories);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
		}
		catch (IOException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("cannot hash taxonomy snapshot", exception);
		}
	}

	private void requireId(String value, int maximumLength, String field) {
		if (value == null || value.length() > maximumLength || !ID_PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " is invalid");
		}
	}

	private void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

	private void requireText(String value, int maximumLength, String field) {
		requireText(value, field);
		if (value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(field + " is invalid");
		}
	}
}
