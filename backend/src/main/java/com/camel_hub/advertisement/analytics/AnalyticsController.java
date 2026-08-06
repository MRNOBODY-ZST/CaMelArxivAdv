package com.camel_hub.advertisement.analytics;

import com.camel_hub.advertisement.common.api.RequestContextSupport;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.Principal;

@RestController
@Profile("api")
@RequestMapping("/api/v1/analytics")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsController {

	private final AnalyticsService service;

	public AnalyticsController(AnalyticsService service) {
		this.service = service;
	}

	@GetMapping("/overview")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<AnalyticsDtos.OverviewResponse> overview(@ModelAttribute AnalyticsQuery query) {
		return service.overview(query);
	}

	@GetMapping("/ingestion")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<AnalyticsDtos.IngestionResponse> ingestion(@ModelAttribute AnalyticsQuery query) {
		return service.ingestion(query);
	}

	@GetMapping("/papers")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<AnalyticsDtos.PapersResponse> papers(@ModelAttribute AnalyticsQuery query) {
		return service.papers(query);
	}

	@GetMapping("/contacts")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<AnalyticsDtos.ContactsResponse> contacts(@ModelAttribute AnalyticsQuery query) {
		return service.contacts(query);
	}

	@GetMapping("/filters")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<AnalyticsDtos.FilterOptionsResponse> filters(
			@ModelAttribute AnalyticsQuery query, Authentication authentication
	) {
		boolean includeUsers = authentication != null && authentication.getAuthorities().stream()
				.anyMatch(authority -> authority.getAuthority().equals("user:read"));
		return service.filters(query, includeUsers);
	}

	@GetMapping(value = "/{view}/export", produces = "text/csv;charset=UTF-8")
	@PreAuthorize("hasAuthority('analytics:read')")
	Mono<ResponseEntity<String>> export(
			@PathVariable String view,
			@RequestParam(defaultValue = "all") String dataset,
			@ModelAttribute AnalyticsQuery query,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.export(
				view, dataset, query, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange))
				.map(export -> ResponseEntity.ok()
						.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
						.header("X-Content-Type-Options", "nosniff")
						.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
								.filename(export.filename(), StandardCharsets.UTF_8).build().toString())
						.body(export.content()));
	}
}
