package com.camel_hub.advertisement.email.tracking;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public final class MailTrackingSigner {
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final byte[] OPEN_CONTEXT = "camel-arxiv:mail-open:v1:".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] CLICK_CONTEXT = "camel-arxiv:mail-click:v1:".getBytes(StandardCharsets.US_ASCII);
	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public MailTrackingSigner(String keyBase64) {
		byte[] decoded = MailTrackingProperties.decodeKey(keyBase64);
		key = new SecretKeySpec(decoded, "HmacSHA256");
		Arrays.fill(decoded, (byte) 0);
	}

	public String issue(UUID recordId, Instant expiresAt) {
		byte[] nonce = new byte[24];
		random.nextBytes(nonce);
		String payload = "v1." + recordId + "." + expiresAt.getEpochSecond() + "." + ENCODER.encodeToString(nonce);
		return payload + "." + ENCODER.encodeToString(mac(OPEN_CONTEXT, payload));
	}

	public Optional<VerifiedToken> verify(String token, Instant now) {
		if (token == null || token.length() > 256) return Optional.empty();
		String[] parts = token.split("\\.", -1);
		if (parts.length != 5 || !parts[0].equals("v1") || parts[1].length() != 36
				|| !parts[2].matches("[0-9]{1,19}") || !parts[3].matches("[A-Za-z0-9_-]{32}")
				|| !parts[4].matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
		try {
			UUID id = UUID.fromString(parts[1]);
			if (!id.toString().equals(parts[1])) return Optional.empty();
			String payload = token.substring(0, token.lastIndexOf('.'));
			byte[] signature = Base64.getUrlDecoder().decode(parts[4]);
			if (!MessageDigest.isEqual(mac(OPEN_CONTEXT, payload), signature)
					|| !ENCODER.encodeToString(signature).equals(parts[4])) {
				return Optional.empty();
			}
			Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
			if (!expiresAt.isAfter(now)) return Optional.empty();
			return Optional.of(new VerifiedToken(id, expiresAt, digest(token)));
		}
		catch (IllegalArgumentException | java.time.DateTimeException ignored) {
			return Optional.empty();
		}
	}

	public String issueClick(UUID recordId, UUID linkId, Instant expiresAt) {
		byte[] nonce = new byte[24];
		random.nextBytes(nonce);
		String payload = "v1c." + recordId + "." + linkId + "." + expiresAt.getEpochSecond() + "."
				+ ENCODER.encodeToString(nonce);
		return payload + "." + ENCODER.encodeToString(mac(CLICK_CONTEXT, payload));
	}

	public Optional<VerifiedClickToken> verifyClick(String token, Instant now) {
		if (token == null || token.length() > 320) return Optional.empty();
		String[] parts = token.split("\\.", -1);
		if (parts.length != 6 || !parts[0].equals("v1c") || parts[1].length() != 36 || parts[2].length() != 36
				|| !parts[3].matches("[0-9]{1,19}") || !parts[4].matches("[A-Za-z0-9_-]{32}")
				|| !parts[5].matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
		try {
			UUID recordId = UUID.fromString(parts[1]);
			UUID linkId = UUID.fromString(parts[2]);
			if (!recordId.toString().equals(parts[1]) || !linkId.toString().equals(parts[2])) return Optional.empty();
			String payload = token.substring(0, token.lastIndexOf('.'));
			byte[] signature = Base64.getUrlDecoder().decode(parts[5]);
			if (!MessageDigest.isEqual(mac(CLICK_CONTEXT, payload), signature)
					|| !ENCODER.encodeToString(signature).equals(parts[5])) return Optional.empty();
			Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[3]));
			if (!expiresAt.isAfter(now)) return Optional.empty();
			return Optional.of(new VerifiedClickToken(recordId, linkId, expiresAt, digest(token)));
		}
		catch (IllegalArgumentException | java.time.DateTimeException ignored) {
			return Optional.empty();
		}
	}

	static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Mail tracking digest is unavailable");
		}
	}

	private byte[] mac(byte[] context, String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			mac.update(context);
			return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Mail tracking signing is unavailable");
		}
	}

	public record VerifiedToken(UUID recordId, Instant expiresAt, byte[] digest) { }

	public record VerifiedClickToken(UUID recordId, UUID linkId, Instant expiresAt, byte[] digest) { }
}
