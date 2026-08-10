package com.camel_hub.advertisement.common.api;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = {
		"app.auth.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.auth.fingerprint-hmac-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.persistence.enabled=false",
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration,"
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
				+ "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@Import(GlobalExceptionHandlerTest.ProbeConfiguration.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private ApplicationContext applicationContext;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
				.apply(springSecurity())
				.build()
				.mutateWith(mockUser())
				.mutateWith(csrf());
	}

	@Test
	void returnsTraceableFieldErrorsForInvalidRequest() {
		webTestClient.post()
				.uri("/api/v1/test/validation")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{}")
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().valueMatches("X-Trace-Id", "[a-f0-9]{32}")
				.expectBody()
				.jsonPath("$.type").isEqualTo("validation_error")
				.jsonPath("$.title").isEqualTo("Validation failed")
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.traceId").value(value -> ((String) value).matches("[a-f0-9]{32}"))
				.jsonPath("$.fieldErrors.name[0]").isEqualTo("must not be blank");
	}

	@Test
	@SuppressWarnings("unchecked")
	void auditsControllerLevelAccessDenialsWithoutQueryOrBodyData() {
		AuditService auditService = mock(AuditService.class);
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		ObjectProvider<AuditService> auditProvider = mock(ObjectProvider.class);
		ObjectProvider<SensitiveValueHasher> hasherProvider = mock(ObjectProvider.class);
		when(auditProvider.getIfAvailable()).thenReturn(auditService);
		when(hasherProvider.getIfAvailable()).thenReturn(hasher);
		when(hasher.hash("192.0.2.51")).thenReturn(new byte[] {4, 5, 6});
		when(auditService.record(any())).thenReturn(Mono.empty());
		GlobalExceptionHandler handler = new GlobalExceptionHandler(auditProvider, hasherProvider);
		AuthenticatedUser user = new AuthenticatedUser(
				UUID.fromString("ffdbbd0a-8990-4f0a-ac7e-b74c3b2fa467"),
				"viewer", "Viewer", Set.of("VIEWER"), Set.of("paper:read"), false, 1);
		var exchange = MockServerWebExchange.from(MockServerHttpRequest
				.post("/api/v1/users?secret=not-audited")
				.remoteAddress(new InetSocketAddress("192.0.2.51", 443))
				.header("User-Agent", "controller-denial-test")
				.build()).mutate().principal(Mono.just(user)).build();

		StepVerifier.create(handler.handleAccessDenied(new AccessDeniedException("denied"), exchange))
				.assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
				.verifyComplete();

		var event = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
		verify(auditService).record(event.capture());
		assertThat(event.getValue().resourceId()).isEqualTo("POST /api/v1/users");
		assertThat(event.getValue().result()).isEqualTo(AuditResult.DENIED);
		assertThat(event.getValue().afterSummary().toString()).doesNotContain("not-audited");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfiguration {

		@Bean
		ValidationProbeController validationProbeController() {
			return new ValidationProbeController();
		}
	}

	@RestController
	static class ValidationProbeController {

		@PostMapping("/api/v1/test/validation")
		@ResponseStatus(HttpStatus.NO_CONTENT)
		void validate(@Valid @RequestBody ValidationRequest request) {
		}
	}

	record ValidationRequest(@NotBlank String name) {
	}
}
