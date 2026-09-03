package com.camel_hub.advertisement.campaign.inbound;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryWorkerConfiguration;
import com.camel_hub.advertisement.email.mailbox.MailboxTransport;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InboundMailConfigurationTest {
	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(
					CampaignDeliveryWorkerConfiguration.class, InboundMailConfiguration.class, Dependencies.class)
			.withPropertyValues(
					"spring.profiles.active=mail-worker",
					"app.persistence.enabled=true",
					"app.campaign-delivery.enabled=true",
					"app.campaign-delivery.batch-size=10",
					"app.campaign-delivery.lease-duration=PT2M",
					"app.campaign-delivery.production-cooldown=P180D",
					"app.campaign-delivery.maximum-attempts=3",
					"app.campaign-delivery.first-retry-delay=PT1M",
					"app.campaign-delivery.second-retry-delay=PT5M",
					"app.campaign-delivery.poll-delay=PT1S",
					"app.campaign-safety.enabled=false",
					"app.campaign-safety.recipient=",
					"app.campaign-safety.maximum-recipients=20",
					"app.campaign-inbound.poll-delay=PT30S",
					"app.campaign-inbound.lease-duration=PT2M",
					"app.campaign-inbound.batch-size=50",
					"app.smtp.encryption-key-base64=" + KEY,
					"app.smtp.live-allowed=false",
					"app.smtp.local-allowed-hosts=localhost",
					"app.smtp.connect-timeout=PT5S",
					"app.smtp.read-timeout=PT10S",
					"app.smtp.write-timeout=PT10S",
					"app.contact.data-protection.encryption-key-base64=" + KEY,
					"app.contact.data-protection.email-hmac-key-base64=" + OTHER_KEY,
					"app.mailbox.public-allowed=false",
					"app.mailbox.local-allowed-hosts=localhost",
					"app.mailbox.connect-timeout=PT5S",
					"app.mailbox.read-timeout=PT10S",
					"app.mailbox.max-preview-messages=50");

	@Test
	void enabledWorkerWiresOneReadOnlyInboundRuntime() {
		runner.withPropertyValues("app.campaign-inbound.enabled=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(InboundMailRepository.class);
			assertThat(context).hasSingleBean(InboundMailParser.class);
			assertThat(context).hasSingleBean(MailboxTransport.class);
			assertThat(context).hasSingleBean(InboundMailSynchronizer.class);
		});
	}

	@Test
	void disabledOrNonWorkerProfilesCreateNoInboundRuntime() {
		runner.withPropertyValues("app.campaign-inbound.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(InboundMailSynchronizer.class));
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("api"))
				.withPropertyValues("app.campaign-inbound.enabled=true")
				.run(context -> assertThat(context).doesNotHaveBean(InboundMailSynchronizer.class));
	}

	@Test
	void inboundWorkerCanRunWithoutEnablingOutboundDelivery() {
		runner.withPropertyValues(
				"app.campaign-delivery.enabled=false",
				"app.campaign-inbound.enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(MailboxTransport.class);
					assertThat(context).hasSingleBean(InboundMailSynchronizer.class);
				});
	}

	@Test
	void rejectsUnsafeLeaseAndBatchConfiguration() {
		assertThatThrownBy(() -> new InboundMailProperties(
				true, Duration.ofSeconds(30), Duration.ofSeconds(29), 50))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InboundMailProperties(
				true, Duration.ofSeconds(30), Duration.ofMinutes(2), 51))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Configuration(proxyBeanMethods = false)
	static class Dependencies {
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
