package com.camel_hub.advertisement.contact;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/contacts")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContactController {

	private final ContactService service;

	public ContactController(ContactService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('contact:read_masked')")
	Mono<PageResponse<ContactService.ContactSummary>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize,
			@RequestParam(required = false) String domain,
			@RequestParam(required = false) String confidence,
			@RequestParam(required = false) String verificationStatus,
			@RequestParam(required = false) Boolean corresponding,
			@RequestParam(required = false) UUID paperId,
			Principal principal
	) {
		return service.list(page, pageSize, new ContactService.ContactFilter(
				domain, confidence, verificationStatus, corresponding, paperId), user(principal));
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('contact:read_masked')")
	Mono<ContactService.ContactDetail> get(
			@PathVariable UUID id,
			@RequestParam(defaultValue = "false") boolean full,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.get(id, full, user(principal), RequestContextSupport.context(exchange));
	}

	@PatchMapping("/{id}/verification")
	@PreAuthorize("hasAuthority('contact:verify')")
	Mono<ContactService.ContactDetail> verify(
			@PathVariable UUID id,
			@Valid @RequestBody ContactDtos.VerificationRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.verify(
				id, request.command(), user(principal), RequestContextSupport.context(exchange));
	}

	private AuthenticatedUser user(Principal principal) {
		if (principal instanceof AuthenticatedUser user) {
			return user;
		}
		throw new AccessDeniedException("Authenticated user is required");
	}
}
