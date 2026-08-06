package com.camel_hub.advertisement.contact.security;

import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ContactCryptoTest {

	private final ContactCrypto crypto = new ContactCrypto(new ContactDataProtectionProperties(
			Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
			Base64.getEncoder().encodeToString("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8))));

	@Test
	void encryptsWithFreshNoncesAndAuthenticatesRoundTrip() {
		ContactCrypto.EncryptedValue first = crypto.encrypt("alice@university.edu");
		ContactCrypto.EncryptedValue second = crypto.encrypt("alice@university.edu");

		assertThat(first.ciphertext()).isNotEqualTo("alice@university.edu".getBytes(StandardCharsets.UTF_8));
		assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
		assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
		assertThat(crypto.decrypt(first)).isEqualTo("alice@university.edu");
	}

	@Test
	void hmacIsDeterministicAndSeparateFromCiphertext() {
		byte[] first = crypto.hmac("alice@university.edu");
		byte[] second = crypto.hmac("alice@university.edu");

		assertThat(first).hasSize(32).isEqualTo(second);
		assertThat(first).isNotEqualTo(crypto.hmac("bob@university.edu"));
		assertThat(first).isNotEqualTo(crypto.encrypt("alice@university.edu").ciphertext());
	}
}
