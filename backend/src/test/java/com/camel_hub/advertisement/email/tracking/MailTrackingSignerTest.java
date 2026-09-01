package com.camel_hub.advertisement.email.tracking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTrackingSignerTest {
	private static final String KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";
	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final UUID ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
	private static final UUID LINK_ID = UUID.fromString("abcdefab-cdef-cdef-cdef-abcdefabcdef");
	private final MailTrackingSigner signer = new MailTrackingSigner(KEY);

	@Test
	void uniqueNoncesProtectRepeatedIssuanceForTheSameIdAndExpiry() {
		String first = signer.issue(ID, NOW.plusSeconds(60));
		String second = signer.issue(ID, NOW.plusSeconds(60));
		assertThat(first).isNotEqualTo(second);
		assertThat(signer.verify(first, NOW).orElseThrow().recordId()).isEqualTo(ID);
		assertThat(signer.verify(first, NOW).orElseThrow().expiresAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(signer.verify(second, NOW)).isPresent();
	}

	@Test
	void changedPayloadSignatureWrongKeyAndExpiryAreRejected() {
		String token = signer.issue(ID, NOW.plusSeconds(60));
		assertThat(signer.verify(token.replace(ID.toString(), UUID.randomUUID().toString()), NOW)).isEmpty();
		assertThat(signer.verify(token.replace("v1.", "v2."), NOW)).isEmpty();
		String[] parts = token.split("\\.");
		parts[2] = Long.toString(NOW.plusSeconds(120).getEpochSecond());
		assertThat(signer.verify(String.join(".", parts), NOW)).isEmpty();
		assertThat(signer.verify(token, NOW.plusSeconds(60))).isEmpty();
		assertThat(signer.verify(token, NOW.plusSeconds(61))).isEmpty();
		assertThat(new MailTrackingSigner("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=").verify(token, NOW)).isEmpty();
		for (String invalid : new String[] {null, "", "v1.bad", token + ".extra", "a".repeat(257)}) {
			assertThat(signer.verify(invalid, NOW)).isEmpty();
		}
	}

	@Test
	void nonCanonicalBase64SignatureAliasesAreRejectedAsAlterations() {
		String token = signer.issue(ID, NOW.plusSeconds(60));
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
		int last = alphabet.indexOf(token.charAt(token.length() - 1));
		String altered = token.substring(0, token.length() - 1) + alphabet.charAt(last + 1);
		assertThat(signer.verify(altered, NOW)).isEmpty();
	}

	@Test
	void clickTokensBindTheRecordLinkAndExpiryInASeparateSignatureDomain() {
		String token = signer.issueClick(ID, LINK_ID, NOW.plusSeconds(60));
		MailTrackingSigner.VerifiedClickToken verified = signer.verifyClick(token, NOW).orElseThrow();
		assertThat(verified.recordId()).isEqualTo(ID);
		assertThat(verified.linkId()).isEqualTo(LINK_ID);
		assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(signer.verify(token, NOW)).isEmpty();
		assertThat(signer.verifyClick(signer.issue(ID, NOW.plusSeconds(60)), NOW)).isEmpty();
	}

	@Test
	void changedExpiredAndNonCanonicalClickTokensAreRejected() {
		String token = signer.issueClick(ID, LINK_ID, NOW.plusSeconds(60));
		assertThat(signer.verifyClick(token.replace(LINK_ID.toString(), UUID.randomUUID().toString()), NOW)).isEmpty();
		assertThat(signer.verifyClick(token.replace(ID.toString(), UUID.randomUUID().toString()), NOW)).isEmpty();
		assertThat(signer.verifyClick(token, NOW.plusSeconds(60))).isEmpty();
		assertThat(signer.verifyClick(token + ".extra", NOW)).isEmpty();
		assertThat(signer.verifyClick("", NOW)).isEmpty();
	}

	@Test
	void requiresAtLeastThirtyTwoDecodedKeyBytesAndNeverLeaksInvalidKeyMaterial() {
		for (String key : new String[] {null, "", "not-base64-private-value", Base64.getEncoder().encodeToString(new byte[31])}) {
			assertThatThrownBy(() -> new MailTrackingSigner(key)).isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Tracking signing key must be valid Base64 with at least 32 decoded bytes");
		}
		MailTrackingSigner longer = new MailTrackingSigner(Base64.getEncoder().encodeToString(new byte[64]));
		assertThat(longer.verify(longer.issue(ID, NOW.plusSeconds(60)), NOW)).isPresent();
	}
}
