package com.camel_hub.advertisement.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ArxivMessagingConfiguration {

	public static final String JOBS_EXCHANGE = "arxiv.jobs";
	public static final String RESULTS_EXCHANGE = "arxiv.results";
	public static final String RETRY_EXCHANGE = "arxiv.retry";
	public static final String DEAD_EXCHANGE = "arxiv.dead";

	@Bean
	Declarables arxivTopology() {
		TopicExchange jobs = new TopicExchange(JOBS_EXCHANGE, true, false);
		TopicExchange results = new TopicExchange(RESULTS_EXCHANGE, true, false);
		TopicExchange retry = new TopicExchange(RETRY_EXCHANGE, true, false);
		TopicExchange dead = new TopicExchange(DEAD_EXCHANGE, true, false);
		Queue worker = QueueBuilder.durable("arxiv.jobs.worker")
				.deadLetterExchange(DEAD_EXCHANGE).build();
		Queue resultConsumer = QueueBuilder.durable("arxiv.results.backend")
				.deadLetterExchange(DEAD_EXCHANGE).build();
		Queue retryQueue = QueueBuilder.durable("arxiv.jobs.retry.30s")
				.ttl(30_000).deadLetterExchange(JOBS_EXCHANGE).build();
		Queue deadQueue = QueueBuilder.durable("arxiv.dead.archive").build();
		return new Declarables(
				jobs, results, retry, dead, worker, resultConsumer, retryQueue, deadQueue,
				BindingBuilder.bind(worker).to(jobs).with("arxiv.#"),
				BindingBuilder.bind(resultConsumer).to(results).with("arxiv.#"),
				BindingBuilder.bind(retryQueue).to(retry).with("arxiv.#"),
				BindingBuilder.bind(deadQueue).to(dead).with("#"));
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	OutboxRepository outboxRepository(DatabaseClient databaseClient) {
		return new OutboxRepository(databaseClient);
	}

	@Bean
	@ConditionalOnBean({RabbitTemplate.class, OutboxRepository.class})
	ArxivCommandPublisher arxivCommandPublisher(RabbitTemplate rabbitTemplate, OutboxRepository repository) {
		return new ArxivCommandPublisher(rabbitTemplate, repository, 20);
	}
}
