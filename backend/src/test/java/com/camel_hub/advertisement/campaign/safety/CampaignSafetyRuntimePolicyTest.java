package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignSafetyRuntimePolicyTest {
	private static final String KEY = Base64.getEncoder().encodeToString(
			"campaign-safety-policy-key-32-bytes".getBytes(StandardCharsets.US_ASCII));

	@Test
	void requiresEnabledLiveSmtpAndPublicHttpsTracking() {
		assertThatThrownBy(() -> policy(false, true, true, "https://tracking.example.test").requireReady())
				.hasMessageContaining("disabled");
		assertThatThrownBy(() -> policy(true, false, true, "https://tracking.example.test"))
				.hasMessageContaining("Live SMTP");
		assertThatThrownBy(() -> policy(true, true, false, "https://tracking.example.test"))
				.hasMessageContaining("tracking");
		assertThatThrownBy(() -> policy(true, true, true, "http://localhost:8080"))
				.hasMessageContaining("Public HTTPS");
	}

	@Test
	void destinationSnapshotContainsOnlyCanonicalAddressDomainMaskAndHmac() {
		CampaignSafetyRuntimePolicy policy = policy(true, true, true, "https://tracking.example.test");

		CampaignSafetyRuntimePolicy.Destination destination = policy.requireReady();

		assertThat(destination.address()).isEqualTo("Fixed.Inbox@example.com");
		assertThat(destination.domain()).isEqualTo("example.com");
		assertThat(destination.masked()).isEqualTo("F***@example.com");
		assertThat(destination.hmac()).hasSize(32);
		assertThat(policy.requireMatching(destination.hmac()).address()).isEqualTo("Fixed.Inbox@example.com");
		byte[] changed = destination.hmac();
		changed[0] ^= 1;
		assertThatThrownBy(() -> policy.requireMatching(changed)).hasMessageContaining("changed");
	}

	@Test
	void trackingLifetimeIncludesOneSecondOfHeadroomBeyondTheDeliveryLease() {
		assertThatThrownBy(() -> policy(true, true, true, "https://tracking.example.test",
				Duration.ofMinutes(2), Duration.ofMinutes(2)))
				.hasMessage("Campaign safety tracking lifetime must safely exceed the delivery lease");
		assertThat(policy(true, true, true, "https://tracking.example.test",
				Duration.ofSeconds(121), Duration.ofMinutes(2)).requireReady()).isNotNull();
	}

	private CampaignSafetyRuntimePolicy policy(
			boolean enabled, boolean liveSmtp, boolean trackingEnabled, String origin
	) {
		return policy(enabled, liveSmtp, trackingEnabled, origin, Duration.ofDays(30), Duration.ofMinutes(2));
	}

	private CampaignSafetyRuntimePolicy policy(
			boolean enabled, boolean liveSmtp, boolean trackingEnabled, String origin,
			Duration tokenTtl, Duration deliveryLease
	) {
		CampaignSafetyProperties safety = new CampaignSafetyProperties(enabled, "Fixed.Inbox@EXAMPLE.COM", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				trackingEnabled, origin, KEY, tokenTtl, Duration.ofMinutes(15));
		return new CampaignSafetyRuntimePolicy(
				safety, new SmtpProperties(liveSmtp, Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking,
				new CampaignSafetySigner(KEY), deliveryLease);
	}
}
