package com.camel_hub.advertisement.common.api;

import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
	ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.FORBIDDEN, "access_denied", "Access denied",
				"You do not have permission to perform this operation", Map.of());
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception, ServerWebExchange exchange) {
		HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
		HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
		String detail = exception.getReason() == null ? resolved.getReasonPhrase() : exception.getReason();
		return response(exchange, resolved, "request_rejected", resolved.getReasonPhrase(), detail, Map.of());
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

