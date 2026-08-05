package com.camel_hub.advertisement.common.security;

import com.camel_hub.advertisement.common.api.ApiError;
import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public final class SecurityErrorResponseWriter {

	private final ObjectMapper objectMapper;

	public SecurityErrorResponseWriter() {
		this.objectMapper = new ObjectMapper().findAndRegisterModules();
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
		return write(
				exchange,
				HttpStatus.FORBIDDEN,
				"access_denied",
				"Access denied",
				"You do not have permission to perform this operation");
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
