package com.camel_hub.advertisement.analytics;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsApiTest {

	private AnalyticsService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(AnalyticsService.class);
		client = WebTestClient.bindToController(new AnalyticsController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();
	}

	@Test
	void exposesOverviewUsingTypedGlobalFilters() {
		var window = new AnalyticsDtos.Window(
				LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-06"),
				"papers.imported_at", "UTC");
		var response = new AnalyticsDtos.OverviewResponse(
				window, new AnalyticsDtos.Freshness(
						Instant.parse("2026-08-06T10:00:00Z"), "CURRENT",
						Instant.parse("2026-08-06T11:00:00Z")),
				List.of(new AnalyticsDtos.Metric(
						"cohortPapers", "Imported papers", 12, 12, 1, "count", "definition")),
				List.of(), List.of(), List.of(), List.of());
		when(service.overview(any())).thenReturn(Mono.just(response));

		client.get().uri(uri -> uri.path("/api/v1/analytics/overview")
				.queryParam("from", "2026-08-01")
				.queryParam("to", "2026-08-06")
				.queryParam("categoryId", "cs.AI")
				.queryParam("relation", "PRIMARY")
				.queryParam("domain", "example.edu")
				.queryParam("confidence", "HIGH").build())
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.window.dateBasis").isEqualTo("papers.imported_at")
				.jsonPath("$.window.timezone").isEqualTo("UTC")
				.jsonPath("$.metrics[0].value").isEqualTo(12);
	}

	@Test
	void exposesTheTypedAuthorRelationshipGraph() {
		UUID alice = UUID.fromString("50000000-0000-0000-0000-000000000001");
		UUID bob = UUID.fromString("50000000-0000-0000-0000-000000000002");
		var window = new AnalyticsDtos.Window(
				LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-06"),
				"papers.imported_at", "UTC");
		var response = new AnalyticsDtos.AuthorsResponse(
				window,
				new AnalyticsDtos.Freshness(
						Instant.parse("2026-08-06T10:00:00Z"), "CURRENT",
						Instant.parse("2026-08-06T11:00:00Z")),
				new AnalyticsDtos.AuthorGraphSummary(2, 1, 1, false),
				List.of(
						new AnalyticsDtos.AuthorNode(alice, "Alice", 1, 1, 1),
						new AnalyticsDtos.AuthorNode(bob, "Bob", 1, 1, 0)),
				List.of(new AnalyticsDtos.AuthorEdge(alice, bob, 1)));
		when(service.authors(any())).thenReturn(Mono.just(response));

		client.get().uri(uri -> uri.path("/api/v1/analytics/authors")
				.queryParam("from", "2026-08-01")
				.queryParam("to", "2026-08-06").build())
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.summary.totalAuthors").isEqualTo(2)
				.jsonPath("$.nodes[0].id").isEqualTo(alice.toString())
				.jsonPath("$.nodes[0].label").isEqualTo("Alice")
				.jsonPath("$.edges[0].source").isEqualTo(alice.toString())
				.jsonPath("$.edges[0].target").isEqualTo(bob.toString())
				.jsonPath("$.edges[0].sharedPaperCount").isEqualTo(1);
	}

	@Test
	void appliesAnalyticsReadToEveryEndpoint() {
		Arrays.stream(AnalyticsController.class.getDeclaredMethods())
				.filter(method -> method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null)
				.forEach(method -> {
					PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
					assertThat(permission).isNotNull();
					assertThat(permission.value()).isEqualTo("hasAuthority('analytics:read')");
				});
	}
}
