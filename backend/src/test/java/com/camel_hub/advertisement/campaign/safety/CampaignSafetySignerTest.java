package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.tracking.CampaignTrackingSigner;
import com.camel_hub.advertisement.email.tracking.MailTrackingSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignSafetySignerTest {

	private static final String KEY = Base64.getEncoder().encodeToString(
			"campaign-safety-signer-test-key-32!!".getBytes(StandardCharsets.US_ASCII));
	private final CampaignSafetySigner signer = new CampaignSafetySigner(KEY);

	@Test
	void issuesThreeCryptographicallySeparatedOpaqueCapabilities() {
		UUID message = UUID.randomUUID();
		UUID link = UUID.randomUUID();
		Instant expiry = Instant.parse("2035-01-02T03:04:05Z");
		String open = signer.issueOpen(message, expiry);
		String click = signer.issueClick(message, link, expiry);
		String unsubscribe = signer.issueUnsubscribe(message, expiry);

		assertThat(signer.verifyOpen(open, expiry.minusSeconds(1))).get()
				.extracting(CampaignSafetySigner.VerifiedOpen::messageId,
						CampaignSafetySigner.VerifiedOpen::expiresAt)
				.containsExactly(message, expiry);
		assertThat(signer.verifyClick(click, expiry.minusSeconds(1))).get()
				.extracting(CampaignSafetySigner.VerifiedClick::messageId,
						CampaignSafetySigner.VerifiedClick::linkId,
						CampaignSafetySigner.VerifiedClick::expiresAt)
				.containsExactly(message, link, expiry);
		assertThat(signer.verifyUnsubscribe(unsubscribe, expiry.minusSeconds(1))).get()
				.extracting(CampaignSafetySigner.VerifiedUnsubscribe::messageId,
						CampaignSafetySigner.VerifiedUnsubscribe::expiresAt)
				.containsExactly(message, expiry);
		assertThat(open).startsWith("campaign-safety-open:v1.").doesNotContain("@", "example");
		assertThat(click).startsWith("campaign-safety-click:v1.").doesNotContain("@", "example");
		assertThat(unsubscribe).startsWith("campaign-safety-unsubscribe:v1.").doesNotContain("@", "example");
		assertThat(signer.verifyOpen(unsubscribe, expiry.minusSeconds(1))).isEmpty();
		assertThat(signer.verifyUnsubscribe(open, expiry.minusSeconds(1))).isEmpty();
		assertThat(signer.verifyClick(open, expiry.minusSeconds(1))).isEmpty();
	}

	@Test
	void rejectsExpiryTamperingAndCrossKeyVerification() {
		UUID message = UUID.randomUUID();
		Instant expiry = Instant.parse("2035-01-02T03:04:05Z");
		String token = signer.issueOpen(message, expiry);
		CampaignSafetySigner other = new CampaignSafetySigner(Base64.getEncoder().encodeToString(
				"different-campaign-safety-key-32!!!".getBytes(StandardCharsets.US_ASCII)));

		assertThat(signer.verifyOpen(token, expiry)).isEmpty();
		assertThat(signer.verifyOpen(token.replace(message.toString(), UUID.randomUUID().toString()),
				expiry.minusSeconds(1))).isEmpty();
		assertThat(other.verifyOpen(token, expiry.minusSeconds(1))).isEmpty();
	}

	@Test
	void includingExpiredVerificationAuthenticatesAllThreeDomainsWithoutRelaxingNormalExpiry() {
		UUID message = UUID.randomUUID();
		UUID link = UUID.randomUUID();
		Instant expiry = Instant.parse("2035-01-02T03:04:05Z");
		Instant afterExpiry = expiry.plusSeconds(1);
		String open = signer.issueOpen(message, expiry);
		String click = signer.issueClick(message, link, expiry);
		String unsubscribe = signer.issueUnsubscribe(message, expiry);

		assertThat(signer.verifyOpen(open, afterExpiry)).isEmpty();
		assertThat(signer.verifyClick(click, afterExpiry)).isEmpty();
		assertThat(signer.verifyUnsubscribe(unsubscribe, afterExpiry)).isEmpty();
		assertThat(signer.verifyOpenIncludingExpired(open)).get()
				.extracting(CampaignSafetySigner.VerifiedOpen::messageId,
						CampaignSafetySigner.VerifiedOpen::expiresAt)
				.containsExactly(message, expiry);
		assertThat(signer.verifyClickIncludingExpired(click)).get()
				.extracting(CampaignSafetySigner.VerifiedClick::messageId,
						CampaignSafetySigner.VerifiedClick::linkId,
						CampaignSafetySigner.VerifiedClick::expiresAt)
				.containsExactly(message, link, expiry);
		assertThat(signer.verifyUnsubscribeIncludingExpired(unsubscribe)).get()
				.extracting(CampaignSafetySigner.VerifiedUnsubscribe::messageId,
						CampaignSafetySigner.VerifiedUnsubscribe::expiresAt)
				.containsExactly(message, expiry);
		assertThat(signer.verifyOpenIncludingExpired(unsubscribe)).isEmpty();
		assertThat(signer.verifyClickIncludingExpired(open)).isEmpty();
		assertThat(signer.verifyUnsubscribeIncludingExpired(click)).isEmpty();
	}

	@Test
	void destinationHmacIsCanonicalDomainSeparatedAndDoesNotExposeTheAddress() {
		byte[] first = signer.destinationHmac("Fixed.Inbox@example.com");
		byte[] same = signer.destinationHmac("Fixed.Inbox@example.com");
		byte[] different = signer.destinationHmac("other@example.com");

		assertThat(first).hasSize(32).isEqualTo(same).isNotEqualTo(different);
		assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("Fixed.Inbox", "example.com");
	}

	@Test
	void safetyProductionAndTestMailCapabilitiesAreMutuallyUnverifiable() {
		UUID message = UUID.randomUUID();
		UUID link = UUID.randomUUID();
		Instant expiry = Instant.parse("2035-01-02T03:04:05Z");
		Instant now = expiry.minusSeconds(1);
		String safetyOpen = signer.issueOpen(message, expiry);
		String safetyClick = signer.issueClick(message, link, expiry);
		String safetyUnsubscribe = signer.issueUnsubscribe(message, expiry);
		CampaignTrackingSigner production = new CampaignTrackingSigner(KEY);
		MailTrackingSigner testMail = new MailTrackingSigner(KEY);
		String productionOpen = production.issueOpen(message, expiry);
		String productionClick = production.issueClick(message, link, expiry);
		String productionUnsubscribe = production.issueUnsubscribe(message, expiry);
		String testOpen = testMail.issue(message, expiry);
		String testClick = testMail.issueClick(message, link, expiry);

		assertThat(signer.verifyOpen(productionOpen, now)).isEmpty();
		assertThat(signer.verifyClick(productionClick, now)).isEmpty();
		assertThat(signer.verifyUnsubscribe(productionUnsubscribe, now)).isEmpty();
		assertThat(signer.verifyOpen(testOpen, now)).isEmpty();
		assertThat(signer.verifyClick(testClick, now)).isEmpty();
		assertThat(production.verifyOpen(safetyOpen, now)).isEmpty();
		assertThat(production.verifyClick(safetyClick, now)).isEmpty();
		assertThat(production.verifyUnsubscribe(safetyUnsubscribe, now)).isEmpty();
		assertThat(testMail.verify(safetyOpen, now)).isEmpty();
		assertThat(testMail.verifyClick(safetyClick, now)).isEmpty();
	}
}
