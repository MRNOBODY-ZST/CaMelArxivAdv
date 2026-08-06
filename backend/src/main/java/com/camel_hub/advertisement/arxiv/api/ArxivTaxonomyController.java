package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyService;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;

@RestController
@Profile("!mail-worker")
@RequestMapping("/api/v1/arxiv/taxonomy")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArxivTaxonomyController {

	private final TaxonomyService service;

	public ArxivTaxonomyController(TaxonomyService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<ArxivTaxonomyDtos.TaxonomyResponse> taxonomy() {
		return service.tree();
	}

	@PostMapping("/sync")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize("hasAuthority('system:manage')")
	Mono<ArxivTaxonomyDtos.TaxonomySyncResponse> sync(
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.requestSync(
				RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange));
	}
}
