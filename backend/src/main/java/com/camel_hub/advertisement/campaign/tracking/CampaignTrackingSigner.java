package com.camel_hub.advertisement.campaign.tracking;

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

/** Issues opaque, independently domain-separated production campaign capabilities. */
public final class CampaignTrackingSigner {

	private static final String OPEN_NAMESPACE = "campaign-open:v1";
	private static final String CLICK_NAMESPACE = "campaign-click:v1";
	private static final String UNSUBSCRIBE_NAMESPACE = "campaign-unsubscribe:v1";
	private static final byte[] OPEN_CONTEXT = "camel-arxiv:campaign-open:v1:"
			.getBytes(StandardCharsets.US_ASCII);
	private static final byte[] CLICK_CONTEXT = "camel-arxiv:campaign-click:v1:"
			.getBytes(StandardCharsets.US_ASCII);
	private static final byte[] UNSUBSCRIBE_CONTEXT = "camel-arxiv:campaign-unsubscribe:v1:"
			.getBytes(StandardCharsets.US_ASCII);
	private static final byte[] FINGERPRINT_CONTEXT = "camel-arxiv:campaign-callback-fingerprint:v1:"
			.getBytes(StandardCharsets.US_ASCII);
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
	private static final int MAXIMUM_TOKEN_LENGTH = 512;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public CampaignTrackingSigner(String keyBase64) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(keyBase64 == null ? "" : keyBase64);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Campaign tracking key must be valid Base64 with at least 32 bytes");
		}
		if (decoded.length < 32) {
			Arrays.fill(decoded, (byte) 0);
			throw new IllegalArgumentException("Campaign tracking key must be valid Base64 with at least 32 bytes");
		}
		key = new SecretKeySpec(decoded, "HmacSHA256");
		Arrays.fill(decoded, (byte) 0);
	}

	public String issueOpen(UUID recipientId, Instant expiresAt) {
		return issue(OPEN_NAMESPACE, OPEN_CONTEXT, recipientId + "." + expiresAt.getEpochSecond());
	}

	public String issueClick(UUID recipientId, UUID linkId, Instant expiresAt) {
		return issue(CLICK_NAMESPACE, CLICK_CONTEXT,
				recipientId + "." + linkId + "." + expiresAt.getEpochSecond());
	}

	public String issueUnsubscribe(UUID recipientId, Instant expiresAt) {
		return issue(UNSUBSCRIBE_NAMESPACE, UNSUBSCRIBE_CONTEXT,
				recipientId + "." + expiresAt.getEpochSecond());
	}

	public Optional<VerifiedOpen> verifyOpen(String token, Instant now) {
		return verify(token, OPEN_NAMESPACE, OPEN_CONTEXT, 5, now).map(parts ->
				new VerifiedOpen(canonicalUuid(parts[1]), canonicalExpiry(parts[2])));
	}

	public Optional<VerifiedClick> verifyClick(String token, Instant now) {
		return verify(token, CLICK_NAMESPACE, CLICK_CONTEXT, 6, now).map(parts ->
				new VerifiedClick(canonicalUuid(parts[1]), canonicalUuid(parts[2]), canonicalExpiry(parts[3])));
	}

	public Optional<VerifiedUnsubscribe> verifyUnsubscribe(String token, Instant now) {
		return verify(token, UNSUBSCRIBE_NAMESPACE, UNSUBSCRIBE_CONTEXT, 5, now).map(parts ->
				new VerifiedUnsubscribe(canonicalUuid(parts[1]), canonicalExpiry(parts[2])));
	}

	Optional<VerifiedOpen> verifyOpenIncludingExpired(String token) {
		return verify(token, OPEN_NAMESPACE, OPEN_CONTEXT, 5, null).map(parts ->
				new VerifiedOpen(canonicalUuid(parts[1]), canonicalExpiry(parts[2])));
	}

	Optional<VerifiedClick> verifyClickIncludingExpired(String token) {
		return verify(token, CLICK_NAMESPACE, CLICK_CONTEXT, 6, null).map(parts ->
				new VerifiedClick(canonicalUuid(parts[1]), canonicalUuid(parts[2]), canonicalExpiry(parts[3])));
	}

	Optional<VerifiedUnsubscribe> verifyUnsubscribeIncludingExpired(String token) {
		return verify(token, UNSUBSCRIBE_NAMESPACE, UNSUBSCRIBE_CONTEXT, 5, null).map(parts ->
				new VerifiedUnsubscribe(canonicalUuid(parts[1]), canonicalExpiry(parts[2])));
	}

	public byte[] digest(String token) {
		return sha256(token);
	}

	public byte[] fingerprint(String canonicalRequestSummary) {
		return mac(FINGERPRINT_CONTEXT, canonicalRequestSummary);
	}

	public static byte[] sha256(String value) {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	public static byte[] sha256Bytes(byte[] value) {
		return sha256(value);
	}

	private String issue(String namespace, byte[] context, String canonicalFields) {
		byte[] nonce = new byte[24];
		random.nextBytes(nonce);
		String payload = namespace + "." + canonicalFields + "." + ENCODER.encodeToString(nonce);
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
			canonicalUuid(parts[1]);
			if (namespace.equals(CLICK_NAMESPACE)) canonicalUuid(parts[2]);
			Instant expiresAt = canonicalExpiry(parts[expiryIndex]);
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

	private UUID canonicalUuid(String value) {
		if (value == null || value.length() != 36) throw new IllegalArgumentException();
		UUID uuid = UUID.fromString(value);
		if (!uuid.toString().equals(value)) throw new IllegalArgumentException();
		return uuid;
	}

	private Instant canonicalExpiry(String value) {
		if (value == null || !value.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
		long epochSecond = Long.parseLong(value);
		if (!Long.toString(epochSecond).equals(value)) throw new IllegalArgumentException();
		return Instant.ofEpochSecond(epochSecond);
	}

	private byte[] mac(byte[] context, String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			mac.update(context);
			return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Campaign tracking signing is unavailable");
		}
	}

	private static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Campaign tracking digest is unavailable");
		}
	}

	public record VerifiedOpen(UUID recipientId, Instant expiresAt) { }
	public record VerifiedClick(UUID recipientId, UUID linkId, Instant expiresAt) { }
	public record VerifiedUnsubscribe(UUID recipientId, Instant expiresAt) { }
}
