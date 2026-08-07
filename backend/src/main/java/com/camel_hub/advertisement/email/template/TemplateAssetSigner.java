package com.camel_hub.advertisement.email.template;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TemplateAssetSigner {

	private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
			+ "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
	private static final Pattern SIGNED_PATH = Pattern.compile(
			"^/api/v1/template-assets/(" + UUID_PATTERN + ")/(" + UUID_PATTERN + ")"
					+ "/content\\?signature=([A-Za-z0-9_-]{43})$");
	private static final byte[] CONTEXT = "camel-arxiv:template-asset:v1:".getBytes(StandardCharsets.UTF_8);
	private final SecretKeySpec signingKey;
	private final URI publicBaseUrl;

	public TemplateAssetSigner(String keyBase64, String publicBaseUrl) {
		byte[] key;
		try {
			key = Base64.getDecoder().decode(keyBase64 == null ? "" : keyBase64);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Template asset signing key configuration is invalid");
		}
		if (key.length != 32) {
			Arrays.fill(key, (byte) 0);
			throw new IllegalStateException("Template asset signing key configuration is invalid");
		}
		this.signingKey = new SecretKeySpec(Arrays.copyOf(key, key.length), "HmacSHA256");
		Arrays.fill(key, (byte) 0);
		try {
			URI parsed = URI.create(publicBaseUrl == null ? "" : publicBaseUrl.strip());
			if (!parsed.isAbsolute() || parsed.getHost() == null || parsed.getUserInfo() != null
					|| parsed.getQuery() != null || parsed.getFragment() != null
					|| !(parsed.getPath().isEmpty() || parsed.getPath().equals("/"))
					|| !(parsed.getScheme().equalsIgnoreCase("http") || parsed.getScheme().equalsIgnoreCase("https"))) {
				throw new IllegalArgumentException();
			}
			this.publicBaseUrl = parsed;
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Template asset public base URL configuration is invalid");
		}
	}

	public String path(UUID templateId, UUID assetId) {
		return resourcePath(templateId, assetId) + "?signature=" + signature(templateId, assetId);
	}

	String resourcePath(UUID templateId, UUID assetId) {
		return "/api/v1/template-assets/" + templateId + "/" + assetId + "/content";
	}

	String signature(UUID templateId, UUID assetId) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(templateId, assetId));
	}

	public boolean verify(UUID templateId, UUID assetId, String signature) {
		if (signature == null || signature.length() != 43) return false;
		try {
			return MessageDigest.isEqual(mac(templateId, assetId), Base64.getUrlDecoder().decode(signature));
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	public boolean matchesAssetUrl(String value, UUID templateId, UUID assetId) {
		if (value == null) return false;
		try {
			URI uri = URI.create(value);
			if (uri.getFragment() != null || !resourcePath(templateId, assetId).equals(uri.getPath())) return false;
			if (uri.isAbsolute()) {
				if (!uri.getScheme().equalsIgnoreCase(publicBaseUrl.getScheme())
						|| !uri.getHost().equalsIgnoreCase(publicBaseUrl.getHost())
						|| effectivePort(uri) != effectivePort(publicBaseUrl)
						|| uri.getUserInfo() != null) return false;
			}
			else if (uri.getAuthority() != null) {
				return false;
			}
			String query = uri.getRawQuery();
			return query != null && query.startsWith("signature=") && query.indexOf('&') < 0
					&& verify(templateId, assetId, query.substring("signature=".length()));
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	public String absolutizeHtml(String html) {
		Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
		for (Element image : document.select("img[src]")) {
			Matcher matcher = SIGNED_PATH.matcher(image.attr("src"));
			if (!matcher.matches()) continue;
			UUID templateId = UUID.fromString(matcher.group(1));
			UUID assetId = UUID.fromString(matcher.group(2));
			if (verify(templateId, assetId, matcher.group(3))) {
				image.attr("src", publicBaseUrl.resolve(image.attr("src")).toString());
			}
		}
		document.outputSettings().prettyPrint(false);
		return document.body().html();
	}

	static boolean isSignedPath(String value) {
		return value != null && SIGNED_PATH.matcher(value).matches();
	}

	private int effectivePort(URI uri) {
		if (uri.getPort() >= 0) return uri.getPort();
		return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
	}

	private byte[] mac(UUID templateId, UUID assetId) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(signingKey);
			mac.update(CONTEXT);
			return mac.doFinal((templateId + ":" + assetId).getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Template asset signing is unavailable", exception);
		}
	}
}
