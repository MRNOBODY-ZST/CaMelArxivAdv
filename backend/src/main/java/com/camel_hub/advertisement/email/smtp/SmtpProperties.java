package com.camel_hub.advertisement.email.smtp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("app.smtp")
public record SmtpProperties(
		boolean liveAllowed,
		Set<String> localAllowedHosts,
		Duration connectTimeout,
		Duration readTimeout,
		Duration writeTimeout,
		String encryptionKeyBase64
) {
	public SmtpProperties {
		localAllowedHosts = localAllowedHosts == null ? Set.of() : Set.copyOf(localAllowedHosts);
		if (connectTimeout == null || readTimeout == null || writeTimeout == null
				|| connectTimeout.isNegative() || connectTimeout.isZero()
				|| readTimeout.isNegative() || readTimeout.isZero()
				|| writeTimeout.isNegative() || writeTimeout.isZero()) {
			throw new IllegalStateException("SMTP timeouts must be positive");
		}
	}
}
