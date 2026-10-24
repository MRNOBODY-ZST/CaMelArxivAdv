package com.camel_hub.advertisement.campaign.partner;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Server-owned late-binding boundary. This class never resolves an invitation. */
public final class PartnerContentPolicy {
	public static final String PLACEHOLDER = "{{partner_claim_url}}";
	public static final String PREVIEW_URL = "https://example.invalid/partner-activation-preview";
	private static final Pattern CLAIM = Pattern.compile("(?i)api\\.camel-hub\\.cn(?::[0-9]+)?/partner/claim");
	private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*partner_claim_url\\s*}}");
	private PartnerContentPolicy() { }

	public static boolean containsCapability(String value) {
		String decoded = value == null ? "" : value;
		for (int round = 0; round < 8; round++) {
			if (CLAIM.matcher(decoded).find()) return true;
			String next = Normalizer.normalize(Parser.unescapeEntities(decoded, false), Normalizer.Form.NFKC);
			try {
				next = URLDecoder.decode(next.replace("+", "%2B").replaceAll("%(?![0-9a-fA-F]{2})", "%25"), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException invalid) { return true; }
			if (next.equals(decoded)) return false;
			decoded = next;
		}
		return true;
	}

	public static boolean optedIn(String html, String text) {
		return html != null && html.contains("partner_claim_url") || text != null && text.contains("partner_claim_url");
	}

	public static void validate(String subject, String html, String text) {
		var document = Jsoup.parseBodyFragment(html == null ? "" : html);
		StringBuilder visible = new StringBuilder();
		appendText(document.body(), visible);
		if (containsCapability(subject) || containsCapability(html) || containsCapability(text)
				|| containsCapability(visible.toString()) || subject != null && subject.contains("partner_claim_url")) throw invalid();
		if (!optedIn(html, text)) return;
		if (html == null || text == null || !html.contains(PLACEHOLDER) || !text.contains(PLACEHOLDER)) throw invalid();
		int accepted = validateNodes(document.body());
		for (var element : document.getAllElements()) {
			for (var attribute : element.attributes()) {
				if (!attribute.getValue().contains("partner_claim_url")) continue;
				if (!element.tagName().equals("a") || !attribute.getKey().equals("href")
						|| !attribute.getValue().equals(PLACEHOLDER)) throw invalid();
				accepted++;
			}
		}
		if (accepted != occurrences(html) || VARIABLE.matcher(html).results().count() != accepted) throw invalid();
		standalone(text);
	}

	private static int validateNodes(Node node) {
		int count = node instanceof TextNode text ? standalone(text.getWholeText()) : 0;
		for (Node child : node.childNodes()) count += validateNodes(child);
		return count;
	}
	private static int standalone(String text) {
		if (VARIABLE.matcher(text).results().count() != occurrences(text)) throw invalid();
		int count = 0;
		for (int start = 0; (start = text.indexOf(PLACEHOLDER, start)) >= 0; start += PLACEHOLDER.length()) {
			int end = start + PLACEHOLDER.length();
			if (start > 0 && !Character.isWhitespace(text.charAt(start - 1))
					&& !("([{\"'".indexOf(text.charAt(start - 1)) >= 0
						&& (start == 1 || Character.isWhitespace(text.charAt(start - 2))))) throw invalid();
			if (end < text.length() && !Character.isWhitespace(text.charAt(end))
					&& ".,;:!?)]}\"'".indexOf(text.charAt(end)) < 0) throw invalid();
			count++;
		}
		return count;
	}
	private static int occurrences(String text) { return (text.length() - text.replace(PLACEHOLDER, "").length()) / PLACEHOLDER.length(); }
	private static void appendText(Node node, StringBuilder text) {
		if (node instanceof TextNode value) text.append(value.getWholeText());
		for (Node child : node.childNodes()) appendText(child, text);
	}
	private static IllegalArgumentException invalid() { return new IllegalArgumentException("Partner activation content is invalid"); }
}
