package com.camel_hub.advertisement.arxiv.client;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class RedisGlobalArxivRateLeaseTest {

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.0.2-alpine")
			.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private ReactiveStringRedisTemplate redis;

	@BeforeAll
	static void connect() {
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
	}

	@AfterAll
	static void disconnect() {
		if (connectionFactory != null) {
			connectionFactory.destroy();
		}
	}

	@BeforeEach
	void clearLease() {
		redis = new ReactiveStringRedisTemplate(
				connectionFactory, RedisSerializationContext.string());
		redis.delete(RedisGlobalArxivRateLease.LEASE_KEY).block();
	}

	@Test
	void atomicallyReservesConcurrentSlotsAtLeastThreeSecondsApart() {
		RedisGlobalArxivRateLease lease = new RedisGlobalArxivRateLease(redis, Duration.ofSeconds(3));

		var delays = Flux.merge(lease.reserveDelay(), lease.reserveDelay(), lease.reserveDelay())
				.collectList().block();

		assertThat(delays).isNotNull();
		var sorted = delays.stream().sorted(Comparator.naturalOrder()).toList();
		assertThat(sorted.get(0)).isBetween(Duration.ZERO, Duration.ofSeconds(1));
		assertThat(sorted.get(1)).isGreaterThanOrEqualTo(Duration.ofMillis(2_900));
		assertThat(sorted.get(2)).isGreaterThanOrEqualTo(Duration.ofMillis(5_900));
	}

	@Test
	@SuppressWarnings("unchecked")
	void failsClosedWhenRedisCannotReserveASlot() {
		ReactiveStringRedisTemplate failingRedis = mock(ReactiveStringRedisTemplate.class);
		when(failingRedis.execute(
				any(RedisScript.class), anyList(), any(Object[].class)))
				.thenReturn(Flux.error(new IllegalStateException("offline")));
		RedisGlobalArxivRateLease lease = new RedisGlobalArxivRateLease(
				failingRedis, Duration.ofSeconds(3));

		assertThatThrownBy(() -> lease.reserveDelay().block())
				.isInstanceOf(ArxivDependencyException.class)
				.hasMessageContaining("rate lease");
	}

	@Test
	void usesTheCrossLanguageLeaseKey() {
		assertThat(RedisGlobalArxivRateLease.LEASE_KEY)
				.isEqualTo("camel:arxiv:global-next-request-ms");
	}
}
