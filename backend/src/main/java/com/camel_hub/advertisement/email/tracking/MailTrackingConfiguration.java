package com.camel_hub.advertisement.email.tracking;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

@Configuration
@Profile("api")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MailTrackingProperties.class)
@EnableScheduling
public class MailTrackingConfiguration {
	@Bean
	MailTrackingRepository mailTrackingRepository(DatabaseClient database) {
		return new MailTrackingRepository(database);
	}

	@Bean
	Clock mailTrackingClock() {
		return Clock.systemUTC();
	}

	@Bean
	MailTrackingService mailTrackingService(
			MailTrackingRepository repository, MailTrackingProperties properties, @Qualifier("mailTrackingClock") Clock clock,
			Environment environment, TransactionalOperator transactions
	) {
		if (properties.enabled()) validateIndependentKey(properties, environment);
		return new MailTrackingService(repository, properties,
				properties.enabled() ? new MailTrackingSigner(properties.signingKeyBase64()) : null,
				new MailOpenClassifier(), clock, transactions);
	}

	@Bean
	MailSendReconciliationJob mailSendReconciliationJob(
			MailTrackingRepository repository, MailTrackingProperties properties,
			@Qualifier("mailTrackingClock") Clock clock
	) {
		return new MailSendReconciliationJob(repository, properties, clock);
	}

	private void validateIndependentKey(MailTrackingProperties properties, Environment environment) {
		byte[] key = MailTrackingProperties.decodeKey(properties.signingKeyBase64());
		try {
			for (String name : new String[] {"app.auth.signing-key-base64", "app.auth.fingerprint-hmac-key-base64",
					"app.smtp.encryption-key-base64", "app.template.assets.signing-key-base64",
					"app.contact.data-protection.encryption-key-base64", "app.contact.data-protection.email-hmac-key-base64"}) {
				String configured = environment.getProperty(name);
				if (configured == null || configured.isBlank()) continue;
				byte[] other;
				try {
					other = Base64.getDecoder().decode(configured);
				}
				catch (IllegalArgumentException ignored) {
					// Other subsystems validate their own key formats.
					continue;
				}
				try {
					if (MessageDigest.isEqual(key, other)) {
						throw new IllegalStateException("Tracking signing key must be independent of other application keys");
					}
				}
				finally {
					Arrays.fill(other, (byte) 0);
				}
			}
		}
		finally {
			Arrays.fill(key, (byte) 0);
		}
	}
}
