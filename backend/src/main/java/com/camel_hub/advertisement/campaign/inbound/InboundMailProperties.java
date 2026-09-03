package com.camel_hub.advertisement.campaign.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.campaign-inbound")
public record InboundMailProperties(
		boolean enabled, Duration pollDelay, Duration leaseDuration, int batchSize
) {
	public InboundMailProperties {
		if (pollDelay == null || pollDelay.isZero() || pollDelay.isNegative()) {
			throw new IllegalArgumentException("Inbound mailbox poll delay must be positive");
		}
		if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(30)) < 0
				|| leaseDuration.compareTo(Duration.ofMinutes(15)) > 0) {
			throw new IllegalArgumentException(
					"Inbound mailbox lease duration must be between 30 seconds and 15 minutes");
		}
		if (batchSize < 1 || batchSize > 50) {
			throw new IllegalArgumentException("Inbound mailbox batch size must be between 1 and 50");
		}
	}
}
