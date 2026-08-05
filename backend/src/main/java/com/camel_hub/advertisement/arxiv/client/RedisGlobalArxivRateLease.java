package com.camel_hub.advertisement.arxiv.client;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

public class RedisGlobalArxivRateLease implements GlobalArxivRateLease {

	public static final String LEASE_KEY = "camel:arxiv:global-next-request-ms";
	private static final Duration OFFICIAL_MINIMUM_INTERVAL = Duration.ofSeconds(3);
	private static final String RESERVE_SCRIPT = """
			local server_time = redis.call('TIME')
			local now_ms = (tonumber(server_time[1]) * 1000) + math.floor(tonumber(server_time[2]) / 1000)
			local next_ms = tonumber(redis.call('GET', KEYS[1]))
			if next_ms == nil or next_ms < now_ms then
			  next_ms = now_ms
			end
			local interval_ms = tonumber(ARGV[1])
			redis.call('SET', KEYS[1], next_ms + interval_ms, 'PX', 86400000)
			return next_ms - now_ms
			""";
	private static final DefaultRedisScript<Long> SCRIPT =
			new DefaultRedisScript<>(RESERVE_SCRIPT, Long.class);

	private final ReactiveStringRedisTemplate redis;
	private final Duration interval;

	public RedisGlobalArxivRateLease(ReactiveStringRedisTemplate redis, Duration interval) {
		if (interval == null || interval.compareTo(OFFICIAL_MINIMUM_INTERVAL) < 0) {
			throw new IllegalArgumentException("arXiv rate lease interval must be at least three seconds");
		}
		this.redis = redis;
		this.interval = interval;
	}

	@Override
	public Mono<Void> awaitPermit() {
		return reserveDelay().flatMap(delay -> delay.isZero()
				? Mono.empty()
				: Mono.delay(delay).then());
	}

	Mono<Duration> reserveDelay() {
		return redis.execute(SCRIPT, List.of(LEASE_KEY), String.valueOf(interval.toMillis()))
				.single()
				.map(delay -> Duration.ofMillis(Math.max(0L, delay)))
				.onErrorMap(exception -> exception instanceof ArxivDependencyException
						? exception
						: new ArxivDependencyException("Could not reserve the global arXiv rate lease", exception));
	}
}
