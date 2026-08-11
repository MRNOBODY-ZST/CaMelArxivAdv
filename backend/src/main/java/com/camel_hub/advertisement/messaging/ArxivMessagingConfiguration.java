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
public class ArxivMessagingConfiguration {

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
	ArxivResultConsumer arxivResultConsumer(
			ArxivResultHandler handler, KafkaDeadLetterPublisher deadLetters
	) {
		return new ArxivResultConsumer(handler, deadLetters);
	}
}
