package com.camel_hub.advertisement.identity.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenGeneratorTest {

	@Test
	void generatesUnpaddedUrlSafe256BitValuesAndOnlyPersistsTheirHash() {
		RefreshTokenGenerator generator = new RefreshTokenGenerator(new FixedSecureRandom());

		RefreshTokenGenerator.GeneratedRefreshToken token = generator.generate();

		assertThat(token.rawValue()).hasSize(43).doesNotContain("=", "+", "/");
		assertThat(token.hash()).hasSize(32).isEqualTo(generator.hash(token.rawValue()));
		assertThat(token.hash()).isNotEqualTo(token.rawValue().getBytes(StandardCharsets.UTF_8));
	}

	private static final class FixedSecureRandom extends SecureRandom {
		@Override
		public void nextBytes(byte[] bytes) {
			for (int index = 0; index < bytes.length; index++) {
				bytes[index] = (byte) index;
			}
		}
	}
}
