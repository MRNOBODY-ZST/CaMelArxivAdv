package com.camel_hub.advertisement.common.security;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityErrorResponseWriterTest {

	@Test
	@SuppressWarnings("unchecked")
	void auditsAnAuthenticatedAuthorizationDenialWithoutSensitiveRequestData() {
		AuditService auditService = mock(AuditService.class);
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		ObjectProvider<AuditService> auditProvider = mock(ObjectProvider.class);
		ObjectProvider<SensitiveValueHasher> hasherProvider = mock(ObjectProvider.class);
		when(auditProvider.getIfAvailable()).thenReturn(auditService);
		when(hasherProvider.getIfAvailable()).thenReturn(hasher);
		when(hasher.hash("192.0.2.50")).thenReturn(new byte[] {1, 2, 3});
		when(auditService.record(any())).thenReturn(Mono.empty());
		SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(auditProvider, hasherProvider);
		AuthenticatedUser user = new AuthenticatedUser(
				UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8"),
				"viewer", "Viewer", Set.of("VIEWER"), Set.of("paper:read"), false, 1);
		var authentication = UsernamePasswordAuthenticationToken.authenticated(user, "jwt", Set.of());
		ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
				.post("/api/v1/users?secret=must-not-be-audited")
				.remoteAddress(InetSocketAddress.createUnresolved("192.0.2.50", 443))
				.header("User-Agent", "security-test")
				.build()).mutate().principal(Mono.just(authentication)).build();

		StepVerifier.create(writer.accessDenied(exchange)).verifyComplete();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
		verify(auditService).record(event.capture());
		assertThat(event.getValue().action()).isEqualTo("AUTHORIZATION_DENIED");
		assertThat(event.getValue().resourceId()).isEqualTo("POST /api/v1/users");
		assertThat(event.getValue().result()).isEqualTo(AuditResult.DENIED);
		assertThat(event.getValue().afterSummary().toString()).doesNotContain("must-not-be-audited");
	}
}
