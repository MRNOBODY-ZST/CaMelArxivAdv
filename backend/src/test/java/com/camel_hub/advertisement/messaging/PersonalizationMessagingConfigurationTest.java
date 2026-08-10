package com.camel_hub.advertisement.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalizationMessagingConfigurationTest {

	@Test
	void declaresDurableWorkerResultAndDeadLetterRoutes() {
		var topology = new PersonalizationMessagingConfiguration().personalizationTopology();

		assertThat(topology.getDeclarablesByType(Queue.class))
				.extracting(Queue::getName)
				.contains("mail.personalization.worker", "mail.personalization.results.backend", "mail.dead.archive");
		assertThat(topology.getDeclarablesByType(Binding.class))
				.anySatisfy(binding -> {
					assertThat(binding.getDestination()).isEqualTo("mail.personalization.worker");
					assertThat(binding.getRoutingKey()).isEqualTo("mail.personalization.generate");
				})
				.anySatisfy(binding -> {
					assertThat(binding.getDestination()).isEqualTo("mail.personalization.results.backend");
					assertThat(binding.getRoutingKey()).isEqualTo("mail.personalization.result");
				});
	}
}
