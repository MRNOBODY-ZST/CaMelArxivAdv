package com.camel_hub.advertisement.campaign;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.personalization")
public record PersonalizationProperties(
		boolean enabled,
		String provider,
		String model,
		int maxRecipients
) {
	public PersonalizationProperties {
		provider = provider == null || provider.isBlank() ? "openai" : provider.strip();
		model = model == null || model.isBlank() ? "gpt-5.6-luna" : model.strip();
		if (provider.length() > 80 || model.length() > 120) {
			throw new IllegalArgumentException("Personalization provider configuration is invalid");
		}
		if (maxRecipients < 1 || maxRecipients > 1_000) {
			throw new IllegalArgumentException("Personalization recipient limit must be between 1 and 1000");
		}
	}
}
