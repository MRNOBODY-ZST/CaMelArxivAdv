package com.camel_hub.advertisement.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;

import static org.assertj.core.api.Assertions.assertThat;

class ArxivMessagingConfigurationTest {

	@Test
	void routesJobResultsAndWorkerHeartbeatsToTheBackendConsumer() {
		var bindings = new ArxivMessagingConfiguration().arxivTopology()
				.getDeclarablesByType(Binding.class);

		assertThat(bindings)
				.anySatisfy(binding -> {
					assertThat(binding.getDestination()).isEqualTo("arxiv.results.backend");
					assertThat(binding.getRoutingKey()).isEqualTo("arxiv.#");
				})
				.anySatisfy(binding -> {
					assertThat(binding.getDestination()).isEqualTo("arxiv.results.backend");
					assertThat(binding.getRoutingKey()).isEqualTo("worker.heartbeat");
				});
	}
}
