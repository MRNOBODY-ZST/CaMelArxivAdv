package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CampaignSafetyApiTest {

	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private CampaignSafetyService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(CampaignSafetyService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a");
		WebFilter principal = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		client = WebTestClient.bindToController(new CampaignSafetyController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).webFilter(principal).build();
	}

	@Test
	void startListGetAndCancelExposeOnlyMaskedProgress() {
		UUID campaign = UUID.randomUUID();
		UUID run = UUID.randomUUID();
		CampaignSafetyService.SafetyRunView view = view(run, campaign, "QUEUED", 0L);
		when(service.start(eq(campaign), eq(ACTOR), any(), any())).thenReturn(Mono.just(view));
		when(service.list(campaign)).thenReturn(Mono.just(List.of(view)));
		when(service.get(campaign, run)).thenReturn(Mono.just(view));
		when(service.cancel(eq(campaign), eq(run), eq(ACTOR), any(), eq(0L)))
				.thenReturn(Mono.just(view(run, campaign, "CANCELED", 1L)));

		client.post().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
				.bodyValue(Map.of("expectedLockVersion", 7, "recipientLimit", 1,
						"confirmation", "SAFETY_REDIRECT"))
				.exchange().expectStatus().isCreated().expectBody()
				.jsonPath("$.destinationMasked").isEqualTo("f***@example.test")
				.jsonPath("$.messages[0].campaignRecipientId").isEqualTo(
						"30000000-0000-0000-0000-000000000003");
		client.get().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
				.exchange().expectStatus().isOk().expectBody().jsonPath("$[0].id").isEqualTo(run.toString());
		client.get().uri("/api/v1/campaigns/{campaign}/safety-runs/{run}", campaign, run)
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.progress.total").isEqualTo(1);
		client.post().uri("/api/v1/campaigns/{campaign}/safety-runs/{run}/cancel", campaign, run)
				.bodyValue(Map.of("expectedLockVersion", 0)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("CANCELED");
	}

	@Test
	void unknownOverrideAndExtraFieldsFailClosedWithoutCallingTheService() {
		UUID campaign = UUID.randomUUID();
		for (Map<String, ?> body : List.of(
				Map.of("expectedLockVersion", 0, "recipientLimit", 1, "confirmation", "SAFETY_REDIRECT",
						"destination", "attacker@example.test"),
				Map.of("expectedLockVersion", 0, "recipientLimit", 1, "confirmation", "SAFETY_REDIRECT",
						"recipient", "attacker@example.test"),
				Map.of("expectedLockVersion", 0, "recipientLimit", 1, "confirmation", "SAFETY_REDIRECT",
						"extra", true))) {
			client.post().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
					.bodyValue(body).exchange().expectStatus().isBadRequest();
		}
		verifyNoInteractions(service);
	}

	@Test
	void requestBoundsAndExactConfirmationFailBeforeService() {
		UUID campaign = UUID.randomUUID();
		for (Map<String, ?> body : List.of(
				Map.of("expectedLockVersion", 0, "recipientLimit", 0, "confirmation", "SAFETY_REDIRECT"),
				Map.of("expectedLockVersion", 0, "recipientLimit", 21, "confirmation", "SAFETY_REDIRECT"),
				Map.of("expectedLockVersion", 0, "recipientLimit", 1, "confirmation", "wrong"))) {
			client.post().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
					.bodyValue(body).exchange().expectStatus().isBadRequest();
		}
		verifyNoInteractions(service);
	}

	@Test
	void startAndCancelRequireExactJsonScalarTypesWithoutDuplicatesOrTrailingRoots() {
		UUID campaign = UUID.randomUUID();
		UUID run = UUID.randomUUID();
		CampaignSafetyService.SafetyRunView view = view(run, campaign, "QUEUED", 0L);
		when(service.start(eq(campaign), eq(ACTOR), any(), any())).thenReturn(Mono.just(view));
		when(service.cancel(eq(campaign), eq(run), eq(ACTOR), any(), anyLong()))
				.thenReturn(Mono.just(view));
		for (String body : List.of(
				"{\"expectedLockVersion\":\"0\",\"recipientLimit\":1,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":0.0,\"recipientLimit\":1,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":true,\"recipientLimit\":1,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":null,\"recipientLimit\":1,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":0,\"recipientLimit\":\"1\",\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":0,\"recipientLimit\":1.5,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":0,\"recipientLimit\":true,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":0,\"recipientLimit\":null,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":0,\"recipientLimit\":1,\"confirmation\":1}",
				"{\"expectedLockVersion\":0,\"expectedLockVersion\":1,\"recipientLimit\":1,\"confirmation\":\"SAFETY_REDIRECT\"}",
				"{\"expectedLockVersion\":0,\"recipientLimit\":1,\"confirmation\":\"SAFETY_REDIRECT\"}{}")) {
			client.post().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
					.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
					.exchange().expectStatus().isBadRequest();
		}
		for (String body : List.of(
				"{\"expectedLockVersion\":\"0\"}",
				"{\"expectedLockVersion\":0.5}",
				"{\"expectedLockVersion\":true}",
				"{\"expectedLockVersion\":null}",
				"{\"expectedLockVersion\":0,\"expectedLockVersion\":1}",
				"{\"expectedLockVersion\":0}{}")) {
			client.post().uri("/api/v1/campaigns/{campaign}/safety-runs/{run}/cancel", campaign, run)
					.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
					.exchange().expectStatus().isBadRequest();
		}
		verifyNoInteractions(service);
	}

	@Test
	void declaresReadSendAndPausePermissions() {
		Arrays.stream(CampaignSafetyController.class.getDeclaredMethods())
				.filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
						|| method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class))
				.forEach(method -> {
					PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
					assertThat(permission).isNotNull();
					String expected = switch (method.getName()) {
						case "start" -> "campaign:send";
						case "cancel" -> "campaign:pause";
						default -> "campaign:read";
					};
					assertThat(permission.value()).isEqualTo("hasAuthority('" + expected + "')");
				});
	}

	private CampaignSafetyService.SafetyRunView view(UUID run, UUID campaign, String status, long version) {
		return new CampaignSafetyService.SafetyRunView(
				run, campaign, status, 1, "f***@example.test",
				new CampaignSafetyService.SafetyProgress(1, 1, 0, 0, 0, 0, 0, 0),
				new CampaignSafetyService.SafetyEventCounts(0, 0, 0, 0, 0, 0), version,
				null, null, Instant.parse("2030-04-05T10:15:30Z"),
				List.of(new CampaignSafetyService.SafetyMessageView(
						UUID.fromString("20000000-0000-0000-0000-000000000002"),
						UUID.fromString("30000000-0000-0000-0000-000000000003"),
						"QUEUED", 0, null, null, null)));
	}
}
