package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.common.api.PageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@Profile("api")
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CampaignReportingController {

	private final CampaignReportingService service;

	public CampaignReportingController(CampaignReportingService service) {
		this.service = service;
	}

	@GetMapping("/deliveries")
	@PreAuthorize("hasAuthority('campaign:read')")
	Mono<PageResponse<CampaignReportingRepository.DeliveryView>> deliveries(
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize
	) {
		return service.deliveries(page, pageSize);
	}

	@GetMapping("/campaign-analytics")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<PageResponse<CampaignReportingRepository.CampaignAnalyticsView>> campaigns(
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize
	) {
		return service.campaigns(page, pageSize);
	}

	@GetMapping("/link-analytics")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<PageResponse<CampaignReportingRepository.LinkAnalyticsView>> links(
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize
	) {
		return service.links(page, pageSize);
	}
}
