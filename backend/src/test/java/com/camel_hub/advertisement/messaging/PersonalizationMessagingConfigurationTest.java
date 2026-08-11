package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalizationMessagingConfigurationTest {

	@Test
	void declaresVersionedPersonalizationTopicsWithExplicitPartitions() {
		assertThat(PersonalizationMessagingConfiguration.topics())
				.extracting(NewTopic::name)
				.containsExactlyInAnyOrder(
						"camel.mail.personalization.jobs.v1",
						"camel.mail.personalization.results.v1",
						"camel.mail.personalization.retry.v1",
						"camel.mail.personalization.dlt.v1");
		assertThat(PersonalizationMessagingConfiguration.topics())
				.allSatisfy(topic -> {
					assertThat(topic.numPartitions()).isEqualTo(3);
					assertThat(topic.replicationFactor()).isEqualTo((short) 1);
				});
	}
}
