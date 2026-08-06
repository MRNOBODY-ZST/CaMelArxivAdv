package com.camel_hub.advertisement.contact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

@ConfigurationProperties("app.contact.data-protection")
public record ContactDataProtectionProperties(
		String encryptionKeyBase64,
		String emailHmacKeyBase64
) {

	private static final int KEY_BYTES = 32;

	public byte[] decodedEncryptionKey() {
		return decode(encryptionKeyBase64, "contact encryption key");
	}

	public byte[] decodedEmailHmacKey() {
		return decode(emailHmacKeyBase64, "contact email HMAC key");
	}

	public void validateIndependentKeys() {
		if (Arrays.equals(decodedEncryptionKey(), decodedEmailHmacKey())) {
			throw new IllegalArgumentException("contact encryption and HMAC keys must be independent");
		}
	}

	private byte[] decode(String encoded, String name) {
		try {
			byte[] decoded = Base64.getDecoder().decode(Objects.requireNonNull(encoded, name));
			if (decoded.length != KEY_BYTES) {
				throw new IllegalArgumentException(name + " must contain exactly 256 bits");
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
}
