package com.camel_hub.advertisement.arxiv.client;

import com.camel_hub.advertisement.arxiv.config.ArxivProperties;
import com.camel_hub.advertisement.arxiv.search.ArxivLegacyQueryBuilder;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class ArxivLegacyClient {

	private static final long MAXIMUM_RESPONSE_BYTES = 5L * 1024L * 1024L;

	private final WebClient webClient;
	private final GlobalArxivRateLease rateLease;
	private final ArxivProperties properties;
	private final AtomFeedParser parser;

	public ArxivLegacyClient(
			WebClient webClient,
			GlobalArxivRateLease rateLease,
			ArxivProperties properties,
			AtomFeedParser parser
	) {
		this.webClient = webClient;
		this.rateLease = rateLease;
		this.properties = properties;
		this.parser = parser;
	}

	public Mono<AtomFeed> fetch(ArxivLegacyQueryBuilder.LegacyQuery query) {
		URI uri = UriComponentsBuilder.fromUri(properties.legacyBaseUrl())
				.queryParam("search_query", query.searchQuery())
				.queryParam("start", query.start())
				.queryParam("max_results", query.maxResults())
				.queryParam("sortBy", query.sortBy())
				.queryParam("sortOrder", query.sortOrder())
				.build().encode().toUri();
		Mono<AtomFeed> attempt = Mono.defer(() -> rateLease.awaitPermit()
				.then(request(uri)))
				.timeout(properties.requestTimeout());
		Retry retry = Retry.backoff(properties.maxRetries(), Duration.ofMillis(250))
				.maxBackoff(Duration.ofSeconds(5))
				.jitter(0.5)
				.filter(this::retryable)
				.onRetryExhaustedThrow((spec, signal) -> signal.failure());
		return attempt.retryWhen(retry)
				.onErrorMap(this::mapFailure);
	}

	private Mono<AtomFeed> request(URI uri) {
		return webClient.get().uri(uri)
				.header("User-Agent", properties.userAgent())
				.exchangeToMono(response -> {
					HttpStatusCode status = response.statusCode();
					if (status.is2xxSuccessful()) {
						long contentLength = response.headers().contentLength().orElse(-1L);
						if (contentLength > MAXIMUM_RESPONSE_BYTES) {
							return response.releaseBody().then(Mono.error(
									new ArxivDependencyException("arXiv response exceeded the size limit", null)));
						}
						return response.bodyToMono(byte[].class)
								.flatMap(payload -> payload.length > MAXIMUM_RESPONSE_BYTES
										? Mono.error(new ArxivDependencyException(
												"arXiv response exceeded the size limit", null))
										: Mono.fromCallable(() -> parser.parse(payload)));
					}
					if (status.value() == 429 || status.is5xxServerError()) {
						return response.releaseBody().then(Mono.error(
								new RetryableArxivException("arXiv temporarily rejected the request")));
					}
					return response.releaseBody().then(Mono.error(
							new ArxivDependencyException("arXiv rejected the query", null)));
				});
	}

	private boolean retryable(Throwable throwable) {
		Throwable unwrapped = Exceptions.unwrap(throwable);
		return unwrapped instanceof RetryableArxivException
				|| unwrapped instanceof TimeoutException
				|| unwrapped instanceof WebClientRequestException;
	}

	private Throwable mapFailure(Throwable throwable) {
		Throwable unwrapped = Exceptions.unwrap(throwable);
		if (unwrapped instanceof ArxivDependencyException) {
			return unwrapped;
		}
		if (unwrapped instanceof RetryableArxivException
				|| unwrapped instanceof TimeoutException
				|| unwrapped instanceof WebClientRequestException) {
			return new ArxivDependencyException("arXiv is temporarily unavailable", unwrapped);
		}
		if (unwrapped instanceof IllegalArgumentException) {
			return new ArxivDependencyException("arXiv returned invalid metadata", unwrapped);
		}
		return unwrapped;
	}

	private static final class RetryableArxivException extends RuntimeException {
		private RetryableArxivException(String message) {
			super(message);
		}
	}
}
