package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.campaign.delivery.CampaignOutboundPreparer;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryExecutor;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryListener;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryScheduler;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryWorkerConfiguration;
import com.camel_hub.advertisement.email.tracking.MailClickController;
import com.camel_hub.advertisement.email.tracking.MailOpenController;
import com.camel_hub.advertisement.messaging.KafkaDeadLetterPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CampaignTrackingConfigurationTest {
	private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
	private static final String CONTACT_HMAC_KEY =
			"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(CampaignTrackingConfiguration.class)
			.withBean(DatabaseClient.class, () -> DatabaseClient.create(
					ConnectionFactories.get("r2dbc:postgresql://unused:unused@localhost/unused")))
			.withBean(ReactiveTransactionManager.class, () -> mock(ReactiveTransactionManager.class));

	@Test
	void apiCallbacksDoNotDependOnDeliveryEnablement() {
		context.withPropertyValues(
				"spring.profiles.active=api",
				"app.campaign-delivery.enabled=false",
				"app.mail-tracking.enabled=true",
				"app.mail-tracking.public-base-url=https://tracking.example.test",
				"app.mail-tracking.signing-key-base64=" + CampaignTrackingDatabaseTestSupport.TRACKING_KEY)
				.run(application -> {
					assertThat(application).hasNotFailed();
					assertThat(application).hasSingleBean(CampaignTrackingService.class);
					assertThat(application).hasSingleBean(CampaignOutboundPreparer.class);
				});
	}

	@Test
	void workerRequiresEnabledTrackingWithAPublicHttpsOrigin() {
		for (String origin : new String[] {
				"http://localhost:8080", "https://localhost", "https://127.0.0.1", "https://worker.local"}) {
			context.withPropertyValues(
					"spring.profiles.active=mail-worker",
					"app.mail-tracking.enabled=true",
					"app.mail-tracking.public-base-url=" + origin,
					"app.mail-tracking.signing-key-base64=" + CampaignTrackingDatabaseTestSupport.TRACKING_KEY)
					.run(application -> {
						assertThat(application).hasFailed();
						assertThat(application.getStartupFailure())
								.hasRootCauseMessage("Campaign callbacks require a public HTTPS origin");
					});
		}
	}

	@Test
	void campaignTrackingKeyMustDifferFromEveryAuthenticationContactSmtpAndAssetKey() {
		for (String property : new String[] {
				"app.auth.signing-key-base64", "app.auth.fingerprint-hmac-key-base64",
				"app.smtp.encryption-key-base64", "app.template.assets.signing-key-base64",
				"app.contact.data-protection.encryption-key-base64",
				"app.contact.data-protection.email-hmac-key-base64"}) {
			context.withPropertyValues(
					"spring.profiles.active=mail-worker",
					"app.mail-tracking.enabled=true",
					"app.mail-tracking.public-base-url=https://tracking.example.test",
					"app.mail-tracking.signing-key-base64=" + CampaignTrackingDatabaseTestSupport.TRACKING_KEY,
					property + "=" + CampaignTrackingDatabaseTestSupport.TRACKING_KEY)
					.run(application -> {
						assertThat(application).hasFailed();
						assertThat(application.getStartupFailure()).hasRootCauseMessage(
								"Campaign tracking signing key must be independent of other application keys");
					});
		}
	}

	@Test
	void workerRejectsATokenLifetimeThatCannotSurviveTheConfiguredRetryHorizon() {
		context.withBean(CampaignDeliveryProperties.class, () -> new CampaignDeliveryProperties(
				true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)))
				.withPropertyValues(
						"spring.profiles.active=mail-worker", "app.campaign-delivery.enabled=true",
						"app.mail-tracking.enabled=true", "app.mail-tracking.token-ttl=PT1M",
						"app.mail-tracking.public-base-url=https://tracking.example.test",
						"app.mail-tracking.signing-key-base64=" + CampaignTrackingDatabaseTestSupport.TRACKING_KEY)
				.run(application -> {
					assertThat(application).hasFailed();
					assertThat(application.getStartupFailure()).hasRootCauseMessage(
							"Campaign tracking lifetime must exceed the delivery retry horizon");
				});
	}

	@Test
	void retryHorizonAccountsForTimeSpentInsideEveryAttemptLease() {
		context.withBean(CampaignDeliveryProperties.class, () -> new CampaignDeliveryProperties(
				true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)))
				.withPropertyValues(
						"spring.profiles.active=mail-worker", "app.campaign-delivery.enabled=true",
						"app.mail-tracking.enabled=true", "app.mail-tracking.token-ttl=PT9M",
						"app.mail-tracking.public-base-url=https://tracking.example.test",
						"app.mail-tracking.signing-key-base64=" + CampaignTrackingDatabaseTestSupport.TRACKING_KEY)
				.run(application -> {
					assertThat(application).hasFailed();
					assertThat(application.getStartupFailure()).hasRootCauseMessage(
							"Campaign tracking lifetime must exceed the delivery retry horizon");
				});
	}

	@Test
	void realTrackingAndDeliveryConfigurationsActivateExactlyOneWorkerRuntimeWithoutControllers() {
		new ApplicationContextRunner()
				.withUserConfiguration(CampaignTrackingConfiguration.class,
						CampaignDeliveryWorkerConfiguration.class, WorkerDependencies.class)
				.withPropertyValues(
						"spring.profiles.active=mail-worker", "app.persistence.enabled=true",
						"app.mail-tracking.enabled=true",
						"app.mail-tracking.public-base-url=https://tracking.example.test",
						"app.mail-tracking.signing-key-base64=" + CampaignTrackingDatabaseTestSupport.TRACKING_KEY,
						"app.smtp.encryption-key-base64=" + OTHER_KEY, "app.smtp.live-allowed=false",
						"app.smtp.local-allowed-hosts=localhost", "app.smtp.connect-timeout=PT5S",
						"app.smtp.read-timeout=PT10S", "app.smtp.write-timeout=PT10S",
						"app.contact.data-protection.encryption-key-base64=" + OTHER_KEY,
						"app.contact.data-protection.email-hmac-key-base64=" + CONTACT_HMAC_KEY,
						"app.campaign-safety.enabled=false", "app.campaign-safety.recipient=",
						"app.campaign-safety.maximum-recipients=20", "app.campaign-delivery.enabled=true",
						"app.campaign-delivery.batch-size=10", "app.campaign-delivery.lease-duration=PT2M",
						"app.campaign-delivery.production-cooldown=P180D",
						"app.campaign-delivery.maximum-attempts=3",
						"app.campaign-delivery.first-retry-delay=PT1M",
						"app.campaign-delivery.second-retry-delay=PT5M",
						"app.campaign-delivery.poll-delay=PT1S")
				.run(application -> {
					assertThat(application).hasNotFailed();
					assertThat(application).hasSingleBean(CampaignTrackingService.class);
					assertThat(application).hasSingleBean(CampaignOutboundPreparer.class);
					assertThat(application).hasSingleBean(CampaignDeliveryRepository.class);
					assertThat(application).hasSingleBean(CampaignDeliveryExecutor.class);
					assertThat(application).hasSingleBean(CampaignDeliveryListener.class);
					assertThat(application).hasSingleBean(CampaignDeliveryScheduler.class);
					assertThat(application).doesNotHaveBean(CampaignUnsubscribeController.class);
					assertThat(application).doesNotHaveBean(MailOpenController.class);
					assertThat(application).doesNotHaveBean(MailClickController.class);
				});
	}

	@Test
	void disabledTrackingCreatesNoRealPreparer() {
		context.withPropertyValues(
				"spring.profiles.active=mail-worker",
				"app.mail-tracking.enabled=false",
				"app.mail-tracking.public-base-url=http://localhost:8080")
				.run(application -> {
					assertThat(application).hasNotFailed();
					assertThat(application).doesNotHaveBean(CampaignTrackingService.class);
					assertThat(application).doesNotHaveBean(CampaignOutboundPreparer.class);
				});
	}

	@Configuration(proxyBeanMethods = false)
	static class WorkerDependencies {
		@Bean DatabaseClient databaseClient() {
			return DatabaseClient.create(ConnectionFactories.get(
					"r2dbc:postgresql://unused:unused@localhost/unused"));
		}
		@Bean ReactiveTransactionManager reactiveTransactionManager() {
			return mock(ReactiveTransactionManager.class);
		}
		@Bean ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
		@Bean KafkaDeadLetterPublisher kafkaDeadLetterPublisher() {
			return mock(KafkaDeadLetterPublisher.class);
		}
	}
}
