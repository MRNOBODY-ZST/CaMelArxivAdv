package com.camel_hub.advertisement.audit;

import com.camel_hub.advertisement.common.api.PageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RestController
@Profile("!mail-worker")
@RequestMapping("/api/v1/audit-logs")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditLogController {

	private final AuditQueryService service;

	public AuditLogController(AuditQueryService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('audit:read')")
	Mono<PageResponse<AuditQueryService.AuditLogView>> query(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(required = false) UUID actorId,
			@RequestParam(defaultValue = "") String action,
			@RequestParam(defaultValue = "") String resource,
			@RequestParam(defaultValue = "") String result
	) {
		return service.query(page, pageSize, from, to, actorId, action, resource, result);
	}
}
