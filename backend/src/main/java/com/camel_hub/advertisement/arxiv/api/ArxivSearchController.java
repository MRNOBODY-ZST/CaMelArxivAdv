package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.search.ArxivPreviewResult;
import com.camel_hub.advertisement.arxiv.search.ArxivPreviewService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/arxiv/search")
@ConditionalOnBean(ArxivPreviewService.class)
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArxivSearchController {

	private final ArxivPreviewService service;

	public ArxivSearchController(ArxivPreviewService service) {
		this.service = service;
	}

	@PostMapping("/preview")
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<ArxivPreviewResult> preview(@Valid @RequestBody ArxivSearchDtos.PreviewRequest request) {
		return service.preview(request.criteria())
				.onErrorMap(IllegalArgumentException.class, exception -> new ResponseStatusException(
						HttpStatus.BAD_REQUEST, exception.getMessage(), exception));
	}
}
