package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
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

class CampaignApiTest {

	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private CampaignService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(CampaignService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a");
		WebFilter principal = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		client = WebTestClient.bindToController(new CampaignController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).webFilter(principal).build();
	}

	@Test
	void listsCreatesAndStartsPersonalization() {
		UUID campaignId = UUID.randomUUID();
		UUID templateId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		UUID smtpId = UUID.randomUUID();
		var view = view(campaignId, templateId, segmentId, smtpId);
		when(service.list(1, 20)).thenReturn(Mono.just(PageResponse.of(List.of(view), 1, 20, 1)));
		when(service.create(eq(ACTOR), any())).thenReturn(Mono.just(view));
		when(service.startPersonalization(eq(campaignId), any()))
				.thenReturn(Mono.just(new CampaignService.GenerationStart(UUID.randomUUID(), 3)));

		client.get().uri("/api/v1/campaigns?page=1&pageSize=20").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.total").isEqualTo(1);
		client.post().uri("/api/v1/campaigns")
				.bodyValue(java.util.Map.of(
						"name", "Outreach", "purpose", "Discuss the paper", "templateId", templateId,
						"segmentId", segmentId, "smtpAccountId", smtpId))
				.exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").isEqualTo(campaignId.toString());
		client.post().uri("/api/v1/campaigns/{id}/personalizations", campaignId).exchange()
				.expectStatus().isAccepted().expectBody().jsonPath("$.queuedRecipients").isEqualTo(3);
	}

	@Test
	void mapsDisabledGenerationToServiceUnavailable() {
		UUID campaignId = UUID.randomUUID();
		when(service.startPersonalization(eq(campaignId), any()))
				.thenReturn(Mono.error(new PersonalizationUnavailableException("Personalization is not configured")));

		client.post().uri("/api/v1/campaigns/{id}/personalizations", campaignId).exchange()
				.expectStatus().isEqualTo(503).expectBody().jsonPath("$.type")
				.isEqualTo("personalization_unavailable");
	}

	@Test
	void declaresReadAndCreatePermissions() {
		Arrays.stream(CampaignController.class.getDeclaredMethods())
				.filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
						|| method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class))
				.forEach(method -> {
					PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
					assertThat(permission).isNotNull();
					if (method.getName().equals("create") || method.getName().equals("startPersonalization")) {
						assertThat(permission.value()).isEqualTo("hasAuthority('campaign:create')");
					}
					else {
						assertThat(permission.value()).isEqualTo("hasAuthority('campaign:read')");
					}
				});
	}

	private CampaignService.CampaignView view(UUID id, UUID template, UUID segment, UUID smtp) {
		Instant now = Instant.now();
		return new CampaignService.CampaignView(
				id, "Outreach", "Discuss", "DRAFT", template, "Template", 1, segment, "Segment", smtp,
				"SMTP", "Research Team", "research@example.org", "reply@example.org", "NOT_REQUESTED",
				null, null, null, new CampaignService.RecipientCounts(0, 0, 0, 0), now, now);
	}
}
