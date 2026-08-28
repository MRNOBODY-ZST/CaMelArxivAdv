package com.camel_hub.advertisement.email.tracking;

import io.netty.util.NetUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

@ConfigurationProperties("app.mail-tracking")
public record MailTrackingProperties(
		boolean enabled, String publicBaseUrl, String signingKeyBase64, Duration tokenTtl
) {
	public MailTrackingProperties {
		if (tokenTtl == null) tokenTtl = Duration.ofDays(30);
		if (tokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || tokenTtl.compareTo(Duration.ofDays(90)) > 0
				|| tokenTtl.getNano() != 0) {
			throw new IllegalArgumentException("Tracking token TTL must be whole seconds between one minute and 90 days");
		}
		publicBaseUrl = validateOrigin(publicBaseUrl);
		if (enabled) {
			byte[] key = decodeKey(signingKeyBase64);
			Arrays.fill(key, (byte) 0);
		}
	}

	public MailTrackingModels.CallbackScope callbackScope() {
		return isLocalHost(URI.create(publicBaseUrl).getHost())
				? MailTrackingModels.CallbackScope.LOCAL_ONLY
				: MailTrackingModels.CallbackScope.PUBLIC_HTTPS_UNVERIFIED;
	}

	static byte[] decodeKey(String encoded) {
		try {
			byte[] key = Base64.getDecoder().decode(encoded == null ? "" : encoded);
			if (key.length >= 32) return key;
			Arrays.fill(key, (byte) 0);
		}
		catch (IllegalArgumentException ignored) {
			// Never attach a configuration value to an exception.
		}
		throw new IllegalArgumentException("Tracking signing key must be valid Base64 with at least 32 decoded bytes");
	}

	private static String validateOrigin(String value) {
		try {
			URI uri = URI.create(value == null ? "" : value.strip());
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
					|| uri.getRawQuery() != null || uri.getRawFragment() != null
					|| !uri.getRawPath().isEmpty() || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65_535
					|| !(scheme.equals("https") || scheme.equals("http") && isLocalHost(uri.getHost()))) {
				throw new IllegalArgumentException();
			}
			return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null).toString();
		}
		catch (Exception ignored) {
			throw new IllegalArgumentException("Tracking callback URL must be an absolute origin; public hosts require HTTPS");
		}
	}

	private static boolean isLocalHost(String value) {
		String host = value.toLowerCase(Locale.ROOT);
		if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
		if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		if (!host.contains(".") && !host.contains(":") || host.endsWith(".localhost") || host.endsWith(".local")) {
			return true;
		}
		byte[] address = NetUtil.createByteArrayFromIpAddressString(host);
		if (address == null) return false;
		try {
			// Byte-only conversion: never resolve DNS or fetch the configured origin.
			InetAddress parsed = InetAddress.getByAddress(address);
			return parsed.isAnyLocalAddress() || parsed.isLoopbackAddress() || parsed.isSiteLocalAddress()
					|| parsed.isLinkLocalAddress() || address.length == 16 && (address[0] & 0xfe) == 0xfc;
		}
		catch (java.net.UnknownHostException ignored) {
			return false;
		}
	}
}
