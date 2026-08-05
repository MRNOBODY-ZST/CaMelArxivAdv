package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.common.security.ClientAddressResolver;
import com.camel_hub.advertisement.identity.service.AuthenticationFailedException;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

import java.security.Principal;
import java.util.UUID;

final class AdminApiSupport {

	private static final int MAXIMUM_USER_AGENT_LENGTH = 255;

	private AdminApiSupport() {
	}

	static UUID actorId(Principal principal) {
		try {
			return UUID.fromString(principal.getName());
		}
		catch (RuntimeException exception) {
			throw new AuthenticationFailedException();
		}
	}

	static AuthenticationRequestContext context(ServerWebExchange exchange) {
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
