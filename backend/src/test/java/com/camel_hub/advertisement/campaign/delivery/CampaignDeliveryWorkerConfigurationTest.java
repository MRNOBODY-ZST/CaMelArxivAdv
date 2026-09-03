package com.camel_hub.advertisement.campaign.delivery;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CampaignDeliveryWorkerConfigurationTest {

	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

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
