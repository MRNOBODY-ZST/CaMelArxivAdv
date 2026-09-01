package com.camel_hub.advertisement.email.tracking;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.camel_hub.advertisement.email.tracking.MailTrackingModels.PendingClickLink;

public final class MailLinkRewriter {
	private final MailTrackingSigner signer;
	private final String callbackBaseUrl;
	private final URI callbackOrigin;

	public MailLinkRewriter(MailTrackingSigner signer, String callbackBaseUrl) {
		this.signer = signer;
		this.callbackBaseUrl = callbackBaseUrl;
		this.callbackOrigin = URI.create(callbackBaseUrl);
	}

	public RewriteResult rewrite(String html, UUID recordId, Instant expiresAt) {
		Document document = Jsoup.parseBodyFragment(html);
		document.outputSettings().prettyPrint(false);
		Map<String, LinkDraft> tracked = new LinkedHashMap<>();
		for (Element anchor : document.select("a[href]")) {
			String target = eligible(anchor.attr("href"));
			if (target == null) continue;
			LinkDraft draft = tracked.get(target);
			if (draft == null) {
				UUID linkId = UUID.randomUUID();
				String token = signer.issueClick(recordId, linkId, expiresAt);
				String label = boundedLabel(anchor.text());
				draft = new LinkDraft(token, new PendingClickLink(linkId, target, label, tracked.size() + 1,
						MailTrackingSigner.digest(token), expiresAt));
				tracked.put(target, draft);
			}
			anchor.attr("href", callbackBaseUrl + "/t/c/" + draft.token());
		}
		List<PendingClickLink> links = new ArrayList<>(tracked.size());
		for (LinkDraft draft : tracked.values()) links.add(draft.link());
		return new RewriteResult(document.body().html(), links);
	}

	private String eligible(String value) {
		String target = value == null ? "" : value.strip();
		if (target.isEmpty() || target.length() > 2048) return null;
		try {
			URI uri = URI.create(target);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
					|| uri.getRawFragment() != null || !(scheme.equals("http") || scheme.equals("https"))) return null;
			if (uri.getPath().startsWith("/api/v1/template-assets/")) return null;
			if (sameOrigin(uri, callbackOrigin) && (uri.getPath().equals("/t") || uri.getPath().startsWith("/t/"))) return null;
			return target;
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private boolean sameOrigin(URI left, URI right) {
		return left.getScheme().equalsIgnoreCase(right.getScheme())
				&& left.getHost().equalsIgnoreCase(right.getHost()) && normalizedPort(left) == normalizedPort(right);
	}

	private int normalizedPort(URI uri) {
		if (uri.getPort() >= 0) return uri.getPort();
		return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
	}

	private String boundedLabel(String value) {
		String label = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
		if (label.isEmpty()) return null;
		return label.substring(0, Math.min(label.length(), 255));
	}

	public record RewriteResult(String html, List<PendingClickLink> links) {
		public RewriteResult {
			links = List.copyOf(links);
		}
	}

	private record LinkDraft(String token, PendingClickLink link) { }
}
