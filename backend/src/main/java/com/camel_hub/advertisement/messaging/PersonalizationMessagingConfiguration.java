package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.campaign.CampaignRepository;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Duration;
import java.util.List;

@Configuration
public class PersonalizationMessagingConfiguration {

	public static final String JOBS_EXCHANGE = "mail.jobs";
	public static final String RESULTS_EXCHANGE = "mail.results";
	public static final String RETRY_EXCHANGE = "mail.retry";
	public static final String DEAD_EXCHANGE = "mail.dead";

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
	Declarables personalizationTopology() {
		TopicExchange jobs = new TopicExchange(JOBS_EXCHANGE, true, false);
		TopicExchange results = new TopicExchange(RESULTS_EXCHANGE, true, false);
		TopicExchange retry = new TopicExchange(RETRY_EXCHANGE, true, false);
		TopicExchange dead = new TopicExchange(DEAD_EXCHANGE, true, false);
		Queue worker = QueueBuilder.durable("mail.personalization.worker")
				.deadLetterExchange(DEAD_EXCHANGE).build();
		Queue backend = QueueBuilder.durable("mail.personalization.results.backend")
				.deadLetterExchange(DEAD_EXCHANGE).build();
		Queue retryQueue = QueueBuilder.durable("mail.personalization.retry.30s")
				.ttl(30_000).deadLetterExchange(JOBS_EXCHANGE).build();
		Queue archive = QueueBuilder.durable("mail.dead.archive").build();
		return new Declarables(
				jobs, results, retry, dead, worker, backend, retryQueue, archive,
				BindingBuilder.bind(worker).to(jobs).with("mail.personalization.generate"),
				BindingBuilder.bind(backend).to(results).with("mail.personalization.result"),
				BindingBuilder.bind(retryQueue).to(retry).with("mail.#"),
				BindingBuilder.bind(archive).to(dead).with("#"));
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
	PersonalizationResultConsumer personalizationResultConsumer(PersonalizationResultHandler handler) {
		return new PersonalizationResultConsumer(handler);
	}
}
