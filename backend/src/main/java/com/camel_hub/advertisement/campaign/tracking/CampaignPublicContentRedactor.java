package com.camel_hub.advertisement.campaign.tracking;

import org.jsoup.Jsoup;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Keeps durable final callback capabilities out of campaign management responses. */
public final class CampaignPublicContentRedactor {

	private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String TAIL = "[0-9]{1,19}\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}";
	private static final Pattern SIGNED_CAPABILITY = Pattern.compile(
			"(?:campaign-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
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
			String source = value == null ? "" : value;
			String inspected = decodeRepeatedly(html ? Jsoup.parseBodyFragment(source).body().html() : source);
			return SIGNED_CAPABILITY.matcher(inspected).find()
					|| configuredCallbackPath != null && configuredCallbackPath.matcher(inspected).find();
		}
		catch (IllegalArgumentException overEncoded) {
			return true;
		}
	}

	private String decodeRepeatedly(String value) {
		String decoded = value;
		for (int round = 0; round < 5; round++) {
			String next = URLDecoder.decode(escapeInvalidPercents(decoded).replace("+", "%2B"),
					StandardCharsets.UTF_8);
			if (next.equals(decoded)) return decoded;
			decoded = next;
		}
		if (containsValidPercentEscape(decoded)) {
			throw new IllegalArgumentException("Subject encoding exceeds the inspection bound");
		}
		return decoded;
	}

	private boolean containsValidPercentEscape(String value) {
		for (int index = 0; index + 2 < value.length(); index++) {
			if (value.charAt(index) == '%' && Character.digit(value.charAt(index + 1), 16) >= 0
					&& Character.digit(value.charAt(index + 2), 16) >= 0) return true;
		}
		return false;
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
