package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentApiTest {

	private SegmentService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(SegmentService.class);
		AuthenticatedUser user = new AuthenticatedUser(
				UUID.randomUUID(), "manager", "Campaign Manager", Set.of("CAMPAIGN_MANAGER"),
				Set.of("campaign:read", "campaign:create"), false, 0);
		var authentication = UsernamePasswordAuthenticationToken.authenticated(user, "token", List.of());
		WebFilter principal = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		client = WebTestClient.bindToController(new SegmentController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).webFilter(principal).build();
	}

	@Test
	void exposesPaginatedSegmentsAndPreview() {
		when(service.list(1, 20)).thenReturn(Mono.just(PageResponse.of(List.of(), 1, 20, 0)));
		when(service.preview(any())).thenReturn(Mono.just(new SegmentService.PreviewView(0, List.of())));

		client.get().uri("/api/v1/segments?page=1&pageSize=20").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.total").isEqualTo(0);
		client.post().uri("/api/v1/segments/preview")
				.bodyValue(java.util.Map.of("rules", List.of(java.util.Map.of(
						"field", "confidence", "operator", "equals", "value", "HIGH"))))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.eligibleCount").isEqualTo(0);
	}

	@Test
	void createsASegmentWithTheActorIdentity() {
		UUID id = UUID.randomUUID();
		var view = new SegmentService.SegmentView(
				id, "AI", null, List.of(), 0, Instant.now(), Instant.now());
		when(service.create(any(), any())).thenReturn(Mono.just(view));

		client.post().uri("/api/v1/segments")
				.bodyValue(java.util.Map.of(
						"name", "AI", "rules", List.of(java.util.Map.of(
								"field", "confidence", "operator", "equals", "value", "HIGH"))))
				.exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").isEqualTo(id.toString());
	}

	@Test
	void declaresReadAndCreatePermissions() {
		Arrays.stream(SegmentController.class.getDeclaredMethods())
				.filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
						|| method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class))
				.forEach(method -> {
					PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
					assertThat(permission).isNotNull();
					if (method.getName().equals("create")) {
						assertThat(permission.value()).isEqualTo("hasAuthority('campaign:create')");
					}
					else {
						assertThat(permission.value()).isEqualTo("hasAuthority('campaign:read')");
					}
				});
	}
}
