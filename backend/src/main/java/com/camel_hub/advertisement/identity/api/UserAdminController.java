package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.service.UserAdministrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.Set;
import java.util.UUID;

@RestController
@Profile("!mail-worker")
@RequestMapping("/api/v1/users")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UserAdminController {

	private final UserAdministrationService service;

	public UserAdminController(UserAdministrationService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('user:read')")
	Mono<PageResponse<UserAdministrationService.UserView>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize,
			@RequestParam(defaultValue = "") String search,
			@RequestParam(defaultValue = "") String status
	) {
		return service.list(page, pageSize, search, status);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('user:create')")
	Mono<UserAdministrationService.UserView> create(
			@Valid @RequestBody CreateUserRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.create(
				new UserAdministrationService.CreateUserCommand(
						request.username(), request.email(), request.displayName(),
						request.initialPassword(), request.roleCodes()),
				AdminApiSupport.actorId(principal),
				AdminApiSupport.context(exchange));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('user:update')")
	Mono<UserAdministrationService.UserView> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateUserRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.update(
				id,
				new UserAdministrationService.UpdateUserCommand(
						request.email(), request.displayName(), request.roleCodes()),
				AdminApiSupport.actorId(principal),
				AdminApiSupport.context(exchange));
	}

	@PostMapping("/{id}/disable")
	@PreAuthorize("hasAuthority('user:disable')")
	Mono<UserAdministrationService.UserView> disable(
			@PathVariable UUID id,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.setEnabled(
				id, false, AdminApiSupport.actorId(principal), AdminApiSupport.context(exchange));
	}

	@PostMapping("/{id}/enable")
	@PreAuthorize("hasAuthority('user:disable')")
	Mono<UserAdministrationService.UserView> enable(
			@PathVariable UUID id,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.setEnabled(
				id, true, AdminApiSupport.actorId(principal), AdminApiSupport.context(exchange));
	}

	@PostMapping("/{id}/reset-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('user:update')")
	Mono<Void> resetPassword(
			@PathVariable UUID id,
			@Valid @RequestBody ResetPasswordRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.resetPassword(
				id, request.newPassword(), AdminApiSupport.actorId(principal), AdminApiSupport.context(exchange));
	}

	public record CreateUserRequest(
			@NotBlank @Size(max = 80) String username,
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(max = 120) String displayName,
			@NotBlank String initialPassword,
			@NotEmpty Set<@NotBlank String> roleCodes
	) {
	}

	public record UpdateUserRequest(
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(max = 120) String displayName,
			@NotEmpty Set<@NotBlank String> roleCodes
	) {
	}

	public record ResetPasswordRequest(@NotBlank String newPassword) {
	}
}
