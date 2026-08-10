package com.camel_hub.advertisement.identity.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuthPropertiesTest {

	@Test
	void decodesIndependentKeysWithAtLeast256Bits() {
		AuthProperties properties = properties(keyOfLength(32), keyOfLength(48));

		assertThat(properties.decodedSigningKey()).hasSize(32);
		assertThat(properties.decodedFingerprintHmacKey()).hasSize(48);
	}

	@Test
	void rejectsShortOrMalformedKeyMaterial() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties(keyOfLength(31), keyOfLength(32)).decodedSigningKey())
				.withMessageContaining("signing key");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties("not-base64", keyOfLength(32)).decodedSigningKey())
				.withMessageContaining("Base64");
	}

	@Test
	void rejectsNonPositiveDurationsAndWeakLoginLimits() {
		assertThatIllegalArgumentException().isThrownBy(() -> new AuthProperties(
				Duration.ZERO,
				Duration.ofDays(14),
				0,
				Duration.ofMinutes(15),
				"camel-arxiv",
				keyOfLength(32),
				keyOfLength(32),
				new AuthProperties.RefreshCookie(true, "Strict", "/api/v1/auth"),
				new AuthProperties.BootstrapAdmin("", "", "", "")));
	}

	private AuthProperties properties(String signingKey, String fingerprintKey) {
		return new AuthProperties(
				Duration.ofMinutes(10),
				Duration.ofDays(14),
				5,
				Duration.ofMinutes(15),
				"camel-arxiv",
				signingKey,
				fingerprintKey,
				new AuthProperties.RefreshCookie(true, "Strict", "/api/v1/auth"),
				new AuthProperties.BootstrapAdmin("admin", "admin@example.invalid", "Admin", ""));
	}

	private String keyOfLength(int length) {
		return Base64.getEncoder().encodeToString(new byte[length]);
	}
}
