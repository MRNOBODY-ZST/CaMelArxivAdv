package com.camel_hub.advertisement.campaign.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.campaign-delivery")
public record CampaignDeliveryProperties(
		boolean enabled,
		int batchSize,
		Duration leaseDuration,
		Duration productionCooldown,
		int maximumAttempts,
		Duration firstRetryDelay,
		Duration secondRetryDelay,
		Duration pollDelay
) {
	public CampaignDeliveryProperties {
		if (batchSize < 1 || batchSize > 100) {
			throw new IllegalArgumentException("Campaign delivery batch size must be between 1 and 100");
		}
		leaseDuration = defaulted(leaseDuration, Duration.ofMinutes(2));
		productionCooldown = defaulted(productionCooldown, Duration.ofDays(180));
		firstRetryDelay = defaulted(firstRetryDelay, Duration.ofMinutes(1));
		secondRetryDelay = defaulted(secondRetryDelay, Duration.ofMinutes(5));
		pollDelay = defaulted(pollDelay, Duration.ofSeconds(1));
		if (leaseDuration.compareTo(Duration.ofSeconds(30)) < 0 || leaseDuration.compareTo(Duration.ofMinutes(15)) > 0) {
			throw new IllegalArgumentException("Campaign delivery lease duration must be between 30 seconds and 15 minutes");
		}
		if (productionCooldown.compareTo(Duration.ofDays(1)) < 0) {
			throw new IllegalArgumentException("Campaign delivery cooldown must be at least one day");
		}
		if (maximumAttempts < 1 || maximumAttempts > 3) {
			throw new IllegalArgumentException("Campaign delivery maximum attempts must be between 1 and 3");
		}
		if (firstRetryDelay.isNegative() || firstRetryDelay.isZero() || secondRetryDelay.compareTo(firstRetryDelay) <= 0) {
			throw new IllegalArgumentException("Campaign delivery retry delays must be positive and increasing");
		}
		if (pollDelay.isNegative() || pollDelay.isZero()) {
			throw new IllegalArgumentException("Campaign delivery poll delay must be positive");
		}
	}

	private static Duration defaulted(Duration value, Duration fallback) {
		return value == null ? fallback : value;
	}
}
