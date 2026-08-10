package com.camel_hub.advertisement.messaging;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class PersonalizationResultConsumer {

	private static final Logger LOGGER = LoggerFactory.getLogger(PersonalizationResultConsumer.class);
	private static final Duration HANDLER_TIMEOUT = Duration.ofSeconds(30);
	private final PersonalizationResultHandler handler;

	public PersonalizationResultConsumer(PersonalizationResultHandler handler) {
		this.handler = handler;
	}

	@RabbitListener(queues = "mail.personalization.results.backend", ackMode = "MANUAL")
	public void consume(Message message, Channel channel) throws IOException {
		long tag = message.getMessageProperties().getDeliveryTag();
		try {
			handler.handle(new String(message.getBody(), StandardCharsets.UTF_8)).block(HANDLER_TIMEOUT);
			channel.basicAck(tag, false);
		}
		catch (IllegalArgumentException | DataIntegrityViolationException exception) {
			LOGGER.warn("Rejected invalid personalization result: {}", exception.getMessage());
			channel.basicReject(tag, false);
		}
		catch (RuntimeException exception) {
			LOGGER.warn("Personalization result persistence failed and will be retried", exception);
			channel.basicNack(tag, false, true);
		}
	}
}
