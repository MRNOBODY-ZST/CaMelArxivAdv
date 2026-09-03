package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.messaging.KafkaDeadLetterPublisher;
import com.camel_hub.advertisement.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Privacy-minimal wakeup listener. Database state remains the source of truth. */
public final class CampaignDeliveryListener {
	static final String GROUP_ID = "camel-mail-delivery-v1";
	private static final String INVALID_ENVELOPE =
			"{\"version\":1,\"failureCategory\":\"INVALID_CONTRACT\"}";
	private static final Set<String> FIELDS = Set.of(
			"version", "messageId", "campaignId", "action", "traceId", "createdAt");

	private final ObjectMapper objectMapper;
	private final DeliveryPump pump;
	private final KafkaDeadLetterPublisher deadLetters;

	public CampaignDeliveryListener(
			ObjectMapper objectMapper, CampaignDeliveryExecutor executor,
			KafkaDeadLetterPublisher deadLetters
	) {
		this(objectMapper, executor::pumpOnce, deadLetters);
	}

	CampaignDeliveryListener(
			ObjectMapper objectMapper, DeliveryPump pump,
			KafkaDeadLetterPublisher deadLetters
	) {
		this.objectMapper = objectMapper;
		this.pump = pump;
		this.deadLetters = deadLetters;
	}

	@KafkaListener(topics = KafkaTopics.DELIVERY_JOBS, groupId = GROUP_ID)
	public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
		if (!valid(record.value())) {
			ConsumerRecord<String, String> safe = new ConsumerRecord<>(
					record.topic(), record.partition(), record.offset(), null, INVALID_ENVELOPE);
			deadLetters.publish(safe, KafkaTopics.PERSONALIZATION_DLT, "INVALID_CONTRACT");
			acknowledgment.acknowledge();
			return;
		}
		// Transport timeouts bound network operations. Do not impose a shorter outer
		// timeout that would cancel a claim before its durable SMTP settlement.
		CampaignDeliveryExecutor.PumpResult result = pump.pump().block();
		if (result == null) throw new IllegalStateException("Delivery pump did not settle");
		acknowledgment.acknowledge();
	}

	private boolean valid(String payload) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			if (root == null || !root.isObject()) return false;
			Set<String> actual = new HashSet<>();
			root.fieldNames().forEachRemaining(actual::add);
			if (!actual.equals(FIELDS) || root.path("version").asInt(-1) != 1) return false;
			UUID.fromString(requiredText(root, "messageId"));
			UUID.fromString(requiredText(root, "campaignId"));
			String action = requiredText(root, "action");
			if (!Set.of("START", "SCHEDULE").contains(action)) return false;
			String trace = requiredText(root, "traceId");
			if (!trace.matches("[A-Za-z0-9_-]{1,64}")) return false;
			Instant.parse(requiredText(root, "createdAt"));
			return true;
		}
		catch (RuntimeException | java.io.IOException ignored) {
			return false;
		}
	}

	private String requiredText(JsonNode root, String name) {
		JsonNode value = root.get(name);
		if (value == null || !value.isTextual() || value.textValue().isBlank()) {
			throw new IllegalArgumentException("Missing delivery envelope field");
		}
		return value.textValue();
	}

	@FunctionalInterface
	interface DeliveryPump {
		Mono<CampaignDeliveryExecutor.PumpResult> pump();
	}
}
