package com.camel_hub.advertisement.identity.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class RefreshTokenGenerator {

	private static final int TOKEN_BYTES = 32;
	private final SecureRandom secureRandom;

	public RefreshTokenGenerator() {
		this(new SecureRandom());
	}

	RefreshTokenGenerator(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	public GeneratedRefreshToken generate() {
		byte[] value = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(value);
		String rawValue = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
		return new GeneratedRefreshToken(rawValue, hash(rawValue));
	}

	public byte[] hash(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			throw new IllegalArgumentException("refresh token must not be blank");
		}
		try {
			return MessageDigest.getInstance("SHA-256").digest(rawValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record GeneratedRefreshToken(String rawValue, byte[] hash) {
	}
}
