package com.camel_hub.advertisement.campaign.safety;

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

/** Issues capability and destination digests that cannot cross safety namespaces. */
public final class CampaignSafetySigner {
	private static final String OPEN_NAMESPACE = "campaign-safety-open:v1";
	private static final String CLICK_NAMESPACE = "campaign-safety-click:v1";
	private static final String UNSUBSCRIBE_NAMESPACE = "campaign-safety-unsubscribe:v1";
	private static final byte[] OPEN_CONTEXT = context("campaign-safety-open:v1:");
	private static final byte[] CLICK_CONTEXT = context("campaign-safety-click:v1:");
	private static final byte[] UNSUBSCRIBE_CONTEXT = context("campaign-safety-unsubscribe:v1:");
	private static final byte[] DESTINATION_CONTEXT = context("campaign-safety-destination:v1:");
	private static final byte[] FINGERPRINT_CONTEXT = context("campaign-safety-callback-fingerprint:v1:");
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
	private static final int MAXIMUM_TOKEN_LENGTH = 512;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public CampaignSafetySigner(String keyBase64) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(keyBase64 == null ? "" : keyBase64);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Campaign safety signing key must be valid Base64 with at least 32 bytes");
		}
		if (decoded.length < 32) {
			Arrays.fill(decoded, (byte) 0);
			throw new IllegalArgumentException("Campaign safety signing key must be valid Base64 with at least 32 bytes");
		}
		key = new SecretKeySpec(decoded, "HmacSHA256");
		Arrays.fill(decoded, (byte) 0);
	}

	public String issueOpen(UUID messageId, Instant expiresAt) {
		return issue(OPEN_NAMESPACE, OPEN_CONTEXT, messageId + "." + expiresAt.getEpochSecond());
	}

	public String issueClick(UUID messageId, UUID linkId, Instant expiresAt) {
		return issue(CLICK_NAMESPACE, CLICK_CONTEXT,
				messageId + "." + linkId + "." + expiresAt.getEpochSecond());
	}

	public String issueUnsubscribe(UUID messageId, Instant expiresAt) {
		return issue(UNSUBSCRIBE_NAMESPACE, UNSUBSCRIBE_CONTEXT,
				messageId + "." + expiresAt.getEpochSecond());
	}

	public Optional<VerifiedOpen> verifyOpen(String token, Instant now) {
		return verify(token, OPEN_NAMESPACE, OPEN_CONTEXT, 5, now)
				.map(parts -> new VerifiedOpen(uuid(parts[1]), expiry(parts[2])));
	}

	public Optional<VerifiedClick> verifyClick(String token, Instant now) {
		return verify(token, CLICK_NAMESPACE, CLICK_CONTEXT, 6, now)
				.map(parts -> new VerifiedClick(uuid(parts[1]), uuid(parts[2]), expiry(parts[3])));
	}

	public Optional<VerifiedUnsubscribe> verifyUnsubscribe(String token, Instant now) {
		return verify(token, UNSUBSCRIBE_NAMESPACE, UNSUBSCRIBE_CONTEXT, 5, now)
				.map(parts -> new VerifiedUnsubscribe(uuid(parts[1]), expiry(parts[2])));
	}

	Optional<VerifiedOpen> verifyOpenIncludingExpired(String token) {
		return verify(token, OPEN_NAMESPACE, OPEN_CONTEXT, 5, null)
				.map(parts -> new VerifiedOpen(uuid(parts[1]), expiry(parts[2])));
	}

	Optional<VerifiedClick> verifyClickIncludingExpired(String token) {
		return verify(token, CLICK_NAMESPACE, CLICK_CONTEXT, 6, null)
				.map(parts -> new VerifiedClick(uuid(parts[1]), uuid(parts[2]), expiry(parts[3])));
	}

	Optional<VerifiedUnsubscribe> verifyUnsubscribeIncludingExpired(String token) {
		return verify(token, UNSUBSCRIBE_NAMESPACE, UNSUBSCRIBE_CONTEXT, 5, null)
				.map(parts -> new VerifiedUnsubscribe(uuid(parts[1]), expiry(parts[2])));
	}

	public byte[] destinationHmac(String canonicalAddress) {
		if (canonicalAddress == null || canonicalAddress.isBlank()) {
			throw new IllegalArgumentException("Campaign safety destination is required");
		}
		return mac(DESTINATION_CONTEXT, canonicalAddress);
	}

	public byte[] digest(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Campaign safety digest is unavailable");
		}
	}

	public byte[] fingerprint(String summary) {
		return mac(FINGERPRINT_CONTEXT, summary);
	}

	private String issue(String namespace, byte[] context, String fields) {
		byte[] nonce = new byte[24];
		random.nextBytes(nonce);
		String payload = namespace + "." + fields + "." + ENCODER.encodeToString(nonce);
		return payload + "." + ENCODER.encodeToString(mac(context, payload));
	}

	private Optional<String[]> verify(
			String token, String namespace, byte[] context, int partCount, Instant now
	) {
		if (token == null || token.length() > MAXIMUM_TOKEN_LENGTH) return Optional.empty();
		String[] parts = token.split("\\.", -1);
		if (parts.length != partCount || !namespace.equals(parts[0])) return Optional.empty();
		int nonceIndex = partCount - 2;
		int signatureIndex = partCount - 1;
		if (!parts[nonceIndex].matches("[A-Za-z0-9_-]{32}")
				|| !parts[signatureIndex].matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
		try {
			int expiryIndex = namespace.equals(CLICK_NAMESPACE) ? 3 : 2;
			uuid(parts[1]);
			if (namespace.equals(CLICK_NAMESPACE)) uuid(parts[2]);
			Instant expiresAt = expiry(parts[expiryIndex]);
			if (now != null && !expiresAt.isAfter(now)) return Optional.empty();
			String payload = token.substring(0, token.lastIndexOf('.'));
			byte[] signature = DECODER.decode(parts[signatureIndex]);
			if (!ENCODER.encodeToString(signature).equals(parts[signatureIndex])
					|| !MessageDigest.isEqual(mac(context, payload), signature)) return Optional.empty();
			return Optional.of(parts);
		}
		catch (IllegalArgumentException | java.time.DateTimeException exception) {
			return Optional.empty();
		}
	}

	private UUID uuid(String value) {
		if (value == null || value.length() != 36) throw new IllegalArgumentException();
		UUID parsed = UUID.fromString(value);
		if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
		return parsed;
	}

	private Instant expiry(String value) {
		if (value == null || !value.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
		long seconds = Long.parseLong(value);
		if (!Long.toString(seconds).equals(value)) throw new IllegalArgumentException();
		return Instant.ofEpochSecond(seconds);
	}

	private byte[] mac(byte[] context, String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			mac.update(context);
			return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Campaign safety signing is unavailable");
		}
	}

	private static byte[] context(String value) {
		return ("camel-arxiv:" + value).getBytes(StandardCharsets.US_ASCII);
	}

	public record VerifiedOpen(UUID messageId, Instant expiresAt) { }
	public record VerifiedClick(UUID messageId, UUID linkId, Instant expiresAt) { }
	public record VerifiedUnsubscribe(UUID messageId, Instant expiresAt) { }
}
