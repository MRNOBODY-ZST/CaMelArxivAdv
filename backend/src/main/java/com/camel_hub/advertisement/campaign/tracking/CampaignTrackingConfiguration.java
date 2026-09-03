package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailTrackingModels;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "mail-worker"})
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "app.mail-tracking", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MailTrackingProperties.class)
public class CampaignTrackingConfiguration {

	@Bean
	CampaignTrackingRepository campaignTrackingRepository(DatabaseClient database) {
		return new CampaignTrackingRepository(database);
	}

	@Bean
	@Order(100)
	CampaignTrackingService campaignTrackingService(
			CampaignTrackingRepository repository, MailTrackingProperties properties,
			ObjectProvider<Clock> clocks, ObjectProvider<CampaignDeliveryProperties> deliveryProperties,
			ReactiveTransactionManager transactionManager, Environment environment
	) {
		validateConfiguration(properties, deliveryProperties.getIfAvailable(), environment);
		CampaignTrackingSigner signer = new CampaignTrackingSigner(properties.signingKeyBase64());
		Clock clock = clocks.getIfUnique(Clock::systemUTC);
		return new CampaignTrackingService(
				repository, properties, signer, new MailOpenClassifier(), clock,
				TransactionalOperator.create(transactionManager));
	}

	private void validateConfiguration(
			MailTrackingProperties properties, CampaignDeliveryProperties delivery, Environment environment
	) {
		if (environment.matchesProfiles("mail-worker")
				&& properties.callbackScope() != MailTrackingModels.CallbackScope.PUBLIC_HTTPS_CONFIGURED) {
			throw new IllegalStateException("Campaign callbacks require a public HTTPS origin");
		}
		if (environment.matchesProfiles("mail-worker")
				&& environment.getProperty("app.campaign-delivery.enabled", Boolean.class, false)) {
			if (delivery == null) throw new IllegalStateException("Campaign delivery tracking lifetime is unavailable");
			Duration retryHorizon = delivery.leaseDuration().multipliedBy(delivery.maximumAttempts());
			if (delivery.maximumAttempts() >= 2) retryHorizon = retryHorizon.plus(delivery.firstRetryDelay());
			if (delivery.maximumAttempts() >= 3) retryHorizon = retryHorizon.plus(delivery.secondRetryDelay());
			if (properties.tokenTtl().compareTo(retryHorizon) <= 0) {
				throw new IllegalStateException("Campaign tracking lifetime must exceed the delivery retry horizon");
			}
		}
		byte[] key = decode(properties.signingKeyBase64());
		try {
			for (String name : new String[] {
					"app.auth.signing-key-base64", "app.auth.fingerprint-hmac-key-base64",
					"app.smtp.encryption-key-base64", "app.template.assets.signing-key-base64",
					"app.contact.data-protection.encryption-key-base64",
					"app.contact.data-protection.email-hmac-key-base64"}) {
				String configured = environment.getProperty(name);
				if (configured == null || configured.isBlank()) continue;
				byte[] other;
				try {
					other = Base64.getDecoder().decode(configured);
				}
				catch (IllegalArgumentException ignored) {
					continue;
				}
				try {
					if (MessageDigest.isEqual(key, other)) {
						throw new IllegalStateException(
								"Campaign tracking signing key must be independent of other application keys");
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

	private byte[] decode(String value) {
		try {
			return Base64.getDecoder().decode(value == null ? "" : value);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Campaign tracking signing key is invalid");
		}
	}
}
