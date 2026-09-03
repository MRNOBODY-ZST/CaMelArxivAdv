package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeliveryMessagingConfigurationTest {

	@Test
	void declaresOnlyThePrivacyMinimalDeliveryWakeupTopicForSevenDays() {
		assertThat(DeliveryMessagingConfiguration.topics())
				.extracting(NewTopic::name)
				.containsExactly("camel.mail.delivery.jobs.v1");
		NewTopic topic = DeliveryMessagingConfiguration.topics().getFirst();
		assertThat(topic.numPartitions()).isEqualTo(3);
		assertThat(topic.replicationFactor()).isEqualTo((short) 1);
		assertThat(topic.configs()).containsEntry(
				TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(7).toMillis()));
	}

	@Test
	void mailWorkerCreatesItsOwnDeadLetterPublisherAndSafeErrorHandler() {
		new ApplicationContextRunner()
				.withUserConfiguration(DeliveryMessagingConfiguration.class, KafkaDependency.class)
				.withPropertyValues("spring.profiles.active=mail-worker")
				.run(context -> {
					assertThat(context).hasSingleBean(KafkaDeadLetterPublisher.class);
					assertThat(context).hasSingleBean(DefaultErrorHandler.class);
				});
	}

	@Configuration(proxyBeanMethods = false)
	static class KafkaDependency {
		@Bean
		KafkaTemplate<String, String> kafkaTemplate() {
			return mock(KafkaTemplate.class);
		}
	}
}
