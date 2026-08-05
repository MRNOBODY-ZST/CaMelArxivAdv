package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.identity.service.RoleAdministrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoleAdminController {

	private final RoleAdministrationService service;

	public RoleAdminController(RoleAdministrationService service) {
		this.service = service;
	}

	@GetMapping("/roles")
	@PreAuthorize("hasAuthority('role:read')")
	Mono<List<RoleAdministrationService.RoleView>> listRoles() {
		return service.listRoles();
	}

	@GetMapping("/permissions")
	@PreAuthorize("hasAuthority('role:read')")
	Mono<List<RoleAdministrationService.PermissionView>> listPermissions() {
		return service.listPermissions();
	}

	@PostMapping("/roles")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('role:manage')")
	Mono<RoleAdministrationService.RoleView> create(
			@Valid @RequestBody RoleRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.create(
				request.command(), AdminApiSupport.actorId(principal), AdminApiSupport.context(exchange));
	}

	@PutMapping("/roles/{id}")
	@PreAuthorize("hasAuthority('role:manage')")
	Mono<RoleAdministrationService.RoleView> update(
			@PathVariable UUID id,
			@Valid @RequestBody RoleRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.update(
				id, request.command(), AdminApiSupport.actorId(principal), AdminApiSupport.context(exchange));
	}

	@DeleteMapping("/roles/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('role:manage')")
	Mono<Void> delete(
			@PathVariable UUID id,
			Principal principal,
			ServerWebExchange exchange
	) {
		return service.delete(id, AdminApiSupport.actorId(principal), AdminApiSupport.context(exchange));
	}

	public record RoleRequest(
			@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}") String code,
			@NotBlank @Size(max = 100) String name,
			@Size(max = 255) String description,
			@NotEmpty Set<@NotBlank String> permissionCodes
	) {
		RoleAdministrationService.RoleCommand command() {
			return new RoleAdministrationService.RoleCommand(code, name, description, permissionCodes);
		}
	}
}
