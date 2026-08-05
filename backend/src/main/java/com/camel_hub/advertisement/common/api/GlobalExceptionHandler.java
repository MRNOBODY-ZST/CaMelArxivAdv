package com.camel_hub.advertisement.common.api;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.arxiv.client.ArxivDependencyException;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.common.security.ClientAddressResolver;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.camel_hub.advertisement.identity.service.AuthenticationFailedException;
import com.camel_hub.advertisement.identity.service.AdministrationConflictException;
import com.camel_hub.advertisement.identity.service.AdministrationNotFoundException;
import com.camel_hub.advertisement.identity.service.AdministrationValidationException;
import com.camel_hub.advertisement.identity.service.InvalidRefreshTokenException;
import com.camel_hub.advertisement.identity.service.LoginRateLimitedException;
import com.camel_hub.advertisement.identity.service.PasswordPolicyViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
	private final ObjectProvider<AuditService> auditServiceProvider;
	private final ObjectProvider<SensitiveValueHasher> hasherProvider;

	public GlobalExceptionHandler(
			ObjectProvider<AuditService> auditServiceProvider,
			ObjectProvider<SensitiveValueHasher> hasherProvider
	) {
		this.auditServiceProvider = auditServiceProvider;
		this.hasherProvider = hasherProvider;
	}

	@ExceptionHandler(WebExchangeBindException.class)
	ResponseEntity<ApiError> handleValidation(WebExchangeBindException exception, ServerWebExchange exchange) {
		Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
		exception.getFieldErrors().stream()
				.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage() == null
						? "invalid value"
						: error.getDefaultMessage()))
				.forEach(violation -> fieldErrors.merge(
						violation.field(),
						List.of(violation.message()),
						(left, right) -> {
							var merged = new java.util.ArrayList<>(left);
							merged.addAll(right);
							return List.copyOf(merged);
						}));
		return response(
				exchange,
				HttpStatus.BAD_REQUEST,
				"validation_error",
				"Validation failed",
				"Request contains invalid fields",
				fieldErrors);
	}

	@ExceptionHandler(ServerWebInputException.class)
	ResponseEntity<ApiError> handleInvalidInput(ServerWebInputException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_request", "Invalid request",
				"Request body or parameters could not be read", Map.of());
	}

	@ExceptionHandler(AccessDeniedException.class)
	Mono<ResponseEntity<ApiError>> handleAccessDenied(AccessDeniedException exception, ServerWebExchange exchange) {
		ResponseEntity<ApiError> denied = response(
				exchange, HttpStatus.FORBIDDEN, "access_denied", "Access denied",
				"You do not have permission to perform this operation", Map.of());
		return auditAccessDenied(exchange).thenReturn(denied);
	}

	private Mono<Void> auditAccessDenied(ServerWebExchange exchange) {
		if (auditServiceProvider == null || hasherProvider == null) {
			return Mono.empty();
		}
		AuditService auditService = auditServiceProvider.getIfAvailable();
		SensitiveValueHasher hasher = hasherProvider.getIfAvailable();
		if (auditService == null || hasher == null) {
			return Mono.empty();
		}
		return authenticatedUser(exchange)
				.flatMap(user -> {
					String ipAddress = ClientAddressResolver.resolve(exchange.getRequest());
					String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
					String summary = userAgent == null
							? "unknown"
							: userAgent.substring(0, Math.min(255, userAgent.length()));
					String resource = exchange.getRequest().getMethod().name() + " "
							+ exchange.getRequest().getPath().value();
					return auditService.record(new AuditEvent(
							user.id(), "AUTHORIZATION_DENIED", "HTTP_ENDPOINT", resource,
							hasher.hash(ipAddress), summary, TraceIdWebFilter.traceId(exchange),
							Map.of(), Map.of("status", "DENIED"), AuditResult.DENIED, "ACCESS_DENIED"));
				})
				.onErrorResume(auditFailure -> {
					LOGGER.warn("Controller authorization denial audit could not be recorded", auditFailure);
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

	@ExceptionHandler(AuthenticationFailedException.class)
	ResponseEntity<ApiError> handleAuthenticationFailed(
			AuthenticationFailedException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.UNAUTHORIZED, "authentication_failed", "Authentication failed",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(LoginRateLimitedException.class)
	ResponseEntity<ApiError> handleLoginRateLimited(
			LoginRateLimitedException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.TOO_MANY_REQUESTS, "login_rate_limited", "Login rate limited",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(InvalidRefreshTokenException.class)
	ResponseEntity<ApiError> handleInvalidRefreshToken(
			InvalidRefreshTokenException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.UNAUTHORIZED, "invalid_session", "Invalid session",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(PasswordPolicyViolationException.class)
	ResponseEntity<ApiError> handlePasswordPolicyViolation(
			PasswordPolicyViolationException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "password_policy_violation", "Password rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(AdministrationValidationException.class)
	ResponseEntity<ApiError> handleAdministrationValidation(
			AdministrationValidationException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_operation", "Operation rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(AdministrationConflictException.class)
	ResponseEntity<ApiError> handleAdministrationConflict(
			AdministrationConflictException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "resource_conflict", "Resource conflict",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(AdministrationNotFoundException.class)
	ResponseEntity<ApiError> handleAdministrationNotFound(
			AdministrationNotFoundException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "resource_not_found", "Resource not found",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception, ServerWebExchange exchange) {
		HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
		HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
		String detail = exception.getReason() == null ? resolved.getReasonPhrase() : exception.getReason();
		return response(exchange, resolved, "request_rejected", resolved.getReasonPhrase(), detail, Map.of());
	}

	@ExceptionHandler(ArxivDependencyException.class)
	ResponseEntity<ApiError> handleArxivDependency(
			ArxivDependencyException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.SERVICE_UNAVAILABLE,
				"arxiv_unavailable", "arXiv unavailable",
				"The arXiv request could not be completed safely; retry later", Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error",
				"The request could not be completed", Map.of());
	}

	private ResponseEntity<ApiError> response(
			ServerWebExchange exchange,
			HttpStatus status,
			String type,
			String title,
			String detail,
			Map<String, List<String>> fieldErrors
	) {
		String traceId = TraceIdWebFilter.traceId(exchange);
		ApiError error = new ApiError(
				type,
				title,
				status.value(),
				detail,
				exchange.getRequest().getPath().value(),
				traceId,
				fieldErrors);
		return ResponseEntity.status(status).body(error);
	}
}
