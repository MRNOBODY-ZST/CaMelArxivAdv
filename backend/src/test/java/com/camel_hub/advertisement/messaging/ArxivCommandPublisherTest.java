package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivCommandPublisherTest {

	@Test
	void marksAnOutboxMessagePublishedOnlyAfterKafkaAcknowledgesTheRecord() {
		KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
		OutboxRepository repository = mock(OutboxRepository.class);
		UUID messageId = UUID.randomUUID();
		var message = new OutboxRepository.OutboxMessage(
				messageId, KafkaTopics.ARXIV_JOBS, "arxiv.import.metadata", "ARXIV_IMPORT_METADATA",
				1, "{\"version\":1}", 1);
		when(repository.claimBatch(20)).thenReturn(Flux.just(message));
		when(repository.markPublished(messageId)).thenReturn(Mono.empty());
		when(kafka.send(any(ProducerRecord.class))).thenReturn(
				CompletableFuture.completedFuture(mock(SendResult.class)));

		int published = new ArxivCommandPublisher(kafka, repository, 20).dispatchOnce().block();

		assertThat(published).isEqualTo(1);
		verify(repository).markPublished(messageId);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(kafka).send(record.capture());
		var header = record.getValue().headers().lastHeader("contractVersion");
		assertThat(record.getValue().topic()).isEqualTo(KafkaTopics.ARXIV_JOBS);
		assertThat(record.getValue().key()).isEqualTo(messageId.toString());
		assertThat(record.getValue().value()).isEqualTo("{\"version\":1}");
		assertThat(header).isNotNull();
		assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("1");
	}

	@Test
	void recordsFailureAndDoesNotMarkPublishedAfterKafkaFailure() {
		KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
		OutboxRepository repository = mock(OutboxRepository.class);
		UUID messageId = UUID.randomUUID();
		var message = new OutboxRepository.OutboxMessage(
				messageId, KafkaTopics.ARXIV_JOBS, "arxiv.sync.oai", "ARXIV_SYNC_OAI",
				1, "{\"version\":1}", 2);
		when(repository.claimBatch(20)).thenReturn(Flux.just(message));
		when(repository.markFailed(eq(messageId), any())).thenReturn(Mono.empty());
		when(kafka.send(any(ProducerRecord.class))).thenReturn(
				CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

		int published = new ArxivCommandPublisher(kafka, repository, 20).dispatchOnce().block();

		assertThat(published).isZero();
		verify(repository).markFailed(eq(messageId), any());
		verify(repository, never()).markPublished(messageId);
	}
}
