package com.camel_hub.advertisement.campaign.tracking;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/** Shared trust boundary for redirect targets returned by any campaign callback namespace. */
public final class CampaignRedirectTargetPolicy {

	private static final int MAXIMUM_TARGET_LENGTH = 2048;

	private CampaignRedirectTargetPolicy() { }

	public static String requireSafe(String value) {
		String target = value == null ? "" : value.strip();
		if (target.isEmpty() || !target.equals(value) || target.length() > MAXIMUM_TARGET_LENGTH
				|| containsControl(target) || target.indexOf('\\') >= 0) {
			throw new IllegalArgumentException("Campaign redirect target is invalid");
		}
		try {
			URI uri = URI.create(target);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			String decodedPath = fullyDecode(uri.getRawPath() == null ? "" : uri.getRawPath());
			String decodedAuthority = fullyDecode(uri.getRawAuthority() == null ? "" : uri.getRawAuthority());
			String decodedQuery = fullyDecode(uri.getRawQuery() == null ? "" : uri.getRawQuery());
			if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
					|| uri.getRawFragment() != null || !(scheme.equals("http") || scheme.equals("https"))
					|| !validPort(uri)
					|| containsControl(decodedPath) || containsControl(decodedAuthority)
					|| containsControl(decodedQuery) || decodedPath.indexOf('\\') >= 0
					|| decodedAuthority.indexOf('@') >= 0) {
				throw new IllegalArgumentException("Campaign redirect target is invalid");
			}
			return target;
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Campaign redirect target is invalid");
		}
	}

	public static String requireSafe(String value, String callbackOrigin) {
		String target = requireSafe(value);
		try {
			URI targetUri = URI.create(target);
			URI origin = requireOrigin(callbackOrigin);
			String decodedPath = fullyDecode(targetUri.getRawPath() == null ? "" : targetUri.getRawPath());
			String path = normalizedPath(decodedPath);
			if (templateAssetPath(decodedPath) || templateAssetPath(path)
					|| sameOrigin(targetUri, origin) && capabilityPath(path)) {
				throw new IllegalArgumentException("Campaign redirect target is invalid");
			}
			return target;
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Campaign redirect target is invalid");
		}
	}

	private static boolean templateAssetPath(String path) {
		return path.equals("/api/v1/template-assets") || path.startsWith("/api/v1/template-assets/");
	}

	public static boolean isSafe(String value) {
		try {
			requireSafe(value);
			return true;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	public static boolean isSafe(String value, String callbackOrigin) {
		try {
			requireSafe(value, callbackOrigin);
			return true;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static boolean validPort(URI uri) {
		return uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65_535;
	}

	private static URI requireOrigin(String value) {
		URI origin = URI.create(value == null ? "" : value);
		if (!origin.isAbsolute() || origin.getHost() == null || origin.getRawUserInfo() != null
				|| origin.getRawQuery() != null || origin.getRawFragment() != null
				|| !origin.getRawPath().isEmpty() || !validPort(origin)
				|| !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))) {
			throw new IllegalArgumentException("Campaign redirect target is invalid");
		}
		return origin;
	}

	private static boolean sameOrigin(URI left, URI right) {
		return left.getScheme().equalsIgnoreCase(right.getScheme())
				&& canonicalHost(left.getHost()).equals(canonicalHost(right.getHost()))
				&& normalizedPort(left) == normalizedPort(right);
	}

	private static int normalizedPort(URI uri) {
		if (uri.getPort() >= 0) return uri.getPort();
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static String canonicalHost(String value) {
		String host = value.toLowerCase(Locale.ROOT);
		while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		return host;
	}

	private static boolean capabilityPath(String path) {
		return path.equals("/t") || path.startsWith("/t/") || path.equals("/u") || path.startsWith("/u/");
	}

	private static String normalizedPath(String path) {
		ArrayDeque<String> segments = new ArrayDeque<>();
		for (String segment : path.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".")) continue;
			if (segment.equals("..")) {
				if (!segments.isEmpty()) segments.removeLast();
			}
			else segments.addLast(segment);
		}
		return "/" + String.join("/", segments);
	}

	private static String fullyDecode(String value) {
		String decoded = value;
		for (int round = 0; round < 5; round++) {
			String next = URLDecoder.decode(escapeInvalidPercents(decoded).replace("+", "%2B"),
					StandardCharsets.UTF_8);
			if (next.equals(decoded)) return decoded;
			decoded = next;
		}
		if (containsValidPercentEscape(decoded)) {
			throw new IllegalArgumentException("Campaign redirect target is invalid");
		}
		return decoded;
	}

	private static String escapeInvalidPercents(String value) {
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

	private static boolean containsValidPercentEscape(String value) {
		for (int index = 0; index + 2 < value.length(); index++) {
			if (value.charAt(index) == '%' && Character.digit(value.charAt(index + 1), 16) >= 0
					&& Character.digit(value.charAt(index + 2), 16) >= 0) return true;
		}
		return false;
	}

	private static boolean containsControl(String value) {
		return value.codePoints().anyMatch(Character::isISOControl);
	}
}
