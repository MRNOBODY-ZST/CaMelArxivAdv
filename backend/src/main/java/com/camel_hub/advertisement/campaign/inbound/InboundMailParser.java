package com.camel_hub.advertisement.campaign.inbound;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic header/DSN classifier; subject and sender are deliberately absent. */
public final class InboundMailParser {
	private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final Pattern CONTROLLED_MESSAGE_ID = Pattern.compile(
			"<(?:safety-)?" + UUID + "@delivery\\.camel-arxiv\\.invalid>");
	private static final Pattern ENHANCED_STATUS = Pattern.compile("[245]\\.[0-9]{1,3}\\.[0-9]{1,3}");
	private static final Pattern SAFE_DIAGNOSTIC = Pattern.compile(
			"^([a-z][a-z0-9-]{0,19})\\s*;.*?\\b([245][0-9]{2})"
					+ "(?:\\s+([245]\\.[0-9]{1,3}\\.[0-9]{1,3}))?\\b.*$");
	private static final int MAX_HEADER_LENGTH = 4_096;
	private static final int MAX_REFERENCES = 20;
	private static final int MAX_DIAGNOSTIC_CODE_POINTS = 80;

	public InboundMailModels.ParsedInbound classify(InboundMailModels.InboundEnvelope envelope) {
		if (envelope == null || envelope.malformed() || oversized(envelope)) return unmatched();
		InboundMailModels.DsnFields dsn = envelope.dsn();
		if (dsn != null) {
			ReferenceScan scan = references(
					dsn.originalMessageId(), envelope.inReplyTo(), envelope.references());
			if (scan.overflow()) return unmatched();
			String action = normalized(dsn.action());
			String status = normalized(dsn.status());
			if (action == null && status == null) return unmatched();
			boolean permanent = "failed".equals(action)
					&& status != null && ENHANCED_STATUS.matcher(status).matches()
					&& status.startsWith("5.");
			return new InboundMailModels.ParsedInbound(
					InboundMailModels.InboundType.BOUNCE, scan.values(),
					diagnostic(dsn.diagnosticCode()), permanent);
		}

		ReferenceScan scan = references(envelope.inReplyTo(), envelope.references());
		if (scan.overflow() || scan.values().isEmpty()) return unmatched();
		String automatic = normalized(envelope.autoSubmitted());
		InboundMailModels.InboundType type = automatic != null && !automatic.equalsIgnoreCase("no")
				? InboundMailModels.InboundType.AUTO_REPLY : InboundMailModels.InboundType.REPLY;
		return new InboundMailModels.ParsedInbound(type, scan.values(), null, null);
	}

	private boolean oversized(InboundMailModels.InboundEnvelope envelope) {
		return tooLong(envelope.messageId()) || tooLong(envelope.inReplyTo())
				|| tooLong(envelope.references()) || tooLong(envelope.autoSubmitted())
				|| tooLong(envelope.contentType());
	}

	private boolean tooLong(String value) {
		return value != null && value.length() > MAX_HEADER_LENGTH;
	}

	private ReferenceScan references(String... values) {
		Set<String> result = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null) continue;
			if (value.length() > MAX_HEADER_LENGTH
					|| value.codePoints().anyMatch(Character::isISOControl)) {
				return new ReferenceScan(List.of(), true);
			}
			Matcher matcher = CONTROLLED_MESSAGE_ID.matcher(value);
			while (matcher.find()) {
				String candidate = matcher.group();
				if (result.contains(candidate)) continue;
				if (result.size() == MAX_REFERENCES) {
					return new ReferenceScan(List.of(), true);
				}
				result.add(candidate);
			}
		}
		return new ReferenceScan(List.copyOf(result), false);
	}

	private String diagnostic(String value) {
		String normalized = normalized(value);
		if (normalized == null || normalized.length() > MAX_HEADER_LENGTH
				|| normalized.codePoints().anyMatch(Character::isISOControl)) return null;
		Matcher matcher = SAFE_DIAGNOSTIC.matcher(normalized);
		if (!matcher.matches()) return null;
		String result = matcher.group(1) + "; " + matcher.group(2)
				+ (matcher.group(3) == null ? "" : " " + matcher.group(3));
		return result.codePointCount(0, result.length()) <= MAX_DIAGNOSTIC_CODE_POINTS ? result : null;
	}

	private String normalized(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
	}

	private InboundMailModels.ParsedInbound unmatched() {
		return new InboundMailModels.ParsedInbound(
				InboundMailModels.InboundType.UNMATCHED, List.of(), null, null);
	}

	private record ReferenceScan(List<String> values, boolean overflow) { }
}
