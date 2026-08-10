package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.identity.service.RoleAdministrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleAdminApiTest {

	private static final UUID ACTOR_ID = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");
	private static final UUID ROLE_ID = UUID.fromString("e06e3a77-1c86-4d55-a99d-53118e9a2d97");
	private RoleAdministrationService service;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		service = mock(RoleAdministrationService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR_ID.toString(), "n/a");
		WebFilter principalFilter = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		webTestClient = WebTestClient.bindToController(new RoleAdminController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.webFilter(principalFilter)
				.build();
	}

	@Test
	void listsRolesAndPermissionCatalogWithoutInternalHashes() {
		when(service.listRoles()).thenReturn(Mono.just(List.of(role())));
		when(service.listPermissions()).thenReturn(Mono.just(List.of(
				new RoleAdministrationService.PermissionView(
						UUID.randomUUID(), "user:read", "Read users", Instant.now()))));

		webTestClient.get().uri("/api/v1/roles").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$[0].code").isEqualTo("RESEARCH_ADMIN");
		webTestClient.get().uri("/api/v1/permissions").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$[0].code").isEqualTo("user:read");
	}

	@Test
	void createsAndUpdatesCustomRolesUsingKnownPermissionCodes() {
		when(service.create(any(), eq(ACTOR_ID), any())).thenReturn(Mono.just(role()));
		when(service.update(eq(ROLE_ID), any(), eq(ACTOR_ID), any())).thenReturn(Mono.just(role()));

		Map<String, Object> body = Map.of(
				"code", "RESEARCH_ADMIN", "name", "Research Admin",
				"description", "Research operations", "permissionCodes", Set.of("user:read"));
		webTestClient.post().uri("/api/v1/roles").bodyValue(body)
				.exchange().expectStatus().isCreated();
		webTestClient.put().uri("/api/v1/roles/{id}", ROLE_ID).bodyValue(body)
				.exchange().expectStatus().isOk();
	}

	@Test
	void declaresReadAndManagePermissionsOnEveryRoleEndpoint() {
		assertPermission("listRoles", "hasAuthority('role:read')");
		assertPermission("listPermissions", "hasAuthority('role:read')");
		assertPermission("create", "hasAuthority('role:manage')");
		assertPermission("update", "hasAuthority('role:manage')");
		assertPermission("delete", "hasAuthority('role:manage')");
	}

	private void assertPermission(String methodName, String expected) {
		PreAuthorize annotation = Arrays.stream(RoleAdminController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals(methodName))
				.findFirst().orElseThrow()
				.getAnnotation(PreAuthorize.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.value()).isEqualTo(expected);
	}

	private RoleAdministrationService.RoleView role() {
		return new RoleAdministrationService.RoleView(
				ROLE_ID, "RESEARCH_ADMIN", "Research Admin", "Research operations", false,
				2, Set.of("user:read"), Instant.now());
	}
}
