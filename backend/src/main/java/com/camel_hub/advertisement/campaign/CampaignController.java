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

	public CampaignController(CampaignService service) {
		this.service = service;
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

	@PostMapping("/{id}/personalizations")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize("hasAuthority('campaign:create')")
	Mono<CampaignService.GenerationStart> startPersonalization(
			@PathVariable UUID id, ServerWebExchange exchange
	) {
		return service.startPersonalization(id, RequestContextSupport.context(exchange));
	}
}
