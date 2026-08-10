package com.camel_hub.advertisement.arxiv.client;

import com.camel_hub.advertisement.arxiv.config.ArxivProperties;
import com.camel_hub.advertisement.arxiv.search.ArxivLegacyQueryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivLegacyClientTest {

	@Test
	void retriesAServiceFailureAndAcquiresALeaseForEveryRequest() throws IOException {
		byte[] fixture = getClass().getResourceAsStream("/arxiv/legacy-preview.xml").readAllBytes();
		AtomicInteger calls = new AtomicInteger();
		ExchangeFunction exchange = request -> {
			int attempt = calls.incrementAndGet();
			return Mono.just(attempt == 1
					? ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("do not expose me").build()
					: ClientResponse.create(HttpStatus.OK)
							.header("Content-Type", "application/atom+xml")
							.body(new String(fixture, java.nio.charset.StandardCharsets.UTF_8)).build());
		};
		GlobalArxivRateLease lease = mock(GlobalArxivRateLease.class);
		when(lease.awaitPermit()).thenReturn(Mono.empty());
		ArxivLegacyClient client = client(exchange, lease, 2);

		AtomFeed feed = client.fetch(query()).block();

		assertThat(feed).isNotNull();
		assertThat(feed.totalResults()).isEqualTo(42);
		assertThat(calls).hasValue(2);
		verify(lease, times(2)).awaitPermit();
	}

	@Test
	void doesNotRetryOtherClientErrorsOrExposeTheirBodies() {
		AtomicInteger calls = new AtomicInteger();
		ExchangeFunction exchange = request -> {
			calls.incrementAndGet();
			return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
					.body("sensitive upstream diagnostic").build());
		};
		GlobalArxivRateLease lease = mock(GlobalArxivRateLease.class);
		when(lease.awaitPermit()).thenReturn(Mono.empty());

		assertThatThrownBy(() -> client(exchange, lease, 2).fetch(query()).block())
				.isInstanceOf(ArxivDependencyException.class)
				.hasMessageNotContaining("sensitive upstream diagnostic");
		assertThat(calls).hasValue(1);
		verify(lease, times(1)).awaitPermit();
	}

	private ArxivLegacyClient client(
			ExchangeFunction exchange,
			GlobalArxivRateLease lease,
			int retries
	) {
		return new ArxivLegacyClient(
				WebClient.builder().exchangeFunction(exchange).build(), lease,
				new ArxivProperties(
						URI.create("https://export.arxiv.org/api/query"),
						URI.create("https://oaipmh.arxiv.org/oai"),
						Set.of("export.arxiv.org", "oaipmh.arxiv.org"),
						"classpath:arxiv/taxonomy-2026-08.json",
						Duration.ofHours(24), Duration.ofSeconds(5), Duration.ofSeconds(3),
						retries, 100, 10_000, "CaMelArxivAdv/0.1 (admin@example.invalid)"),
				new AtomFeedParser());
	}

	private ArxivLegacyQueryBuilder.LegacyQuery query() {
		return new ArxivLegacyQueryBuilder.LegacyQuery(
				"cat:cs.AI", 0, 20, "relevance", "descending");
	}
}
