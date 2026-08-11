package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArxivMessagingConfigurationTest {

	@Test
	void declaresVersionedArxivTopicsWithExplicitPartitions() {
		assertThat(ArxivMessagingConfiguration.topics())
				.extracting(NewTopic::name)
				.containsExactlyInAnyOrder(
						"camel.arxiv.jobs.v1", "camel.arxiv.results.v1",
						"camel.arxiv.retry.v1", "camel.arxiv.dlt.v1");
		assertThat(ArxivMessagingConfiguration.topics())
				.allSatisfy(topic -> {
					assertThat(topic.numPartitions()).isEqualTo(3);
					assertThat(topic.replicationFactor()).isEqualTo((short) 1);
				});
	}
}
