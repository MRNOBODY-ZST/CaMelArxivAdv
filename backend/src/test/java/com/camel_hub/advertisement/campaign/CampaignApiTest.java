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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CampaignApiTest {

	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private CampaignService service;
	private CampaignWorkflowService workflow;
	private CampaignPreflightService preflight;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(CampaignService.class);
		workflow = mock(CampaignWorkflowService.class);
		preflight = mock(CampaignPreflightService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR.toString(), "n/a");
		WebFilter principal = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		client = WebTestClient.bindToController(new CampaignController(service, workflow, preflight))
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
				.bodyValue(Map.of(
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
	void authenticatedRecipientHttpResponseKeepsFrozenCapabilitiesAndBodiesRedacted() {
		UUID campaignId = UUID.randomUUID();
		String capability = "campaign-click:v1.capability-must-not-leak";
		Instant now = Instant.now();
		var recipient = new CampaignService.RecipientView(
				UUID.randomUUID(), "Ada", "Paper", "cs.AI", "Research Lab", "GENERATED",
				"Personal note", null, null, "Paper-specific rationale", null, null, now, now, true);
		when(service.recipients(campaignId, 1, 20))
				.thenReturn(Mono.just(PageResponse.of(List.of(recipient), 1, 20, 1)));

		String body = client.get().uri("/api/v1/campaigns/{id}/recipients", campaignId).exchange()
				.expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains("\"trackingArtifactsRedacted\":true", "Personal note")
				.doesNotContain(capability, "/t/o/", "/t/c/", "/u/", "renderedHtml", "renderedText");
	}

	@Test
	void mapsOptimisticWorkflowConflictToHttp409() {
		UUID campaignId = UUID.randomUUID();
		when(workflow.start(eq(campaignId), eq(ACTOR), any(), eq(7L)))
				.thenReturn(Mono.error(new CampaignConflictException("Campaign changed; refresh before continuing")));

		client.post().uri("/api/v1/campaigns/{id}/start", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 7)).exchange()
				.expectStatus().isEqualTo(409).expectBody()
				.jsonPath("$.type").isEqualTo("campaign_conflict");
	}

	@Test
	void distinguishesReadinessChangeAsHttp400FromOptimisticConflict() {
		UUID campaignId = UUID.randomUUID();
		when(workflow.start(eq(campaignId), eq(ACTOR), any(), eq(7L)))
				.thenReturn(Mono.error(new CampaignValidationException(
						"Campaign readiness changed; run preflight again")));

		client.post().uri("/api/v1/campaigns/{id}/start", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 7)).exchange()
				.expectStatus().isBadRequest().expectBody()
				.jsonPath("$.type").isEqualTo("invalid_campaign");
	}

	@Test
	void exposesPreflightAndEveryLifecycleEndpoint() {
		UUID campaignId = UUID.randomUUID();
		UUID templateId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		UUID smtpId = UUID.randomUUID();
		var draft = view(campaignId, templateId, segmentId, smtpId);
		var ready = status(draft, "READY_FOR_REVIEW", 1);
		var approved = status(draft, "APPROVED", 2);
		var rejected = status(draft, "REJECTED", 2);
		var scheduled = status(draft, "SCHEDULED", 3);
		var running = status(draft, "RUNNING", 3);
		var paused = status(draft, "PAUSED", 4);
		var canceled = status(draft, "CANCELED", 5);
		Map<String, CampaignPreflightService.PreflightCheck> checks = new LinkedHashMap<>();
		checks.put("CONTENT_READY", new CampaignPreflightService.PreflightCheck(true, "ready"));
		var preflightView = new CampaignPreflightService.PreflightView(
				true, checks, Map.of("TOTAL", 1L, "ELIGIBLE", 1L), 1,
				"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

		when(preflight.preflight(campaignId)).thenReturn(Mono.just(preflightView));
		when(workflow.update(eq(campaignId), eq(ACTOR), any(), eq(0L), any())).thenReturn(Mono.just(draft));
		when(workflow.submitReview(eq(campaignId), eq(ACTOR), any(), eq(0L))).thenReturn(Mono.just(ready));
		when(workflow.approve(eq(campaignId), eq(ACTOR), any(), eq(1L))).thenReturn(Mono.just(approved));
		when(workflow.reject(eq(campaignId), eq(ACTOR), any(), eq(1L), eq("Needs revision")))
				.thenReturn(Mono.just(rejected));
		when(workflow.schedule(eq(campaignId), eq(ACTOR), any(), eq(2L), any()))
				.thenReturn(Mono.just(scheduled));
		when(workflow.start(eq(campaignId), eq(ACTOR), any(), eq(2L))).thenReturn(Mono.just(running));
		when(workflow.pause(eq(campaignId), eq(ACTOR), any(), eq(3L))).thenReturn(Mono.just(paused));
		when(workflow.resume(eq(campaignId), eq(ACTOR), any(), eq(4L))).thenReturn(Mono.just(running));
		when(workflow.cancel(eq(campaignId), eq(ACTOR), any(), eq(4L))).thenReturn(Mono.just(canceled));

		client.get().uri("/api/v1/campaigns/{id}/preflight", campaignId).exchange()
				.expectStatus().isOk().expectBody()
				.jsonPath("$.checks.CONTENT_READY.passed").isEqualTo(true)
				.jsonPath("$.digest").isEqualTo(preflightView.digest());
		client.put().uri("/api/v1/campaigns/{id}", campaignId).bodyValue(Map.of(
				"expectedLockVersion", 0, "name", "Updated", "purpose", "Purpose",
				"mailboxAccountId", UUID.randomUUID(), "fromName", "Research Team",
				"replyTo", "reply@example.org", "trackingOpensEnabled", true,
				"trackingClicksEnabled", true)).exchange().expectStatus().isOk();
		client.post().uri("/api/v1/campaigns/{id}/submit-review", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 0)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("READY_FOR_REVIEW");
		client.post().uri("/api/v1/campaigns/{id}/approve", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 1)).exchange().expectStatus().isOk();
		client.post().uri("/api/v1/campaigns/{id}/reject", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 1, "reason", "Needs revision"))
				.exchange().expectStatus().isOk();
		client.post().uri("/api/v1/campaigns/{id}/schedule", campaignId).bodyValue(Map.of(
				"expectedLockVersion", 2, "scheduledAt", Instant.now().plusSeconds(3600).toString()))
				.exchange().expectStatus().isOk();
		client.post().uri("/api/v1/campaigns/{id}/start", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 2)).exchange().expectStatus().isOk();
		client.post().uri("/api/v1/campaigns/{id}/pause", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 3)).exchange().expectStatus().isOk();
		client.post().uri("/api/v1/campaigns/{id}/resume", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 4)).exchange().expectStatus().isOk();
		client.post().uri("/api/v1/campaigns/{id}/cancel", campaignId)
				.bodyValue(Map.of("expectedLockVersion", 4)).exchange().expectStatus().isOk();
	}

	@Test
	void declaresExactCampaignPermissions() {
		Arrays.stream(CampaignController.class.getDeclaredMethods())
				.filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
						|| method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
						|| method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class))
				.forEach(method -> {
					PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
					assertThat(permission).isNotNull();
					String expected = switch (method.getName()) {
						case "create", "update", "startPersonalization", "submitReview" -> "campaign:create";
						case "approve", "reject" -> "campaign:approve";
						case "schedule", "start" -> "campaign:send";
						case "pause", "resume", "cancel" -> "campaign:pause";
						default -> "campaign:read";
					};
					assertThat(permission.value()).isEqualTo("hasAuthority('" + expected + "')");
				});
	}

	private CampaignService.CampaignView view(UUID id, UUID template, UUID segment, UUID smtp) {
		Instant now = Instant.now();
		return new CampaignService.CampaignView(
				id, "Outreach", "Discuss", "DRAFT", template, "Template", 1, segment, "Segment", smtp,
				"SMTP", null, "Research Team", "research@example.org", "reply@example.org", false, false,
				"NOT_REQUESTED", null, null, null, new CampaignService.RecipientCounts(0, 0, 0, 0),
				new CampaignService.DeliveryCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0,
				null, null, null, null, null, null, null, null, null, null, null, null, now, now);
	}

	private CampaignService.CampaignView status(CampaignService.CampaignView value, String status, long lockVersion) {
		return new CampaignService.CampaignView(
				value.id(), value.name(), value.purpose(), status, value.templateId(), value.templateName(),
				value.templateVersion(), value.segmentId(), value.segmentName(), value.smtpAccountId(), value.smtpName(),
				value.mailboxAccountId(), value.fromName(), value.fromEmail(), value.replyTo(),
				value.trackingOpensEnabled(), value.trackingClicksEnabled(), value.generationStatus(),
				value.generationProvider(), value.generationModel(), value.generationJobId(), value.recipientCounts(),
				value.deliveryCounts(), lockVersion, value.submittedForReviewAt(), value.approvedAt(), value.approvedBy(),
				value.rejectedAt(), value.rejectedBy(), value.rejectionReason(), value.scheduledAt(), value.startedAt(),
				value.completedAt(), value.canceledAt(), value.statusChangedAt(), value.statusChangedBy(),
				value.createdAt(), value.updatedAt());
	}
}
