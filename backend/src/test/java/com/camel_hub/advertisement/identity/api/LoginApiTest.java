package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.RefreshCookieFactory;
import com.camel_hub.advertisement.identity.service.AuthenticationFailedException;
import com.camel_hub.advertisement.identity.service.AuthenticationResult;
import com.camel_hub.advertisement.identity.service.AuthenticationService;
import com.camel_hub.advertisement.identity.service.LoginRateLimitedException;
import com.camel_hub.advertisement.identity.security.AccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginApiTest {

	private AuthenticationService authenticationService;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		authenticationService = mock(AuthenticationService.class);
		webTestClient = WebTestClient.bindToController(
						new AuthController(authenticationService, new RefreshCookieFactory(properties())))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.build();
	}

	@Test
	void returnsAShortLivedAccessTokenAndCurrentUserShape() {
		AuthenticatedUser user = new AuthenticatedUser(
				UUID.randomUUID(), "admin", "Administrator",
				Set.of("SUPER_ADMIN"), Set.of("user:read", "system:manage"), true, 0);
		when(authenticationService.login(eq("Admin"), eq("Maple!Orbit92"), any()))
				.thenReturn(Mono.just(new AuthenticationResult(
						new AccessTokenService.IssuedAccessToken(
								"signed.jwt.value", Instant.now().plusSeconds(600), 600),
						user,
						"refresh-value")));

		webTestClient.post().uri("/api/v1/auth/login")
				.header("User-Agent", "Browser/1.0")
				.bodyValue(Maps.login("Admin", "Maple!Orbit92"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.accessToken").isEqualTo("signed.jwt.value")
				.jsonPath("$.tokenType").isEqualTo("Bearer")
				.jsonPath("$.expiresInSeconds").isEqualTo(600)
				.jsonPath("$.user.username").isEqualTo("admin")
				.jsonPath("$.user.mustChangePassword").isEqualTo(true)
				.jsonPath("$.user.passwordHash").doesNotExist();
	}

	private AuthProperties properties() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		return new AuthProperties(
				Duration.ofMinutes(10), Duration.ofDays(14), 5, Duration.ofMinutes(15),
				"camel-arxiv", key, key,
				new AuthProperties.RefreshCookie(true, "Strict", "/api/v1/auth"),
				new AuthProperties.BootstrapAdmin("", "", "", ""));
	}

	@Test
	void usesTheSameUnauthorizedResponseForUnknownAndWrongCredentials() {
		when(authenticationService.login(any(), any(), any()))
				.thenReturn(Mono.error(new AuthenticationFailedException()));

		webTestClient.post().uri("/api/v1/auth/login")
				.bodyValue(Maps.login("unknown", "wrong-password"))
				.exchange()
				.expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.type").isEqualTo("authentication_failed")
				.jsonPath("$.detail").isEqualTo("Invalid username/email or password");
	}

	@Test
	void reportsRateLimitingWithoutEchoingThePrincipal() {
		when(authenticationService.login(any(), any(), any()))
				.thenReturn(Mono.error(new LoginRateLimitedException()));

		webTestClient.post().uri("/api/v1/auth/login")
				.bodyValue(Maps.login("admin@example.invalid", "wrong-password"))
				.exchange()
				.expectStatus().isEqualTo(429)
				.expectBody()
				.jsonPath("$.type").isEqualTo("login_rate_limited")
				.jsonPath("$.detail").isEqualTo("Too many login attempts; try again later");
	}

	private static final class Maps {
		private Maps() {
		}

		static java.util.Map<String, String> login(String principal, String password) {
			return java.util.Map.of("principal", principal, "password", password);
		}
	}
}
