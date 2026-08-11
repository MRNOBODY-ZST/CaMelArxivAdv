package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalizationResultConsumerTest {

	@Test
	void deadLettersInvalidGeneratedDraftsBeforeAcknowledging() {
		PersonalizationResultHandler handler = mock(PersonalizationResultHandler.class);
		KafkaDeadLetterPublisher deadLetters = mock(KafkaDeadLetterPublisher.class);
		Acknowledgment acknowledgment = mock(Acknowledgment.class);
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
				KafkaTopics.PERSONALIZATION_RESULTS, 0, 7, "message-id", "{}");
		when(handler.handle("{}")).thenReturn(Mono.error(new IllegalArgumentException("invalid schema")));

		new PersonalizationResultConsumer(handler, deadLetters).consume(record, acknowledgment);

		verify(deadLetters).publish(
				record, KafkaTopics.PERSONALIZATION_DLT, "INVALID_CONTRACT");
		verify(acknowledgment).acknowledge();
	}
}
