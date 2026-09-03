package com.camel_hub.advertisement.campaign.safety;

import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.InternetAddress;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Collection;
import java.util.regex.Pattern;

/** Strict source boundary that prevents logical addresses and callback capabilities entering safety snapshots. */
final class CampaignSafetyContentPolicy {
	private static final String PLACEHOLDER = "{{unsubscribe_url}}";
	private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String TAIL = "[0-9]{1,19}\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}";
	private static final Pattern SIGNED_CAPABILITY = Pattern.compile(
			"(?:campaign-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-safety-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-safety-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|v1c\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL + ")");
	private static final Pattern FOREIGN_CAPABILITY = Pattern.compile(
			"(?:campaign-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|(?<!:)v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|(?<!:)v1c\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL + ")");
	private static final Pattern EMAIL = Pattern.compile(
			"(?i)(?<![A-Z0-9.!#$%&'*+/=?^_`{|}~-])[A-Z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}"
					+ "@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9-])");

	void validateSource(String subject, String html, String text) {
		if (subject == null || subject.isBlank() || html == null || html.isBlank()
				|| text == null || text.isBlank()) throw invalid();
		validateHtmlPlaceholders(html);
		if (validateStandalonePlaceholders(text) != 1) throw invalid();
		if (containsForbidden(subject, false) || containsForbidden(html, true)
				|| containsForbidden(text, false)) throw invalid();
	}

	boolean containsForbidden(String value, boolean html) {
		try {
			return inspectionViews(value, html).stream().anyMatch(inspected ->
					SIGNED_CAPABILITY.matcher(inspected).find() || EMAIL.matcher(inspected).find());
		}
		catch (IllegalArgumentException unsafe) {
			return true;
		}
	}

	boolean containsAddress(String value, boolean html) {
		try {
			return inspectionViews(value, html).stream().anyMatch(inspected -> EMAIL.matcher(inspected).find());
		}
		catch (IllegalArgumentException unsafe) {
			return true;
		}
	}

	boolean containsCapability(String value, boolean html) {
		try {
			return inspectionViews(value, html).stream()
					.anyMatch(inspected -> SIGNED_CAPABILITY.matcher(inspected).find());
		}
		catch (IllegalArgumentException unsafe) {
			return true;
		}
	}

	boolean containsForeignCapability(String value, boolean html) {
		try {
			return inspectionViews(value, html).stream()
					.anyMatch(inspected -> FOREIGN_CAPABILITY.matcher(inspected).find());
		}
		catch (IllegalArgumentException unsafe) {
			return true;
		}
	}

	boolean containsUnexpectedCapability(String value, boolean html, Collection<String> allowedLiteralTokens) {
		try {
			String normalized = inspect(value, html);
			String remaining = normalized;
			for (String token : allowedLiteralTokens) {
				if (token == null || token.isEmpty()
						|| occurrences(normalized, token) != occurrences(value, token)) return true;
				remaining = remaining.replace(token, "");
			}
			return SIGNED_CAPABILITY.matcher(remaining).find();
		}
		catch (IllegalArgumentException unsafe) {
			return true;
		}
	}

	boolean containsForbiddenSenderMetadata(String fromName, String fromEmail, String replyTo) {
		return containsForbidden(fromName, false)
				|| !strictMailbox(fromEmail) || !strictMailbox(replyTo)
				|| containsCapability(fromEmail, false) || containsCapability(replyTo, false);
	}

	private boolean strictMailbox(String value) {
		if (value == null || value.isBlank() || value.length() > 320
				|| value.codePoints().anyMatch(Character::isISOControl)) return false;
		try {
			InternetAddress parsed = new InternetAddress(value, true);
			return value.equals(parsed.getAddress()) && parsed.getPersonal() == null;
		}
		catch (Exception rejected) {
			return false;
		}
	}

	boolean containsCapabilityJoinedAcrossHtmlNodes(String value) {
		try {
			var document = Jsoup.parseBodyFragment(value == null ? "" : value);
			StringBuilder visible = new StringBuilder();
			appendVisibleText(document.body(), visible);
			long joined = SIGNED_CAPABILITY.matcher(inspectScalar(visible.toString())).results().count();
			long atomic = textNodes(document.body()).stream()
					.map(this::inspectScalar)
					.mapToLong(text -> SIGNED_CAPABILITY.matcher(text).results().count())
					.sum();
			return joined != atomic;
		}
		catch (IllegalArgumentException unsafe) {
			return true;
		}
	}

	private void validateHtmlPlaceholders(String html) {
		var document = Jsoup.parseBodyFragment(html);
		int accepted = 0;
		for (var element : document.getAllElements()) {
			for (var attribute : element.attributes()) {
				String value = attribute.getValue();
				if (!value.contains(PLACEHOLDER)) continue;
				if (!value.equals(PLACEHOLDER)) throw invalid();
				accepted++;
			}
		}
		accepted += validatePlaceholderTextNodes(document.body());
		if (accepted != 1 || accepted != occurrences(html, PLACEHOLDER)) throw invalid();
	}

	private int validatePlaceholderTextNodes(Node node) {
		int accepted = node instanceof TextNode text ? validateStandalonePlaceholders(text.getWholeText()) : 0;
		for (Node child : node.childNodes()) accepted += validatePlaceholderTextNodes(child);
		return accepted;
	}

	private int validateStandalonePlaceholders(String value) {
		int accepted = 0;
		for (int offset = 0; value != null && (offset = value.indexOf(PLACEHOLDER, offset)) >= 0;
				offset += PLACEHOLDER.length()) {
			int end = offset + PLACEHOLDER.length();
			boolean safeLeft = offset == 0 || standaloneLeft(value, offset);
			boolean safeRight = end == value.length() || standaloneRight(value.charAt(end));
			if (!safeLeft || !safeRight) throw invalid();
			accepted++;
		}
		return accepted;
	}

	private boolean standaloneLeft(String value, int offset) {
		char boundary = value.charAt(offset - 1);
		if (Character.isWhitespace(boundary)) return true;
		if ("([{<\"'".indexOf(boundary) < 0) return false;
		return offset == 1 || Character.isWhitespace(value.charAt(offset - 2));
	}

	private boolean standaloneRight(char value) {
		return Character.isWhitespace(value) || ".,;:!?)]}>\"'".indexOf(value) >= 0;
	}

	String inspect(String value, boolean html) {
		String source = value == null ? "" : value;
		if (html) source = Parser.unescapeEntities(
				Jsoup.parseBodyFragment(source).body().html(), false);
		return inspectScalar(source);
	}

	private List<String> inspectionViews(String value, boolean html) {
		if (!html) return List.of(inspect(value, false));
		var document = Jsoup.parseBodyFragment(value == null ? "" : value);
		List<String> views = new java.util.ArrayList<>();
		views.add(inspectScalar(Parser.unescapeEntities(document.body().html(), false)));
		StringBuilder visible = new StringBuilder();
		appendVisibleText(document.body(), visible);
		views.add(inspectScalar(visible.toString()));
		for (var element : document.getAllElements()) {
			for (var attribute : element.attributes()) views.add(inspectScalar(attribute.getValue()));
		}
		return List.copyOf(views);
	}

	private void appendVisibleText(Node node, StringBuilder target) {
		if (node instanceof TextNode text) target.append(text.getWholeText());
		for (Node child : node.childNodes()) appendVisibleText(child, target);
	}

	private List<String> textNodes(Node node) {
		List<String> values = new java.util.ArrayList<>();
		collectTextNodes(node, values);
		return List.copyOf(values);
	}

	private void collectTextNodes(Node node, List<String> target) {
		if (node instanceof TextNode text) target.add(text.getWholeText());
		for (Node child : node.childNodes()) collectTextNodes(child, target);
	}

	private String inspectScalar(String source) {
		String decoded = decodeAllEncodings(source);
		if (containsUnsafeControl(source) || containsUnsafeControl(decoded)) throw invalid();
		return decoded;
	}

	private String decodeAllEncodings(String value) {
		String decoded = value;
		for (int round = 0; round < 8; round++) {
			String next = decodeOneRound(decoded);
			if (next.equals(decoded)) return decoded;
			decoded = next;
		}
		if (!decodeOneRound(decoded).equals(decoded)) throw invalid();
		return decoded;
	}

	private String decodeOneRound(String value) {
		String decoded = Normalizer.normalize(value, Normalizer.Form.NFKC);
		decoded = decodeRepeatedly(decoded);
		decoded = Parser.unescapeEntities(decoded, false);
		try {
			decoded = MimeUtility.decodeText(decoded);
		}
		catch (java.io.UnsupportedEncodingException rejected) {
			throw invalid();
		}
		decoded = Normalizer.normalize(decoded, Normalizer.Form.NFKC);
		if (containsUnsafeControl(decoded)) throw invalid();
		return decoded;
	}

	private boolean containsUnsafeControl(String value) {
		for (int offset = 0; offset < value.length();) {
			int codePoint = value.codePointAt(offset);
			if ((Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT)
					&& codePoint != '\n' && codePoint != '\r' && codePoint != '\t') return true;
			offset += Character.charCount(codePoint);
		}
		return false;
	}

	private String decodeRepeatedly(String value) {
		String decoded = value;
		try {
			for (int round = 0; round < 5; round++) {
				String next = URLDecoder.decode(escapeInvalidPercents(decoded).replace("+", "%2B"),
						StandardCharsets.UTF_8);
				if (next.equals(decoded)) return decoded;
				decoded = next;
			}
			if (containsValidPercentEscape(decoded)) throw invalid();
			return decoded;
		}
		catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private String escapeInvalidPercents(String value) {
		StringBuilder safe = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (current == '%' && (index + 2 >= value.length()
					|| Character.digit(value.charAt(index + 1), 16) < 0
					|| Character.digit(value.charAt(index + 2), 16) < 0)) safe.append("%25");
			else safe.append(current);
		}
		return safe.toString();
	}

	private boolean containsValidPercentEscape(String value) {
		for (int index = 0; index + 2 < value.length(); index++) {
			if (value.charAt(index) == '%' && Character.digit(value.charAt(index + 1), 16) >= 0
					&& Character.digit(value.charAt(index + 2), 16) >= 0) return true;
		}
		return false;
	}

	private int occurrences(String value, String needle) {
		int count = 0;
		for (int offset = 0; value != null && (offset = value.indexOf(needle, offset)) >= 0;
				offset += needle.length()) count++;
		return count;
	}

	private IllegalArgumentException invalid() {
		return new IllegalArgumentException("Campaign safety content is invalid");
	}
}
