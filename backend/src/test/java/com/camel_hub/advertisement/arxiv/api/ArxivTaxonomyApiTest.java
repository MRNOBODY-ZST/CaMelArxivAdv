package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyService;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArxivTaxonomyApiTest {

	private static final UUID ACTOR_ID = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");
	private TaxonomyService service;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		service = mock(TaxonomyService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR_ID.toString(), "n/a");
		WebFilter principalFilter = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		webTestClient = WebTestClient.bindToController(new ArxivTaxonomyController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.webFilter(principalFilter)
				.build();
	}

	@Test
	void returnsSnapshotMetadataAndTheNestedTaxonomyTree() {
		when(service.tree()).thenReturn(Mono.just(new ArxivTaxonomyDtos.TaxonomyResponse(
				"arxiv-taxonomy-2026-08", "OFFLINE_SNAPSHOT",
				List.of("https://arxiv.org/category_taxonomy"),
				Instant.parse("2026-07-25T08:25:04Z"), Instant.parse("2026-08-05T16:30:00Z"),
				List.of(new ArxivTaxonomyDtos.GroupResponse(
						"cs", "Computer Science", List.of(new ArxivTaxonomyDtos.ArchiveResponse(
						"cs", "Computer Science", List.of(new ArxivTaxonomyDtos.CategoryResponse(
								"cs.AI", "Artificial Intelligence", "Official description",
								false, null)))))))));

		webTestClient.get().uri("/api/v1/arxiv/taxonomy").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.snapshotVersion").isEqualTo("arxiv-taxonomy-2026-08")
				.jsonPath("$.groups[0].archives[0].categories[0].categoryId").isEqualTo("cs.AI");
	}

	@Test
	void createsAnAuditedIdempotentTaxonomySyncJob() {
		UUID jobId = UUID.fromString("ed1e38b7-4465-4360-9974-058d45bf24b0");
		when(service.requestSync(eq(ACTOR_ID), any())).thenReturn(Mono.just(
				new ArxivTaxonomyDtos.TaxonomySyncResponse(jobId, "PENDING", true)));

		webTestClient.post().uri("/api/v1/arxiv/taxonomy/sync")
				.header("User-Agent", "taxonomy-api-test")
				.exchange().expectStatus().isAccepted()
				.expectBody()
				.jsonPath("$.jobId").isEqualTo(jobId.toString())
				.jsonPath("$.created").isEqualTo(true);
	}

	@Test
	void declaresReadAndAdministrativePermissions() {
		assertPermission("taxonomy", "hasAuthority('paper:read')");
		assertPermission("sync", "hasAuthority('system:manage')");
	}

	private void assertPermission(String methodName, String expected) {
		PreAuthorize annotation = Arrays.stream(ArxivTaxonomyController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals(methodName))
				.findFirst().orElseThrow()
				.getAnnotation(PreAuthorize.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.value()).isEqualTo(expected);
	}
}
