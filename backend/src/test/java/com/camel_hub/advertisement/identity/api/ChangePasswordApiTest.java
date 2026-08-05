package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.security.RefreshCookieFactory;
import com.camel_hub.advertisement.identity.service.AuthenticationFailedException;
import com.camel_hub.advertisement.identity.service.AuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangePasswordApiTest {

	private static final UUID USER_ID = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");
	private AuthenticationService authenticationService;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		authenticationService = mock(AuthenticationService.class);
		var authentication = new UsernamePasswordAuthenticationToken(USER_ID.toString(), "n/a");
		WebFilter principalFilter = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		webTestClient = WebTestClient.bindToController(
						new AuthController(authenticationService, new RefreshCookieFactory(properties())))
				.controllerAdvice(new GlobalExceptionHandler())
				.webFilter(principalFilter)
				.build();
	}

	@Test
	void changesPasswordForTheAuthenticatedUserOnly() {
		when(authenticationService.changePassword(
				eq(USER_ID), eq("Current!Password92"), eq("Maple!Orbit93"), any()))
				.thenReturn(Mono.empty());

		webTestClient.post().uri("/api/v1/auth/change-password")
				.bodyValue(Map.of(
						"currentPassword", "Current!Password92",
						"newPassword", "Maple!Orbit93"))
				.exchange()
				.expectStatus().isNoContent();

		verify(authenticationService).changePassword(
				eq(USER_ID), eq("Current!Password92"), eq("Maple!Orbit93"), any());
	}

	@Test
	void rejectsAnIncorrectCurrentPasswordWithoutExposingAccountDetails() {
		when(authenticationService.changePassword(any(), any(), any(), any()))
				.thenReturn(Mono.error(new AuthenticationFailedException()));

		webTestClient.post().uri("/api/v1/auth/change-password")
				.bodyValue(Map.of(
						"currentPassword", "wrong",
						"newPassword", "Maple!Orbit93"))
				.exchange()
				.expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.type").isEqualTo("authentication_failed");
	}

	private AuthProperties properties() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		return new AuthProperties(
				Duration.ofMinutes(10), Duration.ofDays(14), 5, Duration.ofMinutes(15),
				"camel-arxiv", key, key,
				new AuthProperties.RefreshCookie(true, "Strict", "/api/v1/auth"),
				new AuthProperties.BootstrapAdmin("", "", "", ""));
	}
}
