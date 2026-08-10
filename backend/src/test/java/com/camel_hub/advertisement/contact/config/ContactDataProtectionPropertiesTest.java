package com.camel_hub.advertisement.contact.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ContactDataProtectionPropertiesTest {

	@Test
	void decodesIndependent256BitKeys() {
		ContactDataProtectionProperties properties = new ContactDataProtectionProperties(
				key((byte) 1, 32), key((byte) 2, 32));

		assertThat(properties.decodedEncryptionKey()).hasSize(32);
		assertThat(properties.decodedEmailHmacKey()).hasSize(32);
	}

	@Test
	void rejectsMalformedShortOrReusedKeys() {
		assertThatIllegalArgumentException().isThrownBy(() ->
				new ContactDataProtectionProperties("not-base64", key((byte) 2, 32))
						.decodedEncryptionKey());
		assertThatIllegalArgumentException().isThrownBy(() ->
				new ContactDataProtectionProperties(key((byte) 1, 31), key((byte) 2, 32))
						.decodedEncryptionKey());
		String reused = key((byte) 7, 32);
		assertThatIllegalArgumentException().isThrownBy(() ->
				new ContactDataProtectionProperties(reused, reused).validateIndependentKeys())
				.withMessageContaining("independent");
	}

	private String key(byte value, int length) {
		byte[] bytes = new byte[length];
		java.util.Arrays.fill(bytes, value);
		return Base64.getEncoder().encodeToString(bytes);
	}
}
