package com.camel_hub.advertisement.common.security;

import com.camel_hub.advertisement.common.api.ApiError;
import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public final class SecurityErrorResponseWriter {
	private static final Logger LOGGER = LoggerFactory.getLogger(SecurityErrorResponseWriter.class);

	private final ObjectMapper objectMapper;
	private final ObjectProvider<AuditService> auditServiceProvider;
	private final ObjectProvider<SensitiveValueHasher> hasherProvider;

	public SecurityErrorResponseWriter(
			ObjectProvider<AuditService> auditServiceProvider,
			ObjectProvider<SensitiveValueHasher> hasherProvider
	) {
		this.objectMapper = new ObjectMapper().findAndRegisterModules();
		this.auditServiceProvider = auditServiceProvider;
		this.hasherProvider = hasherProvider;
	}

	public Mono<Void> authenticationRequired(ServerWebExchange exchange) {
		return write(
				exchange,
				HttpStatus.UNAUTHORIZED,
				"authentication_required",
				"Authentication required",
				"A valid Bearer access token is required");
	}

	public Mono<Void> accessDenied(ServerWebExchange exchange) {
		return auditAccessDenied(exchange).then(write(
				exchange,
				HttpStatus.FORBIDDEN,
				"access_denied",
				"Access denied",
				"You do not have permission to perform this operation"));
	}

	private Mono<Void> auditAccessDenied(ServerWebExchange exchange) {
		AuditService auditService = auditServiceProvider.getIfAvailable();
		SensitiveValueHasher hasher = hasherProvider.getIfAvailable();
		if (auditService == null || hasher == null) {
			return Mono.empty();
		}
		return authenticatedUser(exchange)
				.flatMap(user -> {
					String ipAddress = ClientAddressResolver.resolve(exchange.getRequest());
					String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
					String summary = userAgent == null ? "unknown" : userAgent.substring(0, Math.min(255, userAgent.length()));
					String resource = exchange.getRequest().getMethod().name() + " "
							+ exchange.getRequest().getPath().value();
					return auditService.record(new AuditEvent(
							user.id(), "AUTHORIZATION_DENIED", "HTTP_ENDPOINT", resource,
							hasher.hash(ipAddress), summary, TraceIdWebFilter.traceId(exchange),
							Map.of(), Map.of("status", "DENIED"), AuditResult.DENIED, "ACCESS_DENIED"));
				})
				.onErrorResume(exception -> {
					LOGGER.warn("Authorization denial audit could not be recorded", exception);
					return Mono.empty();
				});
	}

	private Mono<AuthenticatedUser> authenticatedUser(ServerWebExchange exchange) {
		Mono<AuthenticatedUser> exchangeUser = exchange.getPrincipal()
				.flatMap(principal -> asAuthenticatedUser(principal));
		Mono<AuthenticatedUser> contextUser = ReactiveSecurityContextHolder.getContext()
				.map(context -> context.getAuthentication())
				.flatMap(this::asAuthenticatedUser);
		return exchangeUser.switchIfEmpty(contextUser);
	}

	private Mono<AuthenticatedUser> asAuthenticatedUser(Object principal) {
		Object candidate = principal instanceof Authentication authentication
				? authentication.getPrincipal()
				: principal;
		return candidate instanceof AuthenticatedUser user ? Mono.just(user) : Mono.empty();
	}

	private Mono<Void> write(
			ServerWebExchange exchange,
			HttpStatus status,
			String type,
			String title,
			String detail
	) {
		ApiError error = new ApiError(
				type,
				title,
				status.value(),
				detail,
				exchange.getRequest().getPath().value(),
				TraceIdWebFilter.traceId(exchange),
				Map.of());
		try {
			byte[] content = objectMapper.writeValueAsBytes(error);
			exchange.getResponse().setStatusCode(status);
			exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
			return exchange.getResponse().writeWith(Mono.just(
					exchange.getResponse().bufferFactory().wrap(content)));
		}
		catch (JsonProcessingException exception) {
			return Mono.error(exception);
		}
	}
}
