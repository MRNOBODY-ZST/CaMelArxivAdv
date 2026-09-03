package com.camel_hub.advertisement.campaign.tracking;

import jakarta.mail.internet.MimeUtility;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Keeps durable final callback capabilities out of campaign management responses. */
public final class CampaignPublicContentRedactor {

	private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String TAIL = "[0-9]{1,19}\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}";
	private static final Pattern SIGNED_CAPABILITY = Pattern.compile(
			"(?:campaign-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-safety-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-safety-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|v1c\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL + ")");
	private final Pattern configuredCallbackPath;

	public CampaignPublicContentRedactor() {
		this.configuredCallbackPath = null;
	}

	public CampaignPublicContentRedactor(String callbackOrigin) {
		String origin = callbackOrigin == null ? "" : callbackOrigin.strip();
		this.configuredCallbackPath = origin.isEmpty() ? null : Pattern.compile(
				Pattern.quote(origin) + "/(?:t/o|t/c|u)/", Pattern.CASE_INSENSITIVE);
	}

	public RedactedContent redact(String subject, String html, String text, boolean trackingArtifactsFrozen) {
		boolean sensitiveSubject = containsCapability(subject, false);
		boolean sensitiveBody = containsCapability(html, true) || containsCapability(text, false);
		boolean redactBodies = trackingArtifactsFrozen || sensitiveBody;
		return new RedactedContent(
				sensitiveSubject ? null : subject,
				redactBodies ? null : html,
				redactBodies ? null : text,
				redactBodies || sensitiveSubject);
	}

	private boolean containsCapability(String value, boolean html) {
		try {
			return inspectionViews(value, html).stream().anyMatch(inspected ->
					SIGNED_CAPABILITY.matcher(inspected).find()
							|| configuredCallbackPath != null && configuredCallbackPath.matcher(inspected).find());
		}
		catch (IllegalArgumentException overEncoded) {
			return true;
		}
	}

	private List<String> inspectionViews(String value, boolean html) {
		String source = value == null ? "" : value;
		if (!html) return List.of(inspectScalar(source));
		var document = Jsoup.parseBodyFragment(source);
		List<String> views = new ArrayList<>();
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

	private String inspectScalar(String value) {
		String decoded = decodeAllEncodings(value);
		if (containsUnsafeControl(value) || containsUnsafeControl(decoded)) {
			throw new IllegalArgumentException("Campaign content inspection failed");
		}
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

	private String decodeAllEncodings(String value) {
		String decoded = value;
		for (int round = 0; round < 8; round++) {
			String next = decodeOneRound(decoded);
			if (next.equals(decoded)) return decoded;
			decoded = next;
		}
		if (!decodeOneRound(decoded).equals(decoded)) {
			throw new IllegalArgumentException("Subject encoding exceeds the inspection bound");
		}
		return decoded;
	}

	private String decodeOneRound(String value) {
		String decoded = Normalizer.normalize(value, Normalizer.Form.NFKC);
		decoded = URLDecoder.decode(escapeInvalidPercents(decoded).replace("+", "%2B"),
				StandardCharsets.UTF_8);
		decoded = Parser.unescapeEntities(decoded, false);
		try {
			decoded = MimeUtility.decodeText(decoded);
		}
		catch (java.io.UnsupportedEncodingException rejected) {
			throw new IllegalArgumentException("Campaign content inspection failed", rejected);
		}
		decoded = Normalizer.normalize(decoded, Normalizer.Form.NFKC);
		if (containsUnsafeControl(decoded)) {
			throw new IllegalArgumentException("Campaign content inspection failed");
		}
		return decoded;
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

	public record RedactedContent(
			String subject, String html, String text, boolean trackingArtifactsRedacted
	) { }
}
