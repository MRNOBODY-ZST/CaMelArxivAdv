package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/campaigns")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CampaignController {

	private final CampaignService service;
	private final CampaignWorkflowService workflow;
	private final CampaignPreflightService preflight;

	public CampaignController(
			CampaignService service, CampaignWorkflowService workflow, CampaignPreflightService preflight
	) {
		this.service = service;
		this.workflow = workflow;
		this.preflight = preflight;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('campaign:read')")
	Mono<PageResponse<CampaignService.CampaignView>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize
	) {
		return service.list(page, pageSize);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('campaign:read')")
	Mono<CampaignService.CampaignView> get(@PathVariable UUID id) {
		return service.get(id);
	}

	@GetMapping("/{id}/recipients")
	@PreAuthorize("hasAuthority('campaign:read')")
	Mono<PageResponse<CampaignService.RecipientView>> recipients(
			@PathVariable UUID id, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize
	) {
		return service.recipients(id, page, pageSize);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('campaign:create')")
	Mono<CampaignService.CampaignView> create(
			@Valid @RequestBody CampaignDtos.CreateRequest request, Principal principal
	) {
		return service.create(RequestContextSupport.actorId(principal), request.command());
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('campaign:create')")
	Mono<CampaignService.CampaignView> update(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.UpdateRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.update(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion(), request.command());
	}

	@GetMapping("/{id}/preflight")
	@PreAuthorize("hasAuthority('campaign:read')")
	Mono<CampaignPreflightService.PreflightView> preflight(@PathVariable UUID id) {
		return preflight.preflight(id);
	}

	@PostMapping("/{id}/submit-review")
	@PreAuthorize("hasAuthority('campaign:create')")
	Mono<CampaignService.CampaignView> submitReview(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.WorkflowRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.submitReview(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion());
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize("hasAuthority('campaign:approve')")
	Mono<CampaignService.CampaignView> approve(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.WorkflowRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.approve(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion());
	}

	@PostMapping("/{id}/reject")
	@PreAuthorize("hasAuthority('campaign:approve')")
	Mono<CampaignService.CampaignView> reject(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.RejectRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.reject(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion(), request.reason());
	}

	@PostMapping("/{id}/schedule")
	@PreAuthorize("hasAuthority('campaign:send')")
	Mono<CampaignService.CampaignView> schedule(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.ScheduleRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.schedule(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion(), request.scheduledAt());
	}

	@PostMapping("/{id}/start")
	@PreAuthorize("hasAuthority('campaign:send')")
	Mono<CampaignService.CampaignView> start(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.WorkflowRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.start(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion());
	}

	@PostMapping("/{id}/pause")
	@PreAuthorize("hasAuthority('campaign:pause')")
	Mono<CampaignService.CampaignView> pause(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.WorkflowRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.pause(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion());
	}

	@PostMapping("/{id}/resume")
	@PreAuthorize("hasAuthority('campaign:pause')")
	Mono<CampaignService.CampaignView> resume(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.WorkflowRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.resume(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion());
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('campaign:pause')")
	Mono<CampaignService.CampaignView> cancel(
			@PathVariable UUID id, @Valid @RequestBody CampaignDtos.WorkflowRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return workflow.cancel(id, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange),
				request.expectedLockVersion());
	}

	@PostMapping("/{id}/personalizations")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize("hasAuthority('campaign:create')")
	Mono<CampaignService.GenerationStart> startPersonalization(
			@PathVariable UUID id, ServerWebExchange exchange
	) {
		return service.startPersonalization(id, RequestContextSupport.context(exchange));
	}
}
