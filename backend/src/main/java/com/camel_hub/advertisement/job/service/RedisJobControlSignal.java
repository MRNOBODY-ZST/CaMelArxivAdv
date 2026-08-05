package com.camel_hub.advertisement.job.service;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

public class RedisJobControlSignal implements JobControlSignal {

	private static final Set<String> CONTROLS = Set.of("RUN", "PAUSE", "CANCEL");
	private static final Duration TTL = Duration.ofDays(30);
	private final ReactiveStringRedisTemplate redis;

	public RedisJobControlSignal(ReactiveStringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public Mono<Void> set(UUID jobId, String control) {
		if (!CONTROLS.contains(control)) {
			return Mono.error(new IllegalArgumentException("Job control signal is invalid"));
		}
		return redis.opsForValue().set("camel:jobs:control:" + jobId, control, TTL)
				.flatMap(written -> Boolean.TRUE.equals(written)
						? Mono.empty()
						: Mono.error(new IllegalStateException("Job control signal could not be stored")));
	}
}
