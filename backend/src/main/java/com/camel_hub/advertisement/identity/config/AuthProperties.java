package com.camel_hub.advertisement.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

@ConfigurationProperties("app.auth")
public record AuthProperties(
		Duration accessTokenTtl,
		Duration refreshTokenTtl,
		int maxLoginFailures,
		Duration loginFailureWindow,
		String issuer,
		String signingKeyBase64,
		String fingerprintHmacKeyBase64,
		RefreshCookie cookie,
		BootstrapAdmin bootstrapAdmin
) {

	private static final int MINIMUM_KEY_BYTES = 32;

	public AuthProperties {
		requirePositive(accessTokenTtl, "access token TTL");
		requirePositive(refreshTokenTtl, "refresh token TTL");
		requirePositive(loginFailureWindow, "login failure window");
		if (maxLoginFailures < 1) {
			throw new IllegalArgumentException("max login failures must be positive");
		}
		if (issuer == null || issuer.isBlank()) {
			throw new IllegalArgumentException("authentication issuer must not be blank");
		}
		Objects.requireNonNull(cookie, "refresh cookie configuration is required");
		Objects.requireNonNull(bootstrapAdmin, "bootstrap administrator configuration is required");
	}

	public byte[] decodedSigningKey() {
		return decodeKey(signingKeyBase64, "signing key");
	}

	public byte[] decodedFingerprintHmacKey() {
		return decodeKey(fingerprintHmacKeyBase64, "fingerprint HMAC key");
	}

	private static byte[] decodeKey(String encoded, String name) {
		try {
			byte[] decoded = Base64.getDecoder().decode(Objects.requireNonNull(encoded, name));
			if (decoded.length < MINIMUM_KEY_BYTES) {
				throw new IllegalArgumentException(name + " must contain at least 256 bits");
			}
			return decoded;
		}
		catch (IllegalArgumentException exception) {
			if (exception.getMessage() != null && exception.getMessage().contains("256 bits")) {
				throw exception;
			}
			throw new IllegalArgumentException(name + " must be valid Base64", exception);
		}
	}

	private static void requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	public record RefreshCookie(boolean secure, String sameSite, String path) {
		public RefreshCookie {
			if (sameSite == null || !switch (sameSite.toLowerCase(Locale.ROOT)) {
				case "strict", "lax", "none" -> true;
				default -> false;
			}) {
				throw new IllegalArgumentException("refresh cookie SameSite must be Strict, Lax, or None");
			}
			if (path == null || !path.startsWith("/")) {
				throw new IllegalArgumentException("refresh cookie path must be absolute");
			}
		}
	}

	public record BootstrapAdmin(String username, String email, String displayName, String password) {
	}
}
