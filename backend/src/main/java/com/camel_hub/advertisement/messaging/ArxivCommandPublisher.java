package com.camel_hub.advertisement.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ArxivCommandPublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger(ArxivCommandPublisher.class);
	private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);
	private final RabbitTemplate rabbitTemplate;
	private final OutboxRepository repository;
	private final int batchSize;

	public ArxivCommandPublisher(RabbitTemplate rabbitTemplate, OutboxRepository repository, int batchSize) {
		this.rabbitTemplate = rabbitTemplate;
		this.repository = repository;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${app.messaging.outbox-poll-interval-ms:1000}")
	public void scheduledDispatch() {
		dispatchOnce().subscribe(
				published -> {
					if (published > 0) {
						LOGGER.debug("Published {} arXiv outbox messages", published);
					}
				}, exception -> LOGGER.warn("arXiv outbox dispatch failed", exception));
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
			CorrelationData correlation = new CorrelationData(outbox.id().toString());
			var message = MessageBuilder
					.withBody(outbox.payload().getBytes(StandardCharsets.UTF_8))
					.setContentType(MessageProperties.CONTENT_TYPE_JSON)
					.setContentEncoding(StandardCharsets.UTF_8.name())
					.setMessageId(outbox.id().toString())
					.setType(outbox.type())
					.setHeader("contractVersion", outbox.version())
					.setHeader("outboxAttempt", outbox.attemptCount())
					.build();
			rabbitTemplate.send(outbox.exchange(), outbox.routingKey(), message, correlation);
			CorrelationData.Confirm confirm = correlation.getFuture()
					.get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (!confirm.ack()) {
				throw new IllegalStateException("Broker rejected message: " + safeReason(confirm.reason()));
			}
			return true;
		}).subscribeOn(Schedulers.boundedElastic())
				.flatMap(ignored -> repository.markPublished(outbox.id()));
	}

	private String failureSummary(Throwable exception) {
		String message = exception.getMessage();
		return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	private String safeReason(String reason) {
		return reason == null ? "unspecified" : reason.replaceAll("[\\p{Cntrl}]", " ").strip();
	}
}
