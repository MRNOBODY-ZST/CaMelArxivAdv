package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.common.api.RequestContextSupport;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.web.server.ServerWebExchange;

import java.security.Principal;
import java.util.UUID;

final class AdminApiSupport {

	private AdminApiSupport() {
	}

	static UUID actorId(Principal principal) {
		return RequestContextSupport.actorId(principal);
	}

	static AuthenticationRequestContext context(ServerWebExchange exchange) {
		return RequestContextSupport.context(exchange);
	}
}
