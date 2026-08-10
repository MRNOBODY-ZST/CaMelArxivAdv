package com.camel_hub.advertisement.job.api;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import com.camel_hub.advertisement.job.domain.JobAction;
import com.camel_hub.advertisement.job.domain.JobStatus;
import com.camel_hub.advertisement.job.service.JobService;
import com.camel_hub.advertisement.job.service.JobEventStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("!mail-worker")
@RequestMapping("/api/v1/jobs")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobController {

	private final JobService service;
	private final JobEventStream eventStream;

	public JobController(JobService service, JobEventStream eventStream) {
		this.service = service;
		this.eventStream = eventStream;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<PageResponse<JobService.JobView>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize,
			@RequestParam(required = false) JobStatus status,
			@RequestParam(required = false) String type
	) {
		return service.list(page, pageSize, status, type)
				.onErrorMap(IllegalArgumentException.class, exception -> badRequest(exception));
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<JobService.JobView> get(@PathVariable UUID id) {
		return service.get(id);
	}

	@GetMapping("/{id}/events")
	@PreAuthorize("hasAuthority('paper:read')")
	Mono<List<JobService.JobEventView>> events(
			@PathVariable UUID id,
			@RequestParam(defaultValue = "0") long afterId,
			@RequestParam(defaultValue = "100") int limit
	) {
		return service.events(id, afterId, limit)
				.onErrorMap(IllegalArgumentException.class, exception -> badRequest(exception));
	}

	@GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@PreAuthorize("hasAuthority('paper:read')")
	Flux<ServerSentEvent<JobService.JobEventView>> stream(
			@PathVariable UUID id,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
	) {
		return eventStream.stream(id, parseLastEventId(lastEventId));
	}

	@PostMapping("/{id}/pause")
	@PreAuthorize("hasAuthority('job:manage')")
	Mono<JobService.JobView> pause(
			@PathVariable UUID id, Principal principal, ServerWebExchange exchange
	) {
		return control(id, JobAction.PAUSE, principal, exchange);
	}

	@PostMapping("/{id}/resume")
	@PreAuthorize("hasAuthority('job:manage')")
	Mono<JobService.JobView> resume(
			@PathVariable UUID id, Principal principal, ServerWebExchange exchange
	) {
		return control(id, JobAction.RESUME, principal, exchange);
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('job:manage')")
	Mono<JobService.JobView> cancel(
			@PathVariable UUID id, Principal principal, ServerWebExchange exchange
	) {
		return control(id, JobAction.CANCEL, principal, exchange);
	}

	@PostMapping("/{id}/retry")
	@PreAuthorize("hasAuthority('job:manage')")
	Mono<JobService.JobView> retry(
			@PathVariable UUID id, Principal principal, ServerWebExchange exchange
	) {
		return control(id, JobAction.RETRY, principal, exchange);
	}

	private Mono<JobService.JobView> control(
			UUID id, JobAction action, Principal principal, ServerWebExchange exchange
	) {
		return service.control(
				id, action, RequestContextSupport.actorId(principal), RequestContextSupport.context(exchange));
	}

	private long parseLastEventId(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		try {
			long parsed = Long.parseLong(value);
			if (parsed < 0) {
				throw new NumberFormatException("negative");
			}
			return parsed;
		}
		catch (NumberFormatException exception) {
			throw badRequest(new IllegalArgumentException("Last-Event-ID is invalid", exception));
		}
	}

	private ResponseStatusException badRequest(IllegalArgumentException exception) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
	}
}
