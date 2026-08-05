package com.camel_hub.advertisement.messaging;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class ArxivResultConsumer {

	private static final Logger LOGGER = LoggerFactory.getLogger(ArxivResultConsumer.class);
	private static final Duration HANDLER_TIMEOUT = Duration.ofMinutes(2);
	private final ArxivResultHandler handler;

	public ArxivResultConsumer(ArxivResultHandler handler) {
		this.handler = handler;
	}

	@RabbitListener(queues = "arxiv.results.backend", ackMode = "MANUAL")
	public void consume(Message message, Channel channel) throws IOException {
		long tag = message.getMessageProperties().getDeliveryTag();
		try {
			handler.handle(new String(message.getBody(), StandardCharsets.UTF_8)).block(HANDLER_TIMEOUT);
			channel.basicAck(tag, false);
		}
		catch (IllegalArgumentException exception) {
			LOGGER.warn("Rejected invalid arXiv result message: {}", exception.getMessage());
			channel.basicReject(tag, false);
		}
		catch (RuntimeException exception) {
			LOGGER.warn("arXiv result persistence failed and will be retried", exception);
			channel.basicNack(tag, false, true);
		}
	}
}
