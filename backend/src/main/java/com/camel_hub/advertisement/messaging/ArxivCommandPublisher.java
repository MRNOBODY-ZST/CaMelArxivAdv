package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ArxivCommandPublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger(ArxivCommandPublisher.class);
	private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final OutboxRepository repository;
	private final int batchSize;

	public ArxivCommandPublisher(
			KafkaTemplate<String, String> kafkaTemplate, OutboxRepository repository, int batchSize
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.repository = repository;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${app.messaging.outbox-poll-interval-ms:1000}")
	public void scheduledDispatch() {
		dispatchOnce().subscribe(
				published -> {
					if (published > 0) LOGGER.debug("Published {} outbox records to Kafka", published);
				}, exception -> LOGGER.warn("Kafka outbox dispatch failed", exception));
	}

	public Mono<Integer> dispatchOnce() {
		return repository.claimBatch(batchSize)
				.concatMap(message -> publish(message)
						.thenReturn(1)
						.onErrorResume(exception -> repository.markFailed(
								message.id(), failureSummary(exception)).thenReturn(0)))
				.reduce(0, Integer::sum);
	}

	private Mono<Void> publish(OutboxRepository.OutboxMessage outbox) {
		return Mono.fromCallable(() -> {
			ProducerRecord<String, String> record = new ProducerRecord<>(
					outbox.topic(), outbox.id().toString(), outbox.payload());
			record.headers()
					.add("messageId", bytes(outbox.id().toString()))
					.add("messageType", bytes(outbox.type()))
					.add("contractVersion", bytes(Integer.toString(outbox.version())))
					.add("routingKey", bytes(outbox.routingKey()))
					.add("outboxAttempt", bytes(Integer.toString(outbox.attemptCount())));
			kafkaTemplate.send(record).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			return true;
		}).subscribeOn(Schedulers.boundedElastic())
				.flatMap(ignored -> repository.markPublished(outbox.id()));
	}

	private byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private String failureSummary(Throwable exception) {
		String message = exception.getMessage();
		String value = exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
		return value.replaceAll("[\\p{Cntrl}]", " ").strip();
	}
}
