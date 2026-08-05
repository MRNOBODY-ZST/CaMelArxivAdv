package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArxivImportApiTest {

	private static final UUID ACTOR = UUID.fromString("211982fa-03d6-462d-9bb7-f84097fda6fd");
	private ArxivImportService service;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		service = mock(ArxivImportService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a");
		WebFilter principalFilter = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		webTestClient = WebTestClient.bindToController(new ArxivImportController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.webFilter(principalFilter).build();
	}

	@Test
	void createsSelectedAndOaiJobs() {
		UUID selectedJob = UUID.randomUUID();
		UUID oaiJob = UUID.randomUUID();
		when(service.createImport(eq(ACTOR), any(), any())).thenReturn(Mono.just(
				new ArxivImportService.JobSubmission(selectedJob, "PENDING", true, "selected-key")));
		when(service.createOaiSync(eq(ACTOR), any(), any())).thenReturn(Mono.just(
				new ArxivImportService.JobSubmission(oaiJob, "PENDING", true, "oai-key")));

		webTestClient.post().uri("/api/v1/arxiv/imports")
				.header("User-Agent", "import-api-test")
				.bodyValue(Map.of("arxivIds", java.util.List.of("2608.00001v2")))
				.exchange().expectStatus().isAccepted()
				.expectBody().jsonPath("$.jobId").isEqualTo(selectedJob.toString());
		webTestClient.post().uri("/api/v1/arxiv/oai/sync")
				.bodyValue(Map.of("setSpec", "cs:cs:AI", "from", "2026-08-01"))
				.exchange().expectStatus().isAccepted()
				.expectBody().jsonPath("$.jobId").isEqualTo(oaiJob.toString());
	}

	@Test
	void validatesIdentifiersBeforeCallingTheService() {
		webTestClient.post().uri("/api/v1/arxiv/imports")
				.bodyValue(Map.of("arxivIds", java.util.List.of("https://evil.invalid/value")))
				.exchange().expectStatus().isBadRequest();
	}

	@Test
	void requiresPaperImportPermissionOnBothCommands() {
		Arrays.stream(ArxivImportController.class.getDeclaredMethods())
				.filter(method -> method.getName().startsWith("create"))
				.forEach(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
						.isEqualTo("hasAuthority('paper:import')"));
	}
}
