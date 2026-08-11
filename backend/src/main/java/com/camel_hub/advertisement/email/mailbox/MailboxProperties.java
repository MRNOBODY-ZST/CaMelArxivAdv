package com.camel_hub.advertisement.email.mailbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("app.mailbox")
public record MailboxProperties(
		boolean publicAllowed,
		Set<String> localAllowedHosts,
		Duration connectTimeout,
		Duration readTimeout,
		int maxPreviewMessages
) {
	public MailboxProperties {
		localAllowedHosts = localAllowedHosts == null ? Set.of() : Set.copyOf(localAllowedHosts);
		if (connectTimeout == null || readTimeout == null
				|| connectTimeout.isNegative() || connectTimeout.isZero()
				|| readTimeout.isNegative() || readTimeout.isZero()) {
			throw new IllegalStateException("Mailbox timeouts must be positive");
		}
		if (maxPreviewMessages < 1 || maxPreviewMessages > 100) {
			throw new IllegalStateException("Mailbox preview limit must be between 1 and 100");
		}
	}
}
