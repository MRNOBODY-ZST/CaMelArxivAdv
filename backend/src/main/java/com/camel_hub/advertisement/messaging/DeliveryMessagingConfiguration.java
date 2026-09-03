package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile("mail-worker")
@EnableKafka
public class DeliveryMessagingConfiguration {

	static List<NewTopic> topics() {
		return List.of(KafkaTopics.topic(KafkaTopics.DELIVERY_JOBS, Duration.ofDays(7)));
	}

	@Bean
	KafkaAdmin.NewTopics campaignDeliveryTopics() {
		return new KafkaAdmin.NewTopics(topics().toArray(NewTopic[]::new));
	}

	@Bean
	KafkaDeadLetterPublisher mailWorkerDeadLetterPublisher(KafkaTemplate<String, String> kafka) {
		return new KafkaDeadLetterPublisher(kafka);
	}

	@Bean
	DefaultErrorHandler campaignDeliveryErrorHandler(KafkaDeadLetterPublisher deadLetters) {
		return new DefaultErrorHandler((record, exception) -> {
			ConsumerRecord<String, String> safe = new ConsumerRecord<>(
					record.topic(), record.partition(), record.offset(), null,
					"{\"version\":1,\"failureCategory\":\"RETRY_EXHAUSTED\"}");
			deadLetters.publish(safe, KafkaTopics.PERSONALIZATION_DLT, "RETRY_EXHAUSTED");
		}, new FixedBackOff(1_000, 4));
	}
}
