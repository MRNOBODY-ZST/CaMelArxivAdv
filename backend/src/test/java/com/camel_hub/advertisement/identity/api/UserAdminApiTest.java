package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import com.camel_hub.advertisement.identity.service.UserAdministrationService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminApiTest {

	private static final UUID ACTOR_ID = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");
	private static final UUID USER_ID = UUID.fromString("e06e3a77-1c86-4d55-a99d-53118e9a2d97");
	private UserAdministrationService service;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		service = mock(UserAdministrationService.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR_ID.toString(), "n/a");
		WebFilter principalFilter = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		webTestClient = WebTestClient.bindToController(new UserAdminController(service))
				.controllerAdvice(new GlobalExceptionHandler())
				.webFilter(principalFilter)
				.build();
	}

	@Test
	void listsUsersWithTheStandardPaginationEnvelope() {
		when(service.list(anyInt(), anyInt(), anyString(), anyString()))
				.thenReturn(Mono.just(new PageResponse<>(List.of(user()), 1, 20, 1, 1)));

		webTestClient.get().uri("/api/v1/users?page=1&pageSize=20")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.items[0].username").isEqualTo("analyst")
				.jsonPath("$.items[0].passwordHash").doesNotExist()
				.jsonPath("$.page").isEqualTo(1)
				.jsonPath("$.totalPages").isEqualTo(1);
	}

	@Test
	void createsDisablesAndResetsUsersWithoutReturningSecrets() {
		when(service.create(any(), eq(ACTOR_ID), any())).thenReturn(Mono.just(user()));
		when(service.setEnabled(eq(USER_ID), eq(false), eq(ACTOR_ID), any())).thenReturn(Mono.just(user()));
		when(service.resetPassword(eq(USER_ID), eq("Maple!Orbit93"), eq(ACTOR_ID), any()))
				.thenReturn(Mono.empty());

		webTestClient.post().uri("/api/v1/users")
				.bodyValue(Map.of(
						"username", "analyst",
						"email", "analyst@example.edu",
						"displayName", "Data Analyst",
						"initialPassword", "Maple!Orbit92",
						"roleCodes", List.of("DATA_ANALYST")))
				.exchange().expectStatus().isCreated()
				.expectBody().jsonPath("$.passwordHash").doesNotExist();
		webTestClient.post().uri("/api/v1/users/{id}/disable", USER_ID)
				.exchange().expectStatus().isOk();
		webTestClient.post().uri("/api/v1/users/{id}/reset-password", USER_ID)
				.bodyValue(Map.of("newPassword", "Maple!Orbit93"))
				.exchange().expectStatus().isNoContent();

		verify(service).resetPassword(eq(USER_ID), eq("Maple!Orbit93"), eq(ACTOR_ID), any());
	}

	@Test
	void declaresTheExactBackendPermissionForEveryUserOperation() {
		assertPermission("list", "hasAuthority('user:read')");
		assertPermission("create", "hasAuthority('user:create')");
		assertPermission("update", "hasAuthority('user:update')");
		assertPermission("disable", "hasAuthority('user:disable')");
		assertPermission("enable", "hasAuthority('user:disable')");
		assertPermission("resetPassword", "hasAuthority('user:update')");
	}

	private void assertPermission(String methodName, String expected) {
		PreAuthorize annotation = Arrays.stream(UserAdminController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals(methodName))
				.findFirst().orElseThrow()
				.getAnnotation(PreAuthorize.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.value()).isEqualTo(expected);
	}

	private UserAdministrationService.UserView user() {
		return new UserAdministrationService.UserView(
				USER_ID, "analyst", "analyst@example.edu", "Data Analyst", UserStatus.ACTIVE,
				true, 1, null, Instant.parse("2026-08-05T08:00:00Z"), Set.of("DATA_ANALYST"));
	}
}
