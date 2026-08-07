package com.camel_hub.advertisement.email.template;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.template")
public record TemplateProperties(int maxContentBytes) {
	public TemplateProperties {
		if (maxContentBytes < 1_024 || maxContentBytes > 1_048_576) {
			throw new IllegalStateException("Template content byte limit is invalid");
		}
	}
}
