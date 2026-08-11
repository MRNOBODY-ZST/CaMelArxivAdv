package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.arxiv.paper.PaperRepository;
import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionResultRepository;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryRepository;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryService;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomySnapshotLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableScheduling
@EnableRabbit
public class ArxivMessagingConfiguration {

	public static final String JOBS_EXCHANGE = "arxiv.jobs";
	public static final String RESULTS_EXCHANGE = "arxiv.results";
	public static final String RETRY_EXCHANGE = "arxiv.retry";
	public static final String DEAD_EXCHANGE = "arxiv.dead";

	static List<NewTopic> topics() {
		return List.of(
				KafkaTopics.topic(KafkaTopics.ARXIV_JOBS, Duration.ofDays(7)),
				KafkaTopics.topic(KafkaTopics.ARXIV_RESULTS, Duration.ofDays(14)),
				KafkaTopics.topic(KafkaTopics.ARXIV_RETRY, Duration.ofDays(7)),
				KafkaTopics.topic(KafkaTopics.ARXIV_DLT, Duration.ofDays(30)));
	}

	@Bean
	KafkaAdmin.NewTopics arxivKafkaTopics() {
		return new KafkaAdmin.NewTopics(topics().toArray(NewTopic[]::new));
	}

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
				BindingBuilder.bind(resultConsumer).to(results).with("worker.heartbeat"),
				BindingBuilder.bind(retryQueue).to(retry).with("arxiv.#"),
				BindingBuilder.bind(deadQueue).to(dead).with("#"));
	}

	@Bean
	@Profile("api")
	RabbitAdmin arxivRabbitAdmin(ConnectionFactory connectionFactory) {
		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		admin.setAutoStartup(true);
		return admin;
	}

	@Bean
	@Profile("api")
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	OutboxRepository outboxRepository(DatabaseClient databaseClient) {
		return new OutboxRepository(databaseClient);
	}

	@Bean
	@Profile("api")
	ArxivCommandPublisher arxivCommandPublisher(
			KafkaTemplate<String, String> kafkaTemplate, OutboxRepository repository
	) {
		return new ArxivCommandPublisher(kafkaTemplate, repository, 20);
	}

	@Bean
	@Profile("api")
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ArxivResultRepository arxivResultRepository(DatabaseClient databaseClient) {
		return new ArxivResultRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	PaperRepository paperRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new PaperRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	PaperQueryRepository paperQueryRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new PaperQueryRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	PaperQueryService paperQueryService(PaperQueryRepository repository) {
		return new PaperQueryService(repository);
	}

	@Bean
	@Profile("api")
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ArxivResultHandler arxivResultHandler(
			ArxivResultRepository repository,
			PaperRepository papers,
			TaxonomyRepository taxonomy,
			TaxonomySnapshotLoader snapshotLoader,
			ContactCrypto contactCrypto,
			ObjectMapper objectMapper,
			DatabaseClient databaseClient,
			TransactionalOperator transactions
	) {
		SourceExtractionResultRepository extractionResults = new SourceExtractionResultRepository(
				databaseClient, contactCrypto, objectMapper);
		return new ArxivResultHandler(
				repository, papers, taxonomy, snapshotLoader, extractionResults, objectMapper, transactions);
	}

	@Bean
	@Profile("api")
	ArxivResultConsumer arxivResultConsumer(ArxivResultHandler handler) {
		return new ArxivResultConsumer(handler);
	}
}
