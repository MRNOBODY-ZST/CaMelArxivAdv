package com.camel_hub.advertisement.campaign;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SegmentModels {

	private static final Pattern CATEGORY = Pattern.compile("[A-Za-z0-9.-]{2,80}");
	private static final Set<String> CONFIDENCE = Set.of("HIGH", "MEDIUM");
	private static final Set<String> VERIFICATION = Set.of("UNVERIFIED", "CONFIRMED");

	private SegmentModels() { }

	public static SegmentCriteria criteria(List<RuleInput> rules) {
		if (rules == null || rules.isEmpty() || rules.size() > 4) {
			throw new SegmentValidationException("A segment must contain between 1 and 4 rules");
		}
		String category = null;
		String confidence = null;
		String verification = null;
		Boolean corresponding = null;
		Set<String> seen = new HashSet<>();
		for (RuleInput rule : rules) {
			if (rule == null || rule.field() == null || rule.operator() == null || rule.value() == null) {
				throw new SegmentValidationException("Segment rules must contain field, operator, and value");
			}
			String field = rule.field().strip();
			if (!rule.operator().strip().equals("equals")) {
				throw new SegmentValidationException("Segment rules support only the equals operator");
			}
			if (!seen.add(field)) {
				throw new SegmentValidationException("A segment field may be used only once: " + field);
			}
			switch (field) {
				case "primaryCategory" -> category = category(rule.value());
				case "confidence" -> confidence = enumValue(rule.value(), CONFIDENCE, "confidence");
				case "verificationStatus" ->
						verification = enumValue(rule.value(), VERIFICATION, "verification status");
				case "corresponding" -> corresponding = booleanValue(rule.value());
				default -> throw new SegmentValidationException("Unsupported segment field: " + field);
			}
		}
		return new SegmentCriteria(category, confidence, verification, corresponding);
	}

	private static String category(Object value) {
		String text = textValue(value);
		if (text == null || !CATEGORY.matcher(text).matches()) {
			throw new SegmentValidationException("Primary category must be a valid arXiv category identifier");
		}
		return text;
	}

	private static String enumValue(Object value, Set<String> allowed, String label) {
		String text = textValue(value);
		String normalized = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
		if (!allowed.contains(normalized)) {
			throw new SegmentValidationException("Unsupported " + label + " value");
		}
		return normalized;
	}

	private static boolean booleanValue(Object value) {
		if (value instanceof Boolean bool) return bool;
		if (!(value instanceof JsonNode node) || !node.isBoolean()) {
			throw new SegmentValidationException("Corresponding-author value must be true or false");
		}
		return node.booleanValue();
	}

	private static String textValue(Object value) {
		if (value instanceof String text) return text;
		if (value instanceof JsonNode node && node.isTextual()) return node.textValue();
		return null;
	}

	public record RuleInput(String field, String operator, Object value) { }

	public record SegmentCriteria(
			String primaryCategory,
			String confidence,
			String verificationStatus,
			Boolean corresponding
	) { }
}
