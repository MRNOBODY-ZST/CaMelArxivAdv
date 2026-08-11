package com.camel_hub.advertisement.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@Profile("api")
public class KafkaConsumerConfiguration {

	@Bean
	KafkaDeadLetterPublisher kafkaDeadLetterPublisher(KafkaTemplate<String, String> kafka) {
		return new KafkaDeadLetterPublisher(kafka);
	}

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaDeadLetterPublisher deadLetters) {
		return new DefaultErrorHandler(
				(record, exception) -> deadLetters.publish(stringRecord(record), dltFor(record.topic()),
						"RETRY_EXHAUSTED"),
				new FixedBackOff(1_000, 4));
	}

	private String dltFor(String sourceTopic) {
		return KafkaTopics.PERSONALIZATION_RESULTS.equals(sourceTopic)
				? KafkaTopics.PERSONALIZATION_DLT : KafkaTopics.ARXIV_DLT;
	}

	private ConsumerRecord<String, String> stringRecord(ConsumerRecord<?, ?> source) {
		return new ConsumerRecord<>(source.topic(), source.partition(), source.offset(),
				source.key() == null ? null : source.key().toString(),
				source.value() == null ? null : source.value().toString());
	}
}
