package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.AccessTokenService;
import com.camel_hub.advertisement.identity.security.RefreshCookieFactory;
import com.camel_hub.advertisement.identity.service.AuthenticationResult;
import com.camel_hub.advertisement.identity.service.AuthenticationService;
import com.camel_hub.advertisement.identity.service.InvalidRefreshTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshApiTest {

	private AuthenticationService authenticationService;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		authenticationService = mock(AuthenticationService.class);
		RefreshCookieFactory cookieFactory = new RefreshCookieFactory(properties());
		webTestClient = WebTestClient.bindToController(new AuthController(authenticationService, cookieFactory))
				.controllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void loginSetsAnHttpOnlySecureStrictCookieWithoutReturningRefreshTokenInJson() {
		when(authenticationService.login(eq("Admin"), eq("Maple!Orbit92"), any()))
				.thenReturn(Mono.just(result("refresh-login-value")));

		var response = webTestClient.post().uri("/api/v1/auth/login")
				.bodyValue(Map.of("principal", "Admin", "password", "Maple!Orbit92"))
				.exchange()
				.expectStatus().isOk()
				.expectHeader().value(HttpHeaders.SET_COOKIE, value -> {
					assertThat(value).contains("refresh_token=refresh-login-value");
					assertThat(value).contains("Path=/api/v1/auth");
					assertThat(value).contains("Max-Age=1209600");
					assertThat(value).contains("Secure", "HttpOnly", "SameSite=Strict");
				})
				.expectBody()
				.jsonPath("$.accessToken").isEqualTo("signed.jwt.value")
				.jsonPath("$.refreshToken").doesNotExist()
				.returnResult();

		assertThat(response.getResponseBody()).isNotEmpty();
	}

	@Test
	void refreshRotatesTheCookieAndReturnsOnlyANewAccessToken() {
		when(authenticationService.refresh(eq("old-refresh-value"), any()))
				.thenReturn(Mono.just(result("new-refresh-value")));

		webTestClient.post().uri("/api/v1/auth/refresh")
				.cookie("refresh_token", "old-refresh-value")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueMatches(HttpHeaders.SET_COOKIE, ".*refresh_token=new-refresh-value.*")
				.expectBody()
				.jsonPath("$.accessToken").isEqualTo("signed.jwt.value")
				.jsonPath("$.refreshToken").doesNotExist();
	}

	@Test
	void refreshReplayUsesTheSameUnauthorizedSessionResponse() {
		when(authenticationService.refresh(eq("replayed-value"), any()))
				.thenReturn(Mono.error(new InvalidRefreshTokenException()));

		webTestClient.post().uri("/api/v1/auth/refresh")
				.cookie("refresh_token", "replayed-value")
				.exchange()
				.expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.type").isEqualTo("invalid_session")
				.jsonPath("$.detail").isEqualTo("Session is invalid or expired");
	}

	@Test
	void logoutRevokesTheFamilyAndExpiresTheCookie() {
		when(authenticationService.logout(eq("refresh-value"), any())).thenReturn(Mono.empty());

		webTestClient.post().uri("/api/v1/auth/logout")
				.cookie("refresh_token", "refresh-value")
				.exchange()
				.expectStatus().isNoContent()
				.expectHeader().value(HttpHeaders.SET_COOKIE, value -> {
					assertThat(value).contains("refresh_token=");
					assertThat(value).contains("Max-Age=0", "HttpOnly", "Secure", "SameSite=Strict");
				});

		verify(authenticationService).logout(eq("refresh-value"), any());
	}

	private AuthenticationResult result(String refreshToken) {
		AuthenticatedUser user = new AuthenticatedUser(
				UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8"),
				"admin", "Administrator", Set.of("SUPER_ADMIN"), Set.of("system:manage"), false, 1);
		return new AuthenticationResult(
				new AccessTokenService.IssuedAccessToken(
						"signed.jwt.value", Instant.parse("2026-08-05T08:10:00Z"), 600),
				user,
				refreshToken);
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
