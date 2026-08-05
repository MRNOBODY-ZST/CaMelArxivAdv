package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.identity.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnBean(AuthenticationService.class)
public class AuthController {

	private static final int MAXIMUM_USER_AGENT_LENGTH = 255;
	private final AuthenticationService authenticationService;

	public AuthController(AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
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
				.map(result -> new AuthDtos.LoginResponse(
						result.accessToken().value(),
						"Bearer",
						result.accessToken().expiresInSeconds(),
						AuthDtos.CurrentUserResponse.from(result.user())));
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
