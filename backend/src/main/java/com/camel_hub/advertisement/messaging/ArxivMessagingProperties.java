package com.camel_hub.advertisement.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties("app.messaging")
public record ArxivMessagingProperties(Duration sourceCompletionGrace) {

	@ConstructorBinding
	public ArxivMessagingProperties {
		if (sourceCompletionGrace == null) {
			sourceCompletionGrace = Duration.ofMinutes(15);
		}
		if (sourceCompletionGrace.compareTo(Duration.ofMinutes(5)) < 0
				|| sourceCompletionGrace.compareTo(Duration.ofDays(1)) > 0
				|| sourceCompletionGrace.getNano() != 0) {
			throw new IllegalArgumentException(
					"Source completion grace must be whole seconds between five minutes and one day");
		}
	}
}
