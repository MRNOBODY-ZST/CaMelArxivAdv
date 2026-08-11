package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivResultConsumerTest {

	private final ArxivResultHandler handler = mock(ArxivResultHandler.class);
	private final KafkaDeadLetterPublisher deadLetters = mock(KafkaDeadLetterPublisher.class);
	private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
	private final ConsumerRecord<String, String> record = new ConsumerRecord<>(
			KafkaTopics.ARXIV_RESULTS, 1, 42, "message-id", "{}");

	@Test
	void acknowledgesOnlyAfterTheResultHandlerSucceeds() {
		when(handler.handle("{}")).thenReturn(Mono.empty());

		new ArxivResultConsumer(handler, deadLetters).consume(record, acknowledgment);

		verify(acknowledgment).acknowledge();
		verify(deadLetters, never()).publish(record, KafkaTopics.ARXIV_DLT, "PERMANENT_FAILURE");
	}

	@Test
	void deadLettersPermanentPersistenceViolationsBeforeAcknowledging() {
		when(handler.handle("{}"))
				.thenReturn(Mono.error(new DataIntegrityViolationException("invalid width")));

		new ArxivResultConsumer(handler, deadLetters).consume(record, acknowledgment);

		verify(deadLetters).publish(record, KafkaTopics.ARXIV_DLT, "PERSISTENCE_CONSTRAINT");
		verify(acknowledgment).acknowledge();
	}

	@Test
	void leavesTransientFailuresUncommittedForTheContainerErrorHandler() {
		when(handler.handle("{}")).thenReturn(Mono.error(new IllegalStateException("database unavailable")));

		assertThatThrownBy(() -> new ArxivResultConsumer(handler, deadLetters)
				.consume(record, acknowledgment)).isInstanceOf(IllegalStateException.class);

		verify(acknowledgment, never()).acknowledge();
		verify(deadLetters, never()).publish(record, KafkaTopics.ARXIV_DLT, "PERMANENT_FAILURE");
	}
}
