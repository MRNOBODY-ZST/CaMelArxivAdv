package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.client.ArxivPaperPreview;
import com.camel_hub.advertisement.arxiv.search.ArxivPreviewResult;
import com.camel_hub.advertisement.arxiv.search.ArxivPreviewService;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArxivSearchApiTest {

	private ArxivPreviewService service;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		service = mock(ArxivPreviewService.class);
		webTestClient = WebTestClient.bindToController(new ArxivSearchController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.build();
	}

	@Test
	void previewsANormalizedCategoryQuery() {
		when(service.preview(any())).thenReturn(Mono.just(new ArxivPreviewResult(
				"abc123", new ArxivSearchCriteria(
						List.of("cs.AI"), ArxivSearchCriteria.CategoryMode.ANY,
						null, null, null, null, null, null, null,
						null, null, null, ArxivSearchCriteria.SortBy.RELEVANCE,
						ArxivSearchCriteria.SortOrder.DESCENDING, 1, 20),
				42, true, 1, 20, ArxivPreviewResult.CacheStatus.MISS,
				List.of(new ArxivPreviewResult.FilterAnnotation(
						"categoryIds", ArxivPreviewResult.FilterSource.OFFICIAL, true,
						"arXiv Category query")),
				List.of(new ArxivPaperPreview(
						"2608.00001", "Reliable Agents", "Summary",
						List.of(new ArxivPaperPreview.Author("Ada Lovelace", List.of())),
						"cs.AI", List.of("cs.AI"), Instant.parse("2026-08-01T09:00:00Z"),
						Instant.parse("2026-08-04T12:30:00Z"), null, null, null, null,
						"https://arxiv.org/pdf/2608.00001v1", 1)))));

		webTestClient.post().uri("/api/v1/arxiv/search/preview")
				.bodyValue(Map.of(
						"categoryIds", List.of("cs.AI"), "categoryMode", "ANY",
						"page", 1, "pageSize", 20,
						"sortBy", "RELEVANCE", "sortOrder", "DESCENDING"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.officialTotal").isEqualTo(42)
				.jsonPath("$.cacheStatus").isEqualTo("MISS")
				.jsonPath("$.papers[0].arxivId").isEqualTo("2608.00001");
	}

	@Test
	void declaresPaperReadPermission() throws NoSuchMethodException {
		Method method = ArxivSearchController.class.getDeclaredMethod(
				"preview", ArxivSearchDtos.PreviewRequest.class);
		assertThat(method.getAnnotation(PreAuthorize.class).value())
				.isEqualTo("hasAuthority('paper:read')");
	}
}
