package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionService;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceExtractionApiTest {

	private static final UUID ACTOR = UUID.fromString("c98ac60e-e560-4c1d-846d-40fc75912a3b");
	private SourceExtractionService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(SourceExtractionService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a");
		WebFilter principal = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		client = WebTestClient.bindToController(new SourceExtractionController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.webFilter(principal).build();
	}

	@Test
	void createsSingleAndBatchExtractionJobs() {
		UUID paper = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID singleJob = UUID.randomUUID();
		UUID batchJob = UUID.randomUUID();
		when(service.create(eq(ACTOR), eq(List.of(paper)), any())).thenReturn(Mono.just(
				new SourceExtractionService.JobSubmission(singleJob, "PENDING")));
		when(service.create(eq(ACTOR), eq(List.of(paper, other)), any())).thenReturn(Mono.just(
				new SourceExtractionService.JobSubmission(batchJob, "PENDING")));

		client.post().uri("/api/v1/papers/{id}/extract", paper)
				.exchange().expectStatus().isAccepted()
				.expectBody().jsonPath("$.jobId").isEqualTo(singleJob.toString());
		client.post().uri("/api/v1/papers/batch-extract")
				.bodyValue(Map.of("paperIds", List.of(paper, other)))
				.exchange().expectStatus().isAccepted()
				.expectBody().jsonPath("$.jobId").isEqualTo(batchJob.toString());
	}

	@Test
	void validatesBatchBounds() {
		client.post().uri("/api/v1/papers/batch-extract")
				.bodyValue(Map.of("paperIds", List.of()))
				.exchange().expectStatus().isBadRequest();
	}

	@Test
	void requiresPaperImportPermissionForEveryExtractionCommand() {
		Arrays.stream(SourceExtractionController.class.getDeclaredMethods())
				.filter(method -> method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) != null)
				.forEach(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
						.isEqualTo("hasAuthority('paper:import')"));
	}
}
