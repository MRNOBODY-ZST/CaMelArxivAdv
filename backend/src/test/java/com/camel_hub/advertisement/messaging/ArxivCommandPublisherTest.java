package com.camel_hub.advertisement.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivCommandPublisherTest {

	@Test
	void marksAnOutboxMessagePublishedOnlyAfterBrokerAck() {
		RabbitTemplate rabbit = mock(RabbitTemplate.class);
		OutboxRepository repository = mock(OutboxRepository.class);
		UUID messageId = UUID.randomUUID();
		var message = new OutboxRepository.OutboxMessage(
				messageId, "arxiv.jobs", "arxiv.import.metadata", "ARXIV_IMPORT_METADATA",
				1, "{\"version\":1}", 1);
		when(repository.claimBatch(20)).thenReturn(Flux.just(message));
		when(repository.markPublished(messageId)).thenReturn(Mono.empty());
		org.mockito.Mockito.doAnswer(invocation -> {
			CorrelationData correlation = invocation.getArgument(3);
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(eq("arxiv.jobs"), eq("arxiv.import.metadata"), any(), any(CorrelationData.class));

		ArxivCommandPublisher publisher = new ArxivCommandPublisher(rabbit, repository, 20);
		int published = publisher.dispatchOnce().block();

		assertThat(published).isEqualTo(1);
		verify(repository).markPublished(messageId);
	}

	@Test
	void recordsFailureAndDoesNotMarkPublishedAfterBrokerNack() {
		RabbitTemplate rabbit = mock(RabbitTemplate.class);
		OutboxRepository repository = mock(OutboxRepository.class);
		UUID messageId = UUID.randomUUID();
		var message = new OutboxRepository.OutboxMessage(
				messageId, "arxiv.jobs", "arxiv.sync.oai", "ARXIV_SYNC_OAI",
				1, "{\"version\":1}", 2);
		when(repository.claimBatch(20)).thenReturn(Flux.just(message));
		when(repository.markFailed(eq(messageId), any())).thenReturn(Mono.empty());
		org.mockito.Mockito.doAnswer(invocation -> {
			CorrelationData correlation = invocation.getArgument(3);
			correlation.getFuture().complete(new CorrelationData.Confirm(false, "unroutable"));
			return null;
		}).when(rabbit).send(eq("arxiv.jobs"), eq("arxiv.sync.oai"), any(), any(CorrelationData.class));

		int published = new ArxivCommandPublisher(rabbit, repository, 20).dispatchOnce().block();

		assertThat(published).isZero();
		verify(repository).markFailed(eq(messageId), any());
	}
}
