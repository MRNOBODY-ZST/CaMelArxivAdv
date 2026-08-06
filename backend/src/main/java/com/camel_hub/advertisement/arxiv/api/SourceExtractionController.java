package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionService;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/papers")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SourceExtractionController {

	private final SourceExtractionService service;

	public SourceExtractionController(SourceExtractionService service) {
		this.service = service;
	}

	@PostMapping("/{id}/extract")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize("hasAuthority('paper:import')")
	Mono<SourceExtractionService.JobSubmission> extract(
			@PathVariable UUID id,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.create(
				RequestContextSupport.actorId(principal), List.of(id),
				RequestContextSupport.context(exchange));
	}

	@PostMapping("/batch-extract")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize("hasAuthority('paper:import')")
	Mono<SourceExtractionService.JobSubmission> batchExtract(
			@Valid @RequestBody SourceExtractionDtos.BatchRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.create(
				RequestContextSupport.actorId(principal), request.paperIds(),
				RequestContextSupport.context(exchange));
	}
}
