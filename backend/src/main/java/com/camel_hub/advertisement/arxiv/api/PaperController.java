package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.paper.PaperQueryService;
import com.camel_hub.advertisement.common.api.PageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RestController
@Profile("!mail-worker")
@RequestMapping("/api/v1/papers")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaperController {

	private final PaperQueryService service;

	public PaperController(PaperQueryService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<PageResponse<PaperQueryService.PaperSummary>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant submittedFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant submittedTo,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedTo,
			@RequestParam(required = false) String title,
			@RequestParam(required = false) String author,
			@RequestParam(required = false) String sourceStatus,
			@RequestParam(required = false) Boolean hasDoi,
			@RequestParam(required = false) Boolean hasJournalReference,
			@RequestParam(defaultValue = "UPDATED_AT") PaperQueryService.SortBy sortBy,
			@RequestParam(defaultValue = "DESCENDING") PaperQueryService.SortOrder sortOrder
	) {
		var filter = new PaperQueryService.PaperFilter(
				category, submittedFrom, submittedTo, updatedFrom, updatedTo, title, author,
				sourceStatus, hasDoi, hasJournalReference, sortBy, sortOrder);
		return service.list(page, pageSize, filter)
				.onErrorMap(IllegalArgumentException.class, this::badRequest);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<PaperQueryService.PaperDetail> get(@PathVariable UUID id) {
		return service.get(id);
	}

	private ResponseStatusException badRequest(IllegalArgumentException exception) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
	}
}
