package com.camel_hub.advertisement.arxiv.search;

import com.camel_hub.advertisement.arxiv.client.AtomFeed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

public class RedisArxivPreviewCache implements ArxivPreviewCache {

	private static final String KEY_PREFIX = "camel:arxiv:preview:";

	private final ReactiveStringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final Duration ttl;

	public RedisArxivPreviewCache(
			ReactiveStringRedisTemplate redis,
			ObjectMapper objectMapper,
			Duration ttl
	) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.ttl = ttl;
	}

	@Override
	public Mono<AtomFeed> get(String key) {
		String cacheKey = KEY_PREFIX + key;
		return redis.opsForValue().get(cacheKey)
				.flatMap(json -> {
					try {
						return Mono.just(objectMapper.readValue(json, AtomFeed.class));
					}
					catch (JsonProcessingException exception) {
						return redis.delete(cacheKey).then(Mono.empty());
					}
				});
	}

	@Override
	public Mono<Void> put(String key, AtomFeed value) {
		try {
			return redis.opsForValue().set(KEY_PREFIX + key, objectMapper.writeValueAsString(value), ttl).then();
		}
		catch (JsonProcessingException exception) {
			return Mono.error(new IllegalArgumentException("arXiv preview could not be cached", exception));
		}
	}
}
