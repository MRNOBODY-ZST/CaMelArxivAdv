package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.messaging.KafkaDeadLetterPublisher;
import com.camel_hub.advertisement.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.annotation.KafkaListener;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignDeliveryListenerTest {

	private static final String VALID = """
			{"version":1,
			 "messageId":"10000000-0000-0000-0000-000000000001",
			 "campaignId":"20000000-0000-0000-0000-000000000002",
			 "action":"START",
			 "traceId":"deliverytrace1",
			 "createdAt":"2030-04-05T10:15:30Z"}
			""";
	private static final String VALID_SAFETY = """
			{"version":1,
			 "messageId":"10000000-0000-0000-0000-000000000001",
			 "safetyRunId":"30000000-0000-0000-0000-000000000003",
			 "action":"SAFETY_START",
			 "traceId":"safetytrace1",
			 "createdAt":"2030-04-05T10:15:30Z"}
			""";

	@Test
	void acknowledgesOnlyAfterTheDatabasePumpSettlesOrFindsNoWork() {
		AtomicInteger pumps = new AtomicInteger();
		AtomicInteger acknowledgments = new AtomicInteger();
		CapturingDeadLetters deadLetters = new CapturingDeadLetters();
		CampaignDeliveryListener listener = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(), () -> {
					pumps.incrementAndGet();
					return Mono.just(CampaignDeliveryExecutor.PumpResult.NO_WORK);
				}, deadLetters);

		listener.consume(record(VALID), acknowledgments::incrementAndGet);
		listener.consume(record(VALID), acknowledgments::incrementAndGet);

		assertThat(pumps).hasValue(2);
		assertThat(acknowledgments).hasValue(2);
		assertThat(deadLetters.published).hasValue(0);
	}

	@Test
	void acceptsTheExactPrivacyMinimalSafetyDiscriminator() {
		AtomicInteger pumps = new AtomicInteger();
		AtomicInteger acknowledgments = new AtomicInteger();
		CapturingDeadLetters deadLetters = new CapturingDeadLetters();
		CampaignDeliveryListener listener = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(), () -> {
					pumps.incrementAndGet();
					return Mono.just(CampaignDeliveryExecutor.PumpResult.NO_WORK);
				}, deadLetters);

		listener.consume(record(VALID_SAFETY), acknowledgments::incrementAndGet);

		assertThat(pumps).hasValue(1);
		assertThat(acknowledgments).hasValue(1);
		assertThat(deadLetters.published).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"version\":1,\"messageId\":\"10000000-0000-0000-0000-000000000001\",\"campaignId\":\"20000000-0000-0000-0000-000000000002\",\"safetyRunId\":\"30000000-0000-0000-0000-000000000003\",\"action\":\"SAFETY_START\",\"traceId\":\"safetytrace1\",\"createdAt\":\"2030-04-05T10:15:30Z\"}",
			"{\"version\":1,\"messageId\":\"10000000-0000-0000-0000-000000000001\",\"safetyRunId\":\"30000000-0000-0000-0000-000000000003\",\"action\":\"START\",\"traceId\":\"safetytrace1\",\"createdAt\":\"2030-04-05T10:15:30Z\"}",
			"{\"version\":1,\"messageId\":\"10000000-0000-0000-0000-000000000001\",\"safetyRunId\":\"30000000-0000-0000-0000-000000000003\",\"action\":\"SAFETY_START\",\"traceId\":\"safetytrace1\",\"createdAt\":\"2030-04-05T10:15:30Z\",\"recipient\":\"private@example.test\"}"
	})
	void rejectsMixedOrExpandedSafetyWakeupShapes(String payload) {
		AtomicInteger pumps = new AtomicInteger();
		AtomicInteger acknowledgments = new AtomicInteger();
		CapturingDeadLetters deadLetters = new CapturingDeadLetters();
		CampaignDeliveryListener listener = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(), () -> {
					pumps.incrementAndGet();
					return Mono.just(CampaignDeliveryExecutor.PumpResult.NO_WORK);
				}, deadLetters);

		listener.consume(record(payload), acknowledgments::incrementAndGet);

		assertThat(pumps).hasValue(0);
		assertThat(acknowledgments).hasValue(1);
		assertThat(deadLetters.published).hasValue(1);
		assertThat(deadLetters.source.value()).isEqualTo(
				"{\"version\":1,\"failureCategory\":\"INVALID_CONTRACT\"}");
	}

	@Test
	void invalidPayloadIsRebuiltAsFixedRedactedEnvelopeBeforeDltAndAck() {
		String malicious = """
				{"version":1,"messageId":"10000000-0000-0000-0000-000000000001",
				 "campaignId":"20000000-0000-0000-0000-000000000002",
				 "action":"START","traceId":"deliverytrace1","createdAt":"2030-04-05T10:15:30Z",
				 "email":"private@research.test","html":"secret body","token":"opaque-secret"}
				""";
		AtomicInteger acknowledgments = new AtomicInteger();
		CapturingDeadLetters deadLetters = new CapturingDeadLetters();
		CampaignDeliveryListener listener = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(),
				() -> Mono.error(new AssertionError("invalid payload must not reach pump")), deadLetters);

		listener.consume(new ConsumerRecord<>(KafkaTopics.DELIVERY_JOBS, 2, 91,
				"private@research.test", malicious), acknowledgments::incrementAndGet);

		assertThat(acknowledgments).hasValue(1);
		assertThat(deadLetters.published).hasValue(1);
		assertThat(deadLetters.source.key()).isNull();
		assertThat(deadLetters.source.value()).isEqualTo(
				"{\"version\":1,\"failureCategory\":\"INVALID_CONTRACT\"}");
		assertThat(deadLetters.source.value()).doesNotContain(
				"private@research.test", "secret body", "opaque-secret", malicious);
		assertThat(deadLetters.targetTopic).isEqualTo(KafkaTopics.PERSONALIZATION_DLT);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"version\":2,\"messageId\":\"10000000-0000-0000-0000-000000000001\",\"campaignId\":\"20000000-0000-0000-0000-000000000002\",\"action\":\"START\",\"traceId\":\"deliverytrace1\",\"createdAt\":\"2030-04-05T10:15:30Z\"}",
			"{\"version\":\"1\",\"messageId\":\"10000000-0000-0000-0000-000000000001\",\"campaignId\":\"20000000-0000-0000-0000-000000000002\",\"action\":\"START\",\"traceId\":\"deliverytrace1\",\"createdAt\":\"2030-04-05T10:15:30Z\"}",
			"{\"version\":1,\"messageId\":\"10000000-0000-0000-0000-000000000001\",\"campaignId\":\"20000000-0000-0000-0000-000000000002\",\"action\":\"SEND\",\"traceId\":\"deliverytrace1\",\"createdAt\":\"2030-04-05T10:15:30Z\"}",
			"{\"version\":1,\"messageId\":\"10000000-0000-0000-0000-000000000001\",\"campaignId\":\"20000000-0000-0000-0000-000000000002\",\"action\":\"START\",\"traceId\":\"deliverytrace1\"}"
	})
	void rejectsWrongVersionActionOrMissingRequiredField(String invalidPayload) {
		AtomicInteger pumps = new AtomicInteger();
		AtomicInteger acknowledgments = new AtomicInteger();
		CapturingDeadLetters deadLetters = new CapturingDeadLetters();
		CampaignDeliveryListener listener = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(), () -> {
					pumps.incrementAndGet();
					return Mono.just(CampaignDeliveryExecutor.PumpResult.NO_WORK);
				}, deadLetters);

		listener.consume(record(invalidPayload), acknowledgments::incrementAndGet);

		assertThat(pumps).hasValue(0);
		assertThat(acknowledgments).hasValue(1);
		assertThat(deadLetters.published).hasValue(1);
	}

	@ParameterizedTest(name = "strict JSON/UUID contract rejects {0}")
	@MethodSource("nonCanonicalEnvelopes")
	void rejectsDuplicateFieldsTrailingRootsAndNonCanonicalUuidStrings(
			String label, String invalidPayload
	) {
		AtomicInteger pumps = new AtomicInteger();
		AtomicInteger acknowledgments = new AtomicInteger();
		CapturingDeadLetters deadLetters = new CapturingDeadLetters();
		CampaignDeliveryListener listener = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(), () -> {
					pumps.incrementAndGet();
					return Mono.just(CampaignDeliveryExecutor.PumpResult.NO_WORK);
				}, deadLetters);

		listener.consume(record(invalidPayload), acknowledgments::incrementAndGet);

		assertThat(pumps).as(label).hasValue(0);
		assertThat(acknowledgments).as(label).hasValue(1);
		assertThat(deadLetters.published).as(label).hasValue(1);
		assertThat(deadLetters.source.key()).isNull();
		assertThat(deadLetters.source.value())
				.isEqualTo("{\"version\":1,\"failureCategory\":\"INVALID_CONTRACT\"}");
	}

	private static Stream<Arguments> nonCanonicalEnvelopes() {
		return Stream.of(
				Arguments.of("duplicate-field", """
						{"version":1,
						 "messageId":"10000000-0000-0000-0000-000000000001",
						 "messageId":"10000000-0000-0000-0000-000000000001",
						 "campaignId":"20000000-0000-0000-0000-000000000002",
						 "action":"START","traceId":"deliverytrace1",
						 "createdAt":"2030-04-05T10:15:30Z"}
						"""),
				Arguments.of("trailing-root", VALID + "{}"),
				Arguments.of("non-canonical-message-uuid", """
						{"version":1,"messageId":"1-1-1-1-1",
						 "safetyRunId":"30000000-0000-0000-0000-000000000003",
						 "action":"SAFETY_START","traceId":"safetytrace1",
						 "createdAt":"2030-04-05T10:15:30Z"}
						"""),
				Arguments.of("non-canonical-aggregate-uuid", """
						{"version":1,"messageId":"10000000-0000-0000-0000-000000000001",
						 "campaignId":"2-2-2-2-2","action":"START",
						 "traceId":"deliverytrace1","createdAt":"2030-04-05T10:15:30Z"}
						"""));
	}

	@Test
	void listenerContractPinsDeliveryTopicGroupAndManualImmediateAckConfiguration() throws Exception {
		KafkaListener annotation = CampaignDeliveryListener.class
				.getMethod("consume", ConsumerRecord.class, org.springframework.kafka.support.Acknowledgment.class)
				.getAnnotation(KafkaListener.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.topics()).containsExactly(KafkaTopics.DELIVERY_JOBS);
		assertThat(annotation.groupId()).isEqualTo("camel-mail-delivery-v1");

		try (var stream = getClass().getResourceAsStream("/application.yaml")) {
			assertThat(stream).isNotNull();
			String configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(configuration).contains("enable-auto-commit: false", "ack-mode: manual_immediate");
		}
	}

	@Test
	void doesNotAcknowledgeWhenPumpOrDltHasNotDurablyCompleted() {
		AtomicInteger acknowledgments = new AtomicInteger();
		CampaignDeliveryListener failedPump = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(),
				() -> Mono.error(new IllegalStateException("database unavailable")), new CapturingDeadLetters());

		assertThatThrownBy(() -> failedPump.consume(record(VALID), acknowledgments::incrementAndGet))
				.isInstanceOf(IllegalStateException.class);
		assertThat(acknowledgments).hasValue(0);

		CapturingDeadLetters failedDlt = new CapturingDeadLetters();
		failedDlt.fail = true;
		CampaignDeliveryListener invalid = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(),
				() -> Mono.just(CampaignDeliveryExecutor.PumpResult.NO_WORK), failedDlt);
		assertThatThrownBy(() -> invalid.consume(record("{}"), acknowledgments::incrementAndGet))
				.isInstanceOf(IllegalStateException.class);
		assertThat(acknowledgments).hasValue(0);
	}

	@Test
	void waitsForDurableSettlementWithoutCancelingAnAlreadyClaimedPumpAtAnArbitraryTimeout() {
		AtomicInteger indefiniteWaits = new AtomicInteger();
		AtomicInteger timedWaits = new AtomicInteger();
		AtomicInteger acknowledgments = new AtomicInteger();
		Mono<CampaignDeliveryExecutor.PumpResult> settlement = new Mono<>() {
			@Override
			public CampaignDeliveryExecutor.PumpResult block() {
				indefiniteWaits.incrementAndGet();
				return CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED;
			}

			@Override
			public CampaignDeliveryExecutor.PumpResult block(Duration timeout) {
				timedWaits.incrementAndGet();
				throw new IllegalStateException("timed wait would cancel a claimed delivery");
			}

			@Override
			public void subscribe(CoreSubscriber<? super CampaignDeliveryExecutor.PumpResult> subscriber) {
				Mono.just(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED).subscribe(subscriber);
			}
		};
		CampaignDeliveryListener listener = new CampaignDeliveryListener(
				new ObjectMapper().findAndRegisterModules(), () -> settlement, new CapturingDeadLetters());

		listener.consume(record(VALID), acknowledgments::incrementAndGet);

		assertThat(indefiniteWaits).hasValue(1);
		assertThat(timedWaits).hasValue(0);
		assertThat(acknowledgments).hasValue(1);
	}

	private ConsumerRecord<String, String> record(String value) {
		return new ConsumerRecord<>(KafkaTopics.DELIVERY_JOBS, 0, 7, "opaque-message-id", value);
	}

	private static final class CapturingDeadLetters extends KafkaDeadLetterPublisher {
		private final AtomicInteger published = new AtomicInteger();
		private ConsumerRecord<String, String> source;
		private String targetTopic;
		private boolean fail;

		private CapturingDeadLetters() {
			super(null);
		}

		@Override
		public void publish(ConsumerRecord<String, String> source, String targetTopic, String category) {
			if (fail) throw new IllegalStateException("DLT unavailable");
			this.source = source;
			this.targetTopic = targetTopic;
			published.incrementAndGet();
		}
	}
}
