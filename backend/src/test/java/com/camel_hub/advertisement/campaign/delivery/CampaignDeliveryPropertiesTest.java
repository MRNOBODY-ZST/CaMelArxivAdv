package com.camel_hub.advertisement.campaign.delivery;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignDeliveryPropertiesTest {
	@Test
	void defaultsUseTheSafeDeliverySchedule() {
		CampaignDeliveryProperties properties = new CampaignDeliveryProperties(
				false, 10, null, null, 3, null, null, null);

		assertThat(properties.enabled()).isFalse();
		assertThat(properties.batchSize()).isEqualTo(10);
		assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(2));
		assertThat(properties.productionCooldown()).isEqualTo(Duration.ofDays(180));
		assertThat(properties.maximumAttempts()).isEqualTo(3);
		assertThat(properties.firstRetryDelay()).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.secondRetryDelay()).isEqualTo(Duration.ofMinutes(5));
		assertThat(properties.pollDelay()).isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void rejectsUnsafeDeliveryBoundsAndRetryOrdering() {
		for (int batchSize : new int[] {0, 101}) {
			assertThatThrownBy(() -> delivery(batchSize, Duration.ofMinutes(2), Duration.ofDays(180), 3,
					Duration.ofMinutes(1), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
		}
		for (Duration lease : new Duration[] {Duration.ofSeconds(29), Duration.ofMinutes(15).plusSeconds(1)}) {
			assertThatThrownBy(() -> delivery(10, lease, Duration.ofDays(180), 3, Duration.ofMinutes(1), Duration.ofMinutes(5)))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> delivery(10, Duration.ofMinutes(2), Duration.ofHours(23), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
		for (int attempts : new int[] {0, 4}) {
			assertThatThrownBy(() -> delivery(10, Duration.ofMinutes(2), Duration.ofDays(180), attempts,
					Duration.ofMinutes(1), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> delivery(10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(5), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validatesSafetyRecipientOnlyWhenSafetyModeIsEnabled() {
		assertThat(new CampaignSafetyProperties(false, "", 20).validatedRecipient()).isBlank();
		assertThat(new CampaignSafetyProperties(true, "zstbmw@163.com", 20).validatedRecipient())
				.isEqualTo("zstbmw@163.com");
		for (int maximumRecipients : new int[] {0, 21}) {
			assertThatThrownBy(() -> new CampaignSafetyProperties(false, "", maximumRecipients))
					.isInstanceOf(IllegalArgumentException.class);
		}
		for (String recipient : new String[] {"", " ", "not-an-email", "name@example"}) {
			assertThatThrownBy(() -> new CampaignSafetyProperties(true, recipient, 20).validatedRecipient())
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	private CampaignDeliveryProperties delivery(
			int batchSize, Duration leaseDuration, Duration productionCooldown, int maximumAttempts,
			Duration firstRetryDelay, Duration secondRetryDelay
	) {
		return new CampaignDeliveryProperties(true, batchSize, leaseDuration, productionCooldown, maximumAttempts,
				firstRetryDelay, secondRetryDelay, Duration.ofSeconds(1));
	}
}
