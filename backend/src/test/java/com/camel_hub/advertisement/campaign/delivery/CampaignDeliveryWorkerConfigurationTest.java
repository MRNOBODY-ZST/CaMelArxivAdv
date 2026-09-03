package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.safety.CampaignSafetyConfiguration;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRuntimePolicy;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyTrackingService;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyController;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyService;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetySigner;
import com.camel_hub.advertisement.campaign.tracking.CampaignTrackingConfiguration;
import com.camel_hub.advertisement.messaging.KafkaDeadLetterPublisher;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpPolicy;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CampaignDeliveryWorkerConfigurationTest {

	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
	private static final String TRACKING_KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmM=";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(CampaignDeliveryWorkerConfiguration.class, Dependencies.class)
			.withPropertyValues(
					"spring.profiles.active=mail-worker",
					"app.persistence.enabled=true",
					"app.smtp.encryption-key-base64=" + KEY,
					"app.smtp.live-allowed=false",
					"app.smtp.local-allowed-hosts=localhost",
					"app.smtp.connect-timeout=PT5S",
					"app.smtp.read-timeout=PT10S",
					"app.smtp.write-timeout=PT10S",
					"app.contact.data-protection.encryption-key-base64=" + KEY,
					"app.contact.data-protection.email-hmac-key-base64=" + OTHER_KEY,
					"app.campaign-safety.enabled=false",
					"app.campaign-safety.recipient=",
					"app.campaign-safety.maximum-recipients=20",
					"app.campaign-delivery.batch-size=10",
					"app.campaign-delivery.lease-duration=PT2M",
					"app.campaign-delivery.production-cooldown=P180D",
					"app.campaign-delivery.maximum-attempts=3",
					"app.campaign-delivery.first-retry-delay=PT1M",
					"app.campaign-delivery.second-retry-delay=PT5M",
					"app.campaign-delivery.poll-delay=PT1S");

	@Test
	void deliveryDisabledCreatesNoExecutorListenerSchedulerOrTransportInfrastructure() {
		runner.withPropertyValues("app.campaign-delivery.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(CampaignDeliveryRepository.class);
			assertThat(context).doesNotHaveBean(CampaignDeliveryExecutor.class);
			assertThat(context).doesNotHaveBean(CampaignDeliveryListener.class);
			assertThat(context).doesNotHaveBean(CampaignDeliveryScheduler.class);
		});
	}

	@Test
	void enabledWithoutRealPreparerCannotCreateSendingRuntime() {
		runner.withPropertyValues("app.campaign-delivery.enabled=true").run(context -> {
			assertThat(context).hasSingleBean(CampaignDeliveryRepository.class);
			assertThat(context).hasSingleBean(ContactCrypto.class);
			assertThat(context).hasSingleBean(SmtpRepository.class);
			assertThat(context).hasSingleBean(SmtpSecretCrypto.class);
			assertThat(context).hasSingleBean(SmtpPolicy.class);
			assertThat(context).hasSingleBean(SmtpTransport.class);
			assertThat(context).doesNotHaveBean(CampaignDeliveryExecutor.class);
			assertThat(context).doesNotHaveBean(CampaignDeliveryListener.class);
			assertThat(context).doesNotHaveBean(CampaignDeliveryScheduler.class);
		});
	}

	@Test
	void disabledEvenWithPreparerStillCreatesNoDeliveryRuntime() {
		runner.withUserConfiguration(Preparer.class)
				.withPropertyValues("app.campaign-delivery.enabled=false")
				.run(context -> {
					assertThat(context).doesNotHaveBean(CampaignDeliveryRepository.class);
					assertThat(context).doesNotHaveBean(CampaignDeliveryExecutor.class);
					assertThat(context).doesNotHaveBean(CampaignDeliveryListener.class);
					assertThat(context).doesNotHaveBean(CampaignDeliveryScheduler.class);
				});
	}

	@Test
	void nonWorkerProfileCannotCreateDeliveryInfrastructure() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("api"))
				.withUserConfiguration(Preparer.class)
				.withPropertyValues("app.campaign-delivery.enabled=true")
				.run(context -> {
					assertThat(context).doesNotHaveBean(CampaignDeliveryRepository.class);
					assertThat(context).doesNotHaveBean(CampaignDeliveryExecutor.class);
					assertThat(context).doesNotHaveBean(CampaignDeliveryListener.class);
					assertThat(context).doesNotHaveBean(CampaignDeliveryScheduler.class);
				});
	}

	@Test
	void enabledWithRealPreparerCreatesOneFullyWiredRuntime() {
		runner.withUserConfiguration(Preparer.class)
				.withPropertyValues("app.campaign-delivery.enabled=true")
				.run(context -> {
					assertThat(context).hasSingleBean(CampaignDeliveryRepository.class);
					assertThat(context).hasSingleBean(ContactCrypto.class);
					assertThat(context).hasSingleBean(SmtpRepository.class);
					assertThat(context).hasSingleBean(SmtpPolicy.class);
					assertThat(context).hasSingleBean(SmtpSecretCrypto.class);
					assertThat(context).hasSingleBean(SmtpTransport.class);
					assertThat(context).hasSingleBean(CampaignDeliveryExecutor.class);
					assertThat(context).hasSingleBean(CampaignDeliveryListener.class);
					assertThat(context).hasSingleBean(CampaignDeliveryScheduler.class);
				});
	}

	@Test
	void enabledSafetyModeWiresWorkerOnlySafetyRuntimeAndNoWebControllers() {
		new ApplicationContextRunner()
				.withUserConfiguration(CampaignDeliveryWorkerConfiguration.class,
						CampaignTrackingConfiguration.class, CampaignSafetyConfiguration.class, Dependencies.class)
				.withPropertyValues(
						"spring.profiles.active=mail-worker",
						"app.persistence.enabled=true",
						"app.smtp.encryption-key-base64=" + KEY,
						"app.smtp.live-allowed=true",
						"app.smtp.local-allowed-hosts=localhost",
						"app.smtp.connect-timeout=PT5S",
						"app.smtp.read-timeout=PT10S",
						"app.smtp.write-timeout=PT10S",
						"app.contact.data-protection.encryption-key-base64=" + KEY,
						"app.contact.data-protection.email-hmac-key-base64=" + OTHER_KEY,
						"app.mail-tracking.enabled=true",
						"app.mail-tracking.public-base-url=https://tracking.example.test",
						"app.mail-tracking.signing-key-base64=" + TRACKING_KEY,
						"app.mail-tracking.token-ttl=P30D",
						"app.mail-tracking.stale-sending-after=PT15M",
						"app.campaign-safety.enabled=true",
						"app.campaign-safety.recipient=fixed@example.test",
						"app.campaign-safety.maximum-recipients=20",
						"app.campaign-delivery.enabled=true",
						"app.campaign-delivery.batch-size=10",
						"app.campaign-delivery.lease-duration=PT2M",
						"app.campaign-delivery.production-cooldown=P180D",
						"app.campaign-delivery.maximum-attempts=3",
						"app.campaign-delivery.first-retry-delay=PT1M",
						"app.campaign-delivery.second-retry-delay=PT5M",
						"app.campaign-delivery.poll-delay=PT1S")
				.run(context -> {
					assertThat(context).hasSingleBean(CampaignSafetyRuntimePolicy.class);
					assertThat(context).hasSingleBean(CampaignSafetyRepository.class);
					assertThat(context).hasSingleBean(CampaignSafetyTrackingService.class);
					assertThat(context).hasSingleBean(CampaignDeliveryExecutor.class);
					assertThat(context).doesNotHaveBean(CampaignSafetyController.class);
				});
	}

	@Test
	void enabledSafetyModeRejectsTrackingLifetimeThatCannotOutliveOneDeliveryLeaseAtStartup() {
		new ApplicationContextRunner()
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("api"))
				.withUserConfiguration(CampaignSafetyConfiguration.class, Dependencies.class)
				.withBean(CampaignDeliveryProperties.class, () -> new CampaignDeliveryProperties(
						true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
						Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)))
				.withPropertyValues(
						"app.persistence.enabled=true",
						"app.smtp.live-allowed=true",
						"app.smtp.local-allowed-hosts=localhost",
						"app.smtp.connect-timeout=PT5S",
						"app.smtp.read-timeout=PT10S",
						"app.smtp.write-timeout=PT10S",
						"app.mail-tracking.enabled=true",
						"app.mail-tracking.public-base-url=https://tracking.example.test",
						"app.mail-tracking.signing-key-base64=" + TRACKING_KEY,
						"app.mail-tracking.token-ttl=PT1M",
						"app.mail-tracking.stale-sending-after=PT15M",
						"app.campaign-delivery.enabled=true",
						"app.campaign-delivery.batch-size=10",
						"app.campaign-delivery.lease-duration=PT2M",
						"app.campaign-delivery.production-cooldown=P180D",
						"app.campaign-delivery.maximum-attempts=3",
						"app.campaign-delivery.first-retry-delay=PT1M",
						"app.campaign-delivery.second-retry-delay=PT5M",
						"app.campaign-delivery.poll-delay=PT1S",
						"app.campaign-safety.enabled=true",
						"app.campaign-safety.recipient=fixed@example.test",
						"app.campaign-safety.maximum-recipients=20")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseMessage(
							"Campaign safety tracking lifetime must safely exceed the delivery lease");
				});
	}

	@Test
	void disabledSafetyModeKeepsDurableWorkerAndCallbackRuntimeWithBlankDestination() {
		runner.withUserConfiguration(
				CampaignTrackingConfiguration.class, CampaignSafetyConfiguration.class)
				.withPropertyValues(
						"app.campaign-delivery.enabled=true",
						"app.mail-tracking.enabled=true",
						"app.mail-tracking.public-base-url=https://tracking.example.test",
						"app.mail-tracking.signing-key-base64=" + TRACKING_KEY,
						"app.mail-tracking.token-ttl=P30D",
						"app.mail-tracking.stale-sending-after=PT15M",
						"app.campaign-safety.enabled=false",
						"app.campaign-safety.recipient=")
				.run(context -> {
					assertThat(context).hasSingleBean(CampaignSafetyRepository.class);
					assertThat(context).hasSingleBean(CampaignSafetySigner.class);
					assertThat(context).hasSingleBean(CampaignSafetyRuntimePolicy.class);
					assertThat(context).hasSingleBean(CampaignSafetyTrackingService.class);
					assertThat(context).hasSingleBean(CampaignDeliveryExecutor.class);
					assertThat(context).hasSingleBean(CampaignDeliveryScheduler.class);
					assertThat(context).doesNotHaveBean(CampaignSafetyController.class);
				});
	}

	@Test
	void disabledSafetyAndTrackingStillExposeApiReadCancelButEnabledSafetyFailsStartup() {
		ApplicationContextRunner api = new ApplicationContextRunner()
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("api"))
				.withUserConfiguration(
						CampaignSafetyConfiguration.class, CampaignSafetyController.class, Dependencies.class)
				.withPropertyValues(
						"app.persistence.enabled=true",
						"app.smtp.live-allowed=false",
						"app.smtp.connect-timeout=PT5S",
						"app.smtp.read-timeout=PT10S",
						"app.smtp.write-timeout=PT10S",
						"app.mail-tracking.enabled=false",
						"app.mail-tracking.public-base-url=http://localhost:8080",
						"app.mail-tracking.token-ttl=P30D",
						"app.mail-tracking.stale-sending-after=PT15M",
						"app.campaign-safety.maximum-recipients=20");
		api.withPropertyValues(
				"app.campaign-safety.enabled=false", "app.campaign-safety.recipient=")
				.run(context -> {
					assertThat(context).hasSingleBean(CampaignSafetyRepository.class);
					assertThat(context).hasSingleBean(CampaignSafetyService.class);
					assertThat(context).hasSingleBean(CampaignSafetyController.class);
					assertThat(context).doesNotHaveBean(CampaignSafetySigner.class);
					assertThat(context).doesNotHaveBean(CampaignSafetyTrackingService.class);
				});
		api.withPropertyValues(
				"app.campaign-safety.enabled=true",
				"app.campaign-safety.recipient=fixed@example.test")
				.run(context -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	static class Dependencies {
		@Bean DatabaseClient databaseClient() {
			return mock(DatabaseClient.class);
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

	@Configuration(proxyBeanMethods = false)
	static class Preparer {
		@Bean CampaignOutboundPreparer campaignOutboundPreparer() {
			return claim -> Mono.just(new CampaignOutboundPreparer.PreparedOutbound(
					"Final subject", "<p>Final body</p>", "Final body", java.util.Map.of(
							"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
							"List-Unsubscribe-Post", "List-Unsubscribe=One-Click")));
		}
	}
}
