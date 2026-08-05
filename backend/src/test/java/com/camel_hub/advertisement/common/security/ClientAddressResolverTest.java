package com.camel_hub.advertisement.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressResolverTest {

	@Test
	void resolvesFrameworkTransformedForwardedAddressesAsCanonicalIpLiterals() {
		ForwardedHeaderTransformer transformer = new ForwardedHeaderTransformer();
		var first = transformer.apply(MockServerHttpRequest.get("/api/v1/auth/login")
				.remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
				.header("X-Forwarded-For", "198.51.100.10")
				.build());
		var second = transformer.apply(MockServerHttpRequest.get("/api/v1/auth/login")
				.remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
				.header("X-Forwarded-For", "198.51.100.11")
				.build());

		assertThat(first.getRemoteAddress()).isNotNull();
		assertThat(first.getRemoteAddress().isUnresolved()).isTrue();
		assertThat(ClientAddressResolver.resolve(first)).isEqualTo("198.51.100.10");
		assertThat(ClientAddressResolver.resolve(second)).isEqualTo("198.51.100.11");
	}

	@Test
	void rejectsNonLiteralUnresolvedHostNames() {
		var request = MockServerHttpRequest.get("/")
				.remoteAddress(InetSocketAddress.createUnresolved("untrusted.example", 443))
				.build();

		assertThat(ClientAddressResolver.resolve(request)).isEqualTo("unknown");
	}
}
