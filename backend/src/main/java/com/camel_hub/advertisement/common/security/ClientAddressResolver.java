package com.camel_hub.advertisement.common.security;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class ClientAddressResolver {

	private ClientAddressResolver() {
	}

	public static String resolve(ServerHttpRequest request) {
		InetSocketAddress remoteAddress = request.getRemoteAddress();
		if (remoteAddress == null) {
			return "unknown";
		}
		if (remoteAddress.getAddress() != null) {
			return remoteAddress.getAddress().getHostAddress();
		}
		try {
			return InetAddress.ofLiteral(remoteAddress.getHostString()).getHostAddress();
		}
		catch (IllegalArgumentException exception) {
			return "unknown";
		}
	}
}
