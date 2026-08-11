package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;

public class ArxivResultConsumer {

	private static final Logger LOGGER = LoggerFactory.getLogger(ArxivResultConsumer.class);
	private static final Duration HANDLER_TIMEOUT = Duration.ofMinutes(2);
	private final ArxivResultHandler handler;
	private final KafkaDeadLetterPublisher deadLetters;

	public ArxivResultConsumer(ArxivResultHandler handler, KafkaDeadLetterPublisher deadLetters) {
		this.handler = handler;
		this.deadLetters = deadLetters;
	}

	@KafkaListener(topics = KafkaTopics.ARXIV_RESULTS, groupId = "camel-backend-arxiv-results-v1")
	public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
		try {
			handler.handle(record.value()).block(HANDLER_TIMEOUT);
			acknowledgment.acknowledge();
		}
		catch (IllegalArgumentException exception) {
			LOGGER.warn("Dead-lettering invalid arXiv result at {}-{}@{}",
					record.topic(), record.partition(), record.offset());
			deadLetters.publish(record, KafkaTopics.ARXIV_DLT, "INVALID_CONTRACT");
			acknowledgment.acknowledge();
		}
		catch (DataIntegrityViolationException exception) {
			LOGGER.warn("Dead-lettering arXiv result with invalid persistence constraints at {}-{}@{}",
					record.topic(), record.partition(), record.offset());
			deadLetters.publish(record, KafkaTopics.ARXIV_DLT, "PERSISTENCE_CONSTRAINT");
			acknowledgment.acknowledge();
		}
	}
}
