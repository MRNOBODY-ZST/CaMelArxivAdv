package com.camel_hub.advertisement.contact.security;

import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class ContactCrypto {

	private static final String CIPHER = "AES/GCM/NoPadding";
	private static final String HMAC = "HmacSHA256";
	private static final byte[] AAD = "camel-arxiv:contact:v1".getBytes(StandardCharsets.UTF_8);
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec encryptionKey;
	private final SecretKeySpec hmacKey;
	private final SecureRandom random = new SecureRandom();

	public ContactCrypto(ContactDataProtectionProperties properties) {
		properties.validateIndependentKeys();
		this.encryptionKey = new SecretKeySpec(properties.decodedEncryptionKey(), "AES");
		this.hmacKey = new SecretKeySpec(properties.decodedEmailHmacKey(), HMAC);
	}

	public EncryptedValue encrypt(String plaintext) {
		if (plaintext == null || plaintext.isBlank() || plaintext.length() > 320) {
			throw new IllegalArgumentException("contact value is invalid");
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(CIPHER);
			cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(AAD);
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return new EncryptedValue(ciphertext, nonce);
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("contact encryption is unavailable", exception);
		}
	}

	public String decrypt(EncryptedValue encrypted) {
		if (encrypted == null || encrypted.nonce().length != NONCE_BYTES
				|| encrypted.ciphertext().length < 16) {
			throw new IllegalArgumentException("encrypted contact value is invalid");
		}
		try {
			Cipher cipher = Cipher.getInstance(CIPHER);
			cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
					new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
			cipher.updateAAD(AAD);
			return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
		}
		catch (AEADBadTagException exception) {
			throw new IllegalArgumentException("encrypted contact value authentication failed", exception);
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("contact decryption is unavailable", exception);
		}
	}

	public byte[] hmac(String normalizedEmail) {
		if (normalizedEmail == null || normalizedEmail.isBlank() || normalizedEmail.length() > 320) {
			throw new IllegalArgumentException("normalized contact email is invalid");
		}
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(hmacKey);
			return mac.doFinal(normalizedEmail.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("contact HMAC is unavailable", exception);
		}
	}

	public record EncryptedValue(byte[] ciphertext, byte[] nonce) {
		public EncryptedValue {
			ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
			nonce = Arrays.copyOf(nonce, nonce.length);
		}

		@Override
		public byte[] ciphertext() {
			return Arrays.copyOf(ciphertext, ciphertext.length);
		}

		@Override
		public byte[] nonce() {
			return Arrays.copyOf(nonce, nonce.length);
		}
	}
}
