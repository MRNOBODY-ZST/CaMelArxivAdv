package com.camel_hub.advertisement.campaign.tracking;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates outbound targets and rewrites only targets persisted by the server. */
public final class CampaignLinkRewriter {

	private static final int MAXIMUM_TARGET_LENGTH = 2048;
	private final URI callbackOrigin;

	public CampaignLinkRewriter(String callbackOrigin) {
		this.callbackOrigin = validateOrigin(callbackOrigin);
	}

	public List<EligibleLink> eligibleLinks(String html, String unsubscribeUrl) {
		Document document = parse(html);
		Map<String, EligibleLink> unique = new LinkedHashMap<>();
		for (Element anchor : document.select("a[href]")) {
			String target = eligible(anchor.attr("href"), unsubscribeUrl);
			if (target == null || unique.containsKey(target)) continue;
			unique.put(target, new EligibleLink(target, boundedLabel(anchor.text())));
		}
		return List.copyOf(unique.values());
	}

	public String rewrite(String html, Map<String, String> tokensByTarget) {
		Document document = parse(html);
		for (Element anchor : document.select("a[href]")) {
			String token = tokensByTarget.get(anchor.attr("href").strip());
			if (token != null) {
				validateOpaqueToken(token);
				anchor.attr("href", callbackOrigin + "/t/c/" + token);
			}
		}
		return document.body().html();
	}

	public boolean safeRedirectTarget(String value) {
		return eligible(value, null) != null;
	}

	private Document parse(String html) {
		Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
		document.outputSettings().prettyPrint(false);
		return document;
	}

	private String eligible(String value, String unsubscribeUrl) {
		String target = value == null ? "" : value.strip();
		if (target.isEmpty() || target.length() > MAXIMUM_TARGET_LENGTH || target.equals(unsubscribeUrl)
				|| !(target.startsWith("http://") || target.startsWith("https://"))) return null;
		try {
			return CampaignRedirectTargetPolicy.requireSafe(target, callbackOrigin.toString());
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private void validateOpaqueToken(String value) {
		if (value == null || value.length() > 512 || !value.matches("[A-Za-z0-9._:-]+")) {
			throw new IllegalArgumentException("Campaign callback token is invalid");
		}
	}

	private boolean validPort(URI uri) {
		return uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65_535;
	}

	private URI validateOrigin(String value) {
		try {
			URI origin = URI.create(value == null ? "" : value);
			String scheme = origin.getScheme();
			if (!origin.isAbsolute() || origin.getHost() == null || origin.getRawUserInfo() != null
					|| origin.getRawQuery() != null || origin.getRawFragment() != null
					|| !origin.getRawPath().isEmpty() || !validPort(origin)
					|| !("https".equals(scheme) || "http".equals(scheme))) throw new IllegalArgumentException();
			return new URI(origin.getScheme(), null, canonicalHost(origin.getHost()), origin.getPort(),
					null, null, null);
		}
		catch (RuntimeException | URISyntaxException exception) {
			throw new IllegalArgumentException("Campaign callback origin is invalid");
		}
	}

	private String canonicalHost(String value) {
		String host = value.toLowerCase(Locale.ROOT);
		while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		return host;
	}

	private String boundedLabel(String value) {
		String label = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
		return label.isEmpty() ? null : label.substring(0, Math.min(label.length(), 255));
	}

	public record EligibleLink(String targetUrl, String label) { }
}
