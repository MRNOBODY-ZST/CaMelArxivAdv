package com.camel_hub.advertisement.campaign.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

@ConfigurationProperties("app.campaign-safety")
public record CampaignSafetyProperties(boolean enabled, String recipient, int maximumRecipients) {
	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	public CampaignSafetyProperties {
		recipient = recipient == null ? "" : recipient.strip();
		if (maximumRecipients < 1 || maximumRecipients > 20) {
			throw new IllegalArgumentException("Campaign safety recipient limit must be between 1 and 20");
		}
	}

	public String validatedRecipient() {
		if (enabled && !EMAIL.matcher(recipient).matches()) {
			throw new IllegalArgumentException("Campaign safety recipient must be a valid email address when safety mode is enabled");
		}
		return recipient;
	}
}
