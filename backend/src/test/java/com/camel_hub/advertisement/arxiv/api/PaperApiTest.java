package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.paper.PaperQueryService;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperApiTest {

	private PaperQueryService service;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		service = mock(PaperQueryService.class);
		webTestClient = WebTestClient.bindToController(new PaperController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();
	}

	@Test
	void listsPapersWithServerSideFilters() {
		UUID id = UUID.randomUUID();
		when(service.list(eq(1), eq(20), any())).thenReturn(Mono.just(PageResponse.of(List.of(
				new PaperQueryService.PaperSummary(
						id, "2608.00001", "Reliable Agents", "cs.AI", List.of("Ada Lovelace"),
						Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-04T00:00:00Z"),
						"10.1000/example", null, "UNKNOWN", 2)), 1, 20, 1)));

		webTestClient.get().uri(uri -> uri.path("/api/v1/papers")
				.queryParam("category", "cs.AI").queryParam("hasDoi", true).build())
				.exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.items[0].arxivId").isEqualTo("2608.00001")
				.jsonPath("$.total").isEqualTo(1);
	}

	@Test
	void declaresReadPermission() {
		for (Method method : PaperController.class.getDeclaredMethods()) {
			if (method.getName().equals("list") || method.getName().equals("get")) {
				assertThat(method.getAnnotation(PreAuthorize.class).value())
						.isEqualTo("hasAuthority('paper:read')");
			}
		}
	}
}
