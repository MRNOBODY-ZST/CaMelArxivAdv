package com.camel_hub.advertisement.email.smtp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpSecretCryptoTest {

	private final SmtpSecretCrypto crypto = new SmtpSecretCrypto(
			Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));

	@Test
	void encryptsWithIndependentNoncesAndAuthenticatedContext() {
		var first = crypto.encrypt("smtp-password-value".toCharArray());
		var second = crypto.encrypt("smtp-password-value".toCharArray());

		assertThat(new String(first.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("smtp-password-value");
		assertThat(first.nonce()).isNotEqualTo(second.nonce());
		assertThat(crypto.decrypt(first)).containsExactly("smtp-password-value".toCharArray());

		byte[] tampered = first.ciphertext();
		tampered[0] ^= 1;
		assertThatThrownBy(() -> crypto.decrypt(new SmtpSecretCrypto.EncryptedSecret(tampered, first.nonce())))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("authentication");
	}

	@Test
	void rejectsMissingOrWeakKeysWithoutEchoingSecretMaterial() {
		assertThatThrownBy(() -> new SmtpSecretCrypto("c2hvcnQ="))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("short");
	}
}
