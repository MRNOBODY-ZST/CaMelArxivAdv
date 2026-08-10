package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.savedsearch.SavedSearchService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@Profile("!mail-worker")
@RequestMapping("/api/v1/arxiv/saved-searches")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArxivSavedSearchController {

	private final SavedSearchService service;

	public ArxivSavedSearchController(SavedSearchService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<PageResponse<SavedSearchService.SavedSearchView>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize,
			Principal principal
	) {
		return service.list(RequestContextSupport.actorId(principal), page, pageSize);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<SavedSearchService.SavedSearchView> get(@PathVariable UUID id, Principal principal) {
		return service.get(RequestContextSupport.actorId(principal), id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('paper:import')")
	Mono<SavedSearchService.SavedSearchView> create(
			@Valid @RequestBody ArxivSavedSearchDtos.UpsertRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.create(
				RequestContextSupport.actorId(principal), request.name(), request.criteria().criteria(),
				RequestContextSupport.context(exchange));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('paper:import')")
	Mono<SavedSearchService.SavedSearchView> update(
			@PathVariable UUID id,
			@Valid @RequestBody ArxivSavedSearchDtos.UpsertRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.update(
				RequestContextSupport.actorId(principal), id, request.name(), request.criteria().criteria(),
				RequestContextSupport.context(exchange));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('paper:import')")
	Mono<Void> delete(@PathVariable UUID id, Principal principal, ServerWebExchange exchange) {
		return service.delete(
				RequestContextSupport.actorId(principal), id, RequestContextSupport.context(exchange));
	}
}
