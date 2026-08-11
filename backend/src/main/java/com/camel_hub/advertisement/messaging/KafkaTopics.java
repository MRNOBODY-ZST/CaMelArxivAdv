package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

public final class KafkaTopics {

	public static final String ARXIV_JOBS = "camel.arxiv.jobs.v1";
	public static final String ARXIV_RESULTS = "camel.arxiv.results.v1";
	public static final String ARXIV_RETRY = "camel.arxiv.retry.v1";
	public static final String ARXIV_DLT = "camel.arxiv.dlt.v1";
	public static final String PERSONALIZATION_JOBS = "camel.mail.personalization.jobs.v1";
	public static final String PERSONALIZATION_RESULTS = "camel.mail.personalization.results.v1";
	public static final String PERSONALIZATION_RETRY = "camel.mail.personalization.retry.v1";
	public static final String PERSONALIZATION_DLT = "camel.mail.personalization.dlt.v1";

	private KafkaTopics() {
	}

	static NewTopic topic(String name, Duration retention) {
		return TopicBuilder.name(name)
				.partitions(3)
				.replicas(1)
				.config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
				.config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
				.build();
	}
}
