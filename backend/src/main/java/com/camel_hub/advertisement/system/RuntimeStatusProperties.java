package com.camel_hub.advertisement.system;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.runtime")
public record RuntimeStatusProperties(
		boolean rayConfigured,
		boolean kafkaConfigured,
		boolean liveSmtpAllowed,
		boolean publicMailboxAllowed
) { }
