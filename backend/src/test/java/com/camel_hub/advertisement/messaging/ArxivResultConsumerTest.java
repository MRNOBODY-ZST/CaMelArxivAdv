package com.camel_hub.advertisement.messaging;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivResultConsumerTest {

	@Test
	void rejectsPermanentPersistenceViolationsWithoutRequeueing() throws Exception {
		ArxivResultHandler handler = mock(ArxivResultHandler.class);
		when(handler.handle("{}"))
				.thenReturn(Mono.error(new DataIntegrityViolationException("invalid width")));
		Channel channel = mock(Channel.class);
		MessageProperties properties = new MessageProperties();
		properties.setDeliveryTag(42L);

		new ArxivResultConsumer(handler).consume(new Message("{}".getBytes(), properties), channel);

		verify(channel).basicReject(42L, false);
	}
}
