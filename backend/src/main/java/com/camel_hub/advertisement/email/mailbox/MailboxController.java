package com.camel_hub.advertisement.email.mailbox;

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
import java.util.List;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/mailbox-accounts")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MailboxController {
	private final MailboxService service;

	public MailboxController(MailboxService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('mailbox:read')")
	Mono<PageResponse<MailboxService.MailboxAccountView>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize
	) {
		return service.list(page, pageSize);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('mailbox:read')")
	Mono<MailboxService.MailboxAccountView> get(@PathVariable UUID id) {
		return service.get(id);
	}

	@GetMapping("/{id}/messages")
	@PreAuthorize("hasAuthority('mailbox:read')")
	Mono<List<MailboxTransport.MessageHeader>> messages(
			@PathVariable UUID id, @RequestParam(defaultValue = "20") int limit
	) {
		return service.preview(id, limit);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('mailbox:manage')")
	Mono<MailboxService.MailboxAccountView> create(
			@Valid @RequestBody MailboxDtos.UpsertRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.create(RequestContextSupport.actorId(principal), request.command(),
				RequestContextSupport.context(exchange));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('mailbox:manage')")
	Mono<MailboxService.MailboxAccountView> update(
			@PathVariable UUID id, @Valid @RequestBody MailboxDtos.UpdateRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.update(RequestContextSupport.actorId(principal), id, request.expectedLockVersion(),
				request.account().command(), RequestContextSupport.context(exchange));
	}

	@PostMapping("/{id}/test-connection")
	@PreAuthorize("hasAuthority('mailbox:manage')")
	Mono<MailboxService.ConnectionTestResult> testConnection(
			@PathVariable UUID id, Principal principal, ServerWebExchange exchange
	) {
		return service.testConnection(RequestContextSupport.actorId(principal), id,
				RequestContextSupport.context(exchange));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('mailbox:manage')")
	Mono<Void> delete(
			@PathVariable UUID id, @RequestParam @Min(0) long expectedLockVersion,
			Principal principal, ServerWebExchange exchange
	) {
		return service.delete(RequestContextSupport.actorId(principal), id, expectedLockVersion,
				RequestContextSupport.context(exchange));
	}
}
