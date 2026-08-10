package com.camel_hub.advertisement.common.observability;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdWebFilter implements WebFilter {

	public static final String TRACE_HEADER = "X-Trace-Id";
	public static final String TRACE_ATTRIBUTE = TraceIdWebFilter.class.getName() + ".traceId";
	public static final String TRACE_CONTEXT_KEY = "traceId";

	private static final Pattern ACCEPTED_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String traceId = resolveTraceId(exchange);
		exchange.getAttributes().put(TRACE_ATTRIBUTE, traceId);
		exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
		return chain.filter(exchange).contextWrite(context -> context.put(TRACE_CONTEXT_KEY, traceId));
	}

	public static String traceId(ServerWebExchange exchange) {
		Object traceId = exchange.getAttribute(TRACE_ATTRIBUTE);
		return traceId instanceof String value ? value : generateTraceId();
	}

	private static String resolveTraceId(ServerWebExchange exchange) {
		String inbound = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
		return inbound != null && ACCEPTED_TRACE_ID.matcher(inbound).matches() ? inbound : generateTraceId();
	}

	private static String generateTraceId() {
		byte[] bytes = new byte[16];
		SECURE_RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}
}

