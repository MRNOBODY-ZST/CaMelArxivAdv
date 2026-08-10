package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
@Profile("api")
@RequestMapping("/api/v1/smtp-accounts")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SmtpController {

	private final SmtpService service;

	public SmtpController(SmtpService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('smtp:read')")
	Mono<PageResponse<SmtpService.SmtpAccountView>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize
	) {
		return service.list(page, pageSize);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('smtp:read')")
	Mono<SmtpService.SmtpAccountView> get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('smtp:manage')")
	Mono<SmtpService.SmtpAccountView> create(
			@Valid @RequestBody SmtpDtos.UpsertRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.create(RequestContextSupport.actorId(principal), request.command(),
				RequestContextSupport.context(exchange));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('smtp:manage')")
	Mono<SmtpService.SmtpAccountView> update(
			@PathVariable UUID id, @Valid @RequestBody SmtpDtos.UpdateRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.update(RequestContextSupport.actorId(principal), id, request.expectedLockVersion(),
				request.account().command(), RequestContextSupport.context(exchange));
	}

	@PostMapping("/{id}/test-connection")
	@PreAuthorize("hasAuthority('smtp:manage')")
	Mono<SmtpService.TestResult> testConnection(
			@PathVariable UUID id, Principal principal, ServerWebExchange exchange
	) {
		return service.testConnection(RequestContextSupport.actorId(principal), id,
				RequestContextSupport.context(exchange));
	}

	@PostMapping("/{id}/test-email")
	@PreAuthorize("hasAuthority('smtp:manage')")
	Mono<SmtpService.TestResult> testEmail(
			@PathVariable UUID id, @Valid @RequestBody SmtpDtos.TestEmailRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.sendDiagnostic(RequestContextSupport.actorId(principal), id,
				request.recipient(), request.subject(), request.body(), RequestContextSupport.context(exchange));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('smtp:manage')")
	Mono<Void> delete(
			@PathVariable UUID id, @RequestParam @Min(0) long expectedLockVersion,
			Principal principal, ServerWebExchange exchange
	) {
		return service.delete(RequestContextSupport.actorId(principal), id, expectedLockVersion,
				RequestContextSupport.context(exchange));
	}
}
