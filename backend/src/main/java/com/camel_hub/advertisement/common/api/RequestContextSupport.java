package com.camel_hub.advertisement.common.api;

import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.common.security.ClientAddressResolver;
import com.camel_hub.advertisement.identity.service.AuthenticationFailedException;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

import java.security.Principal;
import java.util.UUID;

public final class RequestContextSupport {

	private static final int MAXIMUM_USER_AGENT_LENGTH = 255;

	private RequestContextSupport() {
	}

	public static UUID actorId(Principal principal) {
		try {
			return UUID.fromString(principal.getName());
		}
		catch (RuntimeException exception) {
			throw new AuthenticationFailedException();
		}
	}

	public static boolean isCapabilityRequest(ServerWebExchange exchange) {
		String path = exchange.getRequest().getPath().pathWithinApplication().value();
		return path.equals("/t") || path.startsWith("/t/");
	}

	public static String safePath(ServerWebExchange exchange) {
		return isCapabilityRequest(exchange) ? "/t/[redacted]" : exchange.getRequest().getPath().value();
	}

	public static AuthenticationRequestContext context(ServerWebExchange exchange) {
		String ipAddress = ClientAddressResolver.resolve(exchange.getRequest());
		String rawUserAgent = exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT);
		String userAgent = rawUserAgent == null || rawUserAgent.isBlank()
				? "unknown"
				: rawUserAgent.replaceAll("[\\p{Cntrl}]", " ").strip();
		if (userAgent.length() > MAXIMUM_USER_AGENT_LENGTH) {
			userAgent = userAgent.substring(0, MAXIMUM_USER_AGENT_LENGTH);
		}
		return new AuthenticationRequestContext(ipAddress, userAgent, TraceIdWebFilter.traceId(exchange));
	}
}
