package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class KafkaDeadLetterPublisher {

	private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
	private final KafkaTemplate<String, String> kafka;

	public KafkaDeadLetterPublisher(KafkaTemplate<String, String> kafka) {
		this.kafka = kafka;
	}

	public void publish(ConsumerRecord<String, String> source, String targetTopic, String category) {
		ProducerRecord<String, String> target = new ProducerRecord<>(targetTopic, source.key(), source.value());
		target.headers()
				.add("originalTopic", bytes(source.topic()))
				.add("originalPartition", bytes(Integer.toString(source.partition())))
				.add("originalOffset", bytes(Long.toString(source.offset())))
				.add("failureCategory", bytes(safeCategory(category)));
		try {
			kafka.send(target).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Kafka dead-letter publication was interrupted", exception);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Kafka dead-letter publication failed", exception);
		}
	}

	private String safeCategory(String category) {
		String value = category == null ? "UNCLASSIFIED" : category.strip();
		return value.matches("[A-Z0-9_]{1,80}") ? value : "UNCLASSIFIED";
	}

	private byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
