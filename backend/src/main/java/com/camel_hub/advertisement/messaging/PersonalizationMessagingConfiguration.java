package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.campaign.CampaignRepository;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Duration;
import java.util.List;

@Configuration
@Profile("!mail-worker")
public class PersonalizationMessagingConfiguration {

	static List<NewTopic> topics() {
		return List.of(
				KafkaTopics.topic(KafkaTopics.PERSONALIZATION_JOBS, Duration.ofDays(7)),
				KafkaTopics.topic(KafkaTopics.PERSONALIZATION_RESULTS, Duration.ofDays(14)),
				KafkaTopics.topic(KafkaTopics.PERSONALIZATION_RETRY, Duration.ofDays(7)),
				KafkaTopics.topic(KafkaTopics.PERSONALIZATION_DLT, Duration.ofDays(30)));
	}

	@Bean
	KafkaAdmin.NewTopics personalizationKafkaTopics() {
		return new KafkaAdmin.NewTopics(topics().toArray(NewTopic[]::new));
	}

	@Bean
	@Profile("api")
	PersonalizationResultHandler personalizationResultHandler(
			CampaignRepository repository, TemplateEngine templateEngine,
			ObjectMapper objectMapper, TransactionalOperator transactions
	) {
		return new PersonalizationResultHandler(repository, templateEngine, objectMapper, transactions);
	}

	@Bean
	@Profile("api")
	PersonalizationResultConsumer personalizationResultConsumer(
			PersonalizationResultHandler handler, KafkaDeadLetterPublisher deadLetters
	) {
		return new PersonalizationResultConsumer(handler, deadLetters);
	}
}
