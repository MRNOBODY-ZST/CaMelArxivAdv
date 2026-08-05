package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.identity.security.RefreshCookieFactory;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.identity.service.AuthenticationResult;
import com.camel_hub.advertisement.identity.service.AuthenticationService;
import com.camel_hub.advertisement.identity.service.InvalidRefreshTokenException;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnBean(AuthenticationService.class)
public class AuthController {

	private static final int MAXIMUM_USER_AGENT_LENGTH = 255;
	private final AuthenticationService authenticationService;
	private final RefreshCookieFactory cookieFactory;

	public AuthController(AuthenticationService authenticationService, RefreshCookieFactory cookieFactory) {
		this.authenticationService = authenticationService;
		this.cookieFactory = cookieFactory;
	}

	@PostMapping("/login")
	Mono<AuthDtos.LoginResponse> login(
			@Valid @RequestBody AuthDtos.LoginRequest request,
			ServerWebExchange exchange
	) {
		AuthenticationRequestContext context = new AuthenticationRequestContext(
				clientAddress(exchange),
				userAgent(exchange),
				TraceIdWebFilter.traceId(exchange));
		return authenticationService.login(request.principal(), request.password(), context)
				.doOnNext(result -> exchange.getResponse().addCookie(cookieFactory.issue(result.refreshToken())))
				.map(this::response);
	}

	@PostMapping("/refresh")
	Mono<AuthDtos.LoginResponse> refresh(
			@CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshToken,
			ServerWebExchange exchange
	) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return Mono.error(new InvalidRefreshTokenException());
		}
		return authenticationService.refresh(refreshToken, context(exchange))
				.doOnNext(result -> exchange.getResponse().addCookie(cookieFactory.issue(result.refreshToken())))
				.map(this::response);
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	Mono<Void> logout(
			@CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshToken,
			ServerWebExchange exchange
	) {
		exchange.getResponse().addCookie(cookieFactory.expire());
		return authenticationService.logout(refreshToken == null ? "" : refreshToken, context(exchange));
	}

	@PostMapping("/change-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	Mono<Void> changePassword(
			@Valid @RequestBody AuthDtos.ChangePasswordRequest request,
			Principal principal,
			ServerWebExchange exchange
	) {
		UUID userId;
		try {
			userId = UUID.fromString(principal.getName());
		}
		catch (RuntimeException exception) {
			return Mono.error(new InvalidRefreshTokenException());
		}
		return authenticationService.changePassword(
				userId, request.currentPassword(), request.newPassword(), context(exchange));
	}

	private AuthDtos.LoginResponse response(AuthenticationResult result) {
		return new AuthDtos.LoginResponse(
				result.accessToken().value(),
				"Bearer",
				result.accessToken().expiresInSeconds(),
				AuthDtos.CurrentUserResponse.from(result.user()));
	}

	private AuthenticationRequestContext context(ServerWebExchange exchange) {
		return new AuthenticationRequestContext(
				clientAddress(exchange), userAgent(exchange), TraceIdWebFilter.traceId(exchange));
	}

	private String clientAddress(ServerWebExchange exchange) {
		InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
		return remoteAddress == null || remoteAddress.getAddress() == null
				? "unknown"
				: remoteAddress.getAddress().getHostAddress();
	}

	private String userAgent(ServerWebExchange exchange) {
		String value = exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT);
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		String sanitized = value.replaceAll("[\\p{Cntrl}]", " ").strip();
		return sanitized.length() <= MAXIMUM_USER_AGENT_LENGTH
				? sanitized
				: sanitized.substring(0, MAXIMUM_USER_AGENT_LENGTH);
	}
}
