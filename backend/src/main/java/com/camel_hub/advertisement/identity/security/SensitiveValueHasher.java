package com.camel_hub.advertisement.identity.security;

import com.camel_hub.advertisement.identity.config.AuthProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

public final class SensitiveValueHasher {

	private static final String ALGORITHM = "HmacSHA256";
	private final SecretKeySpec key;

	public SensitiveValueHasher(AuthProperties properties) {
		this.key = new SecretKeySpec(properties.decodedFingerprintHmacKey(), ALGORITHM);
	}

	public byte[] hash(String value) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
		}
	}
}
