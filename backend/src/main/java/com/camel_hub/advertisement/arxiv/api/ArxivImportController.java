package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/arxiv")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArxivImportController {

	private final ArxivImportService service;

	public ArxivImportController(ArxivImportService service) {
		this.service = service;
	}

	@PostMapping("/imports")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize("hasAuthority('paper:import')")
	Mono<ArxivImportService.JobSubmission> createImport(
			@Valid @RequestBody ArxivImportDtos.ImportRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.createImport(
				RequestContextSupport.actorId(principal), request.command(),
				RequestContextSupport.context(exchange));
	}

	@PostMapping("/oai/sync")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize("hasAuthority('paper:import')")
	Mono<ArxivImportService.JobSubmission> createOaiSync(
			@Valid @RequestBody ArxivImportDtos.OaiSyncRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.createOaiSync(
				RequestContextSupport.actorId(principal), request.command(),
				RequestContextSupport.context(exchange));
	}
}
