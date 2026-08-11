package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;

public final class PersonalizationResultConsumer {

	private static final Logger LOGGER = LoggerFactory.getLogger(PersonalizationResultConsumer.class);
	private static final Duration HANDLER_TIMEOUT = Duration.ofSeconds(30);
	private final PersonalizationResultHandler handler;
	private final KafkaDeadLetterPublisher deadLetters;

	public PersonalizationResultConsumer(
			PersonalizationResultHandler handler, KafkaDeadLetterPublisher deadLetters
	) {
		this.handler = handler;
		this.deadLetters = deadLetters;
	}

	@KafkaListener(
			topics = KafkaTopics.PERSONALIZATION_RESULTS,
			groupId = "camel-backend-personalization-results-v1")
	public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
		try {
			handler.handle(record.value()).block(HANDLER_TIMEOUT);
			acknowledgment.acknowledge();
		}
		catch (IllegalArgumentException exception) {
			LOGGER.warn("Dead-lettering invalid personalization result at {}-{}@{}",
					record.topic(), record.partition(), record.offset());
			deadLetters.publish(record, KafkaTopics.PERSONALIZATION_DLT, "INVALID_CONTRACT");
			acknowledgment.acknowledge();
		}
		catch (DataIntegrityViolationException exception) {
			LOGGER.warn("Dead-lettering personalization persistence violation at {}-{}@{}",
					record.topic(), record.partition(), record.offset());
			deadLetters.publish(record, KafkaTopics.PERSONALIZATION_DLT, "PERSISTENCE_CONSTRAINT");
			acknowledgment.acknowledge();
		}
	}
}
