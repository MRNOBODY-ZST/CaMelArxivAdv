package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.email.tracking.MailTrackingSigner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignTrackingSignerTest {

	private static final String KEY = Base64.getEncoder().encodeToString(
			"campaign-tracking-test-key-32bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
	private static final UUID RECIPIENT = UUID.fromString("4abcdef0-abcd-abcd-abcd-abcdef000001");
	private static final UUID LINK = UUID.fromString("42000000-0000-0000-0000-000000000001");
	private static final Instant NOW = Instant.parse("2031-02-03T04:05:06Z");

	@Test
	void eachNamespaceAcceptsOnlyItsOwnCanonicalPayload() {
		CampaignTrackingSigner signer = new CampaignTrackingSigner(KEY);
		Instant expires = NOW.plusSeconds(3600);
		String open = signer.issueOpen(RECIPIENT, expires);
		String click = signer.issueClick(RECIPIENT, LINK, expires);
		String unsubscribe = signer.issueUnsubscribe(RECIPIENT, expires);

		assertThat(signer.verifyOpen(open, NOW)).contains(
				new CampaignTrackingSigner.VerifiedOpen(RECIPIENT, expires));
		assertThat(signer.verifyClick(click, NOW)).contains(
				new CampaignTrackingSigner.VerifiedClick(RECIPIENT, LINK, expires));
		assertThat(signer.verifyUnsubscribe(unsubscribe, NOW)).contains(
				new CampaignTrackingSigner.VerifiedUnsubscribe(RECIPIENT, expires));
		assertThat(signer.verifyOpen(click, NOW)).isEmpty();
		assertThat(signer.verifyOpen(unsubscribe, NOW)).isEmpty();
		assertThat(signer.verifyClick(open, NOW)).isEmpty();
		assertThat(signer.verifyClick(unsubscribe, NOW)).isEmpty();
		assertThat(signer.verifyUnsubscribe(open, NOW)).isEmpty();
		assertThat(signer.verifyUnsubscribe(click, NOW)).isEmpty();
	}

	@Test
	void alteredExpiredOversizedAndNonCanonicalTokensAreRejected() {
		CampaignTrackingSigner signer = new CampaignTrackingSigner(KEY);
		CampaignTrackingSigner wrongKey = new CampaignTrackingSigner(Base64.getEncoder().encodeToString(
				"a-completely-different-key-32bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		String[] tokens = {
				signer.issueOpen(RECIPIENT, NOW.plusSeconds(60)),
				signer.issueClick(RECIPIENT, LINK, NOW.plusSeconds(60)),
				signer.issueUnsubscribe(RECIPIENT, NOW.plusSeconds(60))
		};

		for (String token : tokens) {
			String altered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
			String upperCaseUuid = token.replace(RECIPIENT.toString(), RECIPIENT.toString().toUpperCase());
			assertThat(verifies(signer, altered, NOW)).as("altered " + namespace(token)).isFalse();
			assertThat(verifies(signer, token, NOW.plusSeconds(60))).as("expired " + namespace(token)).isFalse();
			assertThat(verifies(signer, upperCaseUuid, NOW)).as("UUID " + namespace(token)).isFalse();
			assertThat(verifies(signer, token + "x".repeat(513), NOW)).as("oversized " + namespace(token)).isFalse();
			assertThat(verifies(wrongKey, token, NOW)).as("wrong key " + namespace(token)).isFalse();
			assertThat(verifies(signer, nonCanonicalSignature(token), NOW))
					.as("noncanonical base64 " + namespace(token)).isFalse();
		}
		assertThat(signer.verifyOpen(null, NOW)).isEmpty();
	}

	@Test
	void tokensAreOpaqueRandomAndContainNoDestinationData() {
		CampaignTrackingSigner signer = new CampaignTrackingSigner(KEY);
		String first = signer.issueClick(RECIPIENT, LINK, NOW.plusSeconds(60));
		String second = signer.issueClick(RECIPIENT, LINK, NOW.plusSeconds(60));

		assertThat(first).isNotEqualTo(second)
				.doesNotContain("example.org", "https://", "@")
				.startsWith("campaign-click:v1.");
		assertThat(signer.digest(first)).hasSize(32).isNotEqualTo(signer.digest(second));
	}

	@Test
	void everySignedFieldKeyAndTokenShapeAreAuthenticatedWithoutLeakingKeys() {
		CampaignTrackingSigner signer = new CampaignTrackingSigner(KEY);
		CampaignTrackingSigner wrongKey = new CampaignTrackingSigner(Base64.getEncoder().encodeToString(
				"a-completely-different-key-32bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		Instant expires = NOW.plusSeconds(3600);
		String click = signer.issueClick(RECIPIENT, LINK, expires);
		String[] parts = click.split("\\.", -1);

		assertThat(wrongKey.verifyClick(click, NOW)).isEmpty();
		assertThat(signer.verifyClick(replace(parts, 1, UUID.randomUUID().toString()), NOW)).isEmpty();
		assertThat(signer.verifyClick(replace(parts, 2, UUID.randomUUID().toString()), NOW)).isEmpty();
		assertThat(signer.verifyClick(replace(parts, 3, Long.toString(expires.plusSeconds(1).getEpochSecond())), NOW))
				.isEmpty();
		assertThat(signer.verifyClick(replace(parts, 4, "A".repeat(32)), NOW)).isEmpty();
		assertThat(signer.verifyClick(replace(parts, 5, "A".repeat(43)), NOW)).isEmpty();
		assertThat(signer.verifyClick(click.substring(click.indexOf('.') + 1), NOW)).isEmpty();
		assertThat(signer.verifyClick(click + ".extra", NOW)).isEmpty();
		assertThat(signer.verifyClick(click.replace("campaign-click:v1", "campaign-click:v01"), NOW)).isEmpty();
		assertThat(signer.verifyClick(click.replace(parts[3], "0" + parts[3]), NOW)).isEmpty();
		assertThat(signer.verifyClick(click.substring(0, click.length() - 1) + "/", NOW)).isEmpty();
	}

	@Test
	void testMailLookingTokensCannotCrossIntoAnyCampaignNamespace() {
		CampaignTrackingSigner signer = new CampaignTrackingSigner(KEY);
		MailTrackingSigner testMail = new MailTrackingSigner(KEY);
		String testOpen = testMail.issue(RECIPIENT, NOW.plusSeconds(60));
		String testClick = testMail.issueClick(RECIPIENT, LINK, NOW.plusSeconds(60));
		for (String token : new String[] {
				testOpen, testClick, "v1.open.opaque", "v1.click.opaque", "mail-open:v1.opaque",
				"test-mail:v1." + "A".repeat(96)}) {
			assertThat(signer.verifyOpen(token, NOW)).isEmpty();
			assertThat(signer.verifyClick(token, NOW)).isEmpty();
			assertThat(signer.verifyUnsubscribe(token, NOW)).isEmpty();
		}
		assertThat(testMail.verify(signer.issueOpen(RECIPIENT, NOW.plusSeconds(60)), NOW)).isEmpty();
		assertThat(testMail.verifyClick(signer.issueClick(RECIPIENT, LINK, NOW.plusSeconds(60)), NOW)).isEmpty();
		assertThat(testMail.verify(signer.issueUnsubscribe(RECIPIENT, NOW.plusSeconds(60)), NOW)).isEmpty();
	}

	@Test
	void shortInvalidOrMissingKeysAreRejectedWithRedactedMessages() {
		for (String value : new String[] {null, "", "not-base64", Base64.getEncoder().encodeToString(new byte[31])}) {
			assertThatThrownBy(() -> new CampaignTrackingSigner(value))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Campaign tracking key must be valid Base64 with at least 32 bytes");
		}
		assertThatThrownBy(() -> new CampaignTrackingSigner("not-base64"))
				.hasMessageNotContaining("not-base64");
	}

	private String replace(String[] source, int index, String value) {
		String[] copy = source.clone();
		copy[index] = value;
		return String.join(".", copy);
	}

	private boolean verifies(CampaignTrackingSigner signer, String token, Instant now) {
		return switch (namespace(token)) {
			case "campaign-open:v1" -> signer.verifyOpen(token, now).isPresent();
			case "campaign-click:v1" -> signer.verifyClick(token, now).isPresent();
			case "campaign-unsubscribe:v1" -> signer.verifyUnsubscribe(token, now).isPresent();
			default -> false;
		};
	}

	private String namespace(String token) {
		int separator = token.indexOf('.');
		return separator < 0 ? token : token.substring(0, separator);
	}

	private String nonCanonicalSignature(String token) {
		String[] parts = token.split("\\.", -1);
		String signature = parts[parts.length - 1];
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
		int canonical = alphabet.indexOf(signature.charAt(signature.length() - 1));
		String replacement = signature.substring(0, signature.length() - 1)
				+ alphabet.charAt((canonical & 0b111100) + 1);
		assertThat(Arrays.equals(Base64.getUrlDecoder().decode(signature),
				Base64.getUrlDecoder().decode(replacement))).isTrue();
		parts[parts.length - 1] = replacement;
		return String.join(".", parts);
	}
}
