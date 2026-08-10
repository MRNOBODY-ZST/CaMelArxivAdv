package com.camel_hub.advertisement.email.smtp;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class SmtpSecretCrypto {

	private static final byte[] AAD = "camel-arxiv:smtp-password:v1".getBytes(StandardCharsets.UTF_8);
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private final SecretKeySpec encryptionKey;
	private final SecureRandom random = new SecureRandom();

	public SmtpSecretCrypto(String keyBase64) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(keyBase64 == null ? "" : keyBase64);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("SMTP encryption key configuration is invalid");
		}
		if (decoded.length != 32) {
			Arrays.fill(decoded, (byte) 0);
			throw new IllegalStateException("SMTP encryption key configuration is invalid");
		}
		this.encryptionKey = new SecretKeySpec(Arrays.copyOf(decoded, decoded.length), "AES");
		Arrays.fill(decoded, (byte) 0);
	}

	public EncryptedSecret encrypt(char[] plaintext) {
		if (plaintext == null || plaintext.length == 0 || plaintext.length > 1_024) {
			throw new IllegalArgumentException("SMTP password is invalid");
		}
		byte[] bytes = encode(plaintext);
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(AAD);
			return new EncryptedSecret(cipher.doFinal(bytes), nonce);
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("SMTP secret encryption is unavailable", exception);
		}
		finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	public char[] decrypt(EncryptedSecret encrypted) {
		if (encrypted == null || encrypted.nonce().length != NONCE_BYTES || encrypted.ciphertext().length < 16) {
			throw new IllegalArgumentException("Encrypted SMTP secret is invalid");
		}
		byte[] bytes = null;
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
			cipher.updateAAD(AAD);
			bytes = cipher.doFinal(encrypted.ciphertext());
			return decode(bytes);
		}
		catch (AEADBadTagException exception) {
			throw new IllegalArgumentException("Encrypted SMTP secret authentication failed");
		}
		catch (GeneralSecurityException | CharacterCodingException exception) {
			throw new IllegalStateException("SMTP secret decryption is unavailable", exception);
		}
		finally {
			if (bytes != null) Arrays.fill(bytes, (byte) 0);
		}
	}

	private byte[] encode(char[] value) {
		try {
			ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
			byte[] bytes = new byte[buffer.remaining()];
			buffer.get(bytes);
			return bytes;
		}
		catch (CharacterCodingException exception) {
			throw new IllegalArgumentException("SMTP password contains invalid characters");
		}
	}

	private char[] decode(byte[] value) throws CharacterCodingException {
		CharBuffer buffer = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value));
		char[] chars = new char[buffer.remaining()];
		buffer.get(chars);
		return chars;
	}

	public record EncryptedSecret(byte[] ciphertext, byte[] nonce) {
		public EncryptedSecret {
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
