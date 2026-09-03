package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.campaign.tracking.CampaignCallbackNamespace;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.security.SecurityErrorResponseWriter;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class MailOpenPrivacyTest {
	private static final String CAPABILITY = "capability-must-not-leak-in-errors";

	@Test
	void unexpectedCallbackExceptionsNeverLogTheirUrlOrCause(CapturedOutput output) {
		WebTestClient client = WebTestClient.bindToController(new FailureProbe())
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();
		String body = client.get().uri("/t/o/{token}", CAPABILITY).exchange().expectStatus().is5xxServerError()
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(body).doesNotContain(CAPABILITY);
		assertThat(output.getAll()).doesNotContain(CAPABILITY);
	}

	@Test
	void genericStatusErrorsDoNotEchoACapabilityInTheReasonOrInstance() {
		WebTestClient client = WebTestClient.bindToController(new FailureProbe())
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();
		String body = client.get().uri("/t/c/{token}", CAPABILITY).exchange().expectStatus().isBadRequest()
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(body).doesNotContain(CAPABILITY);
	}

	@Test
	void authenticationErrorsDoNotEchoTheCapabilityPath() {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(
				beans.getBeanProvider(com.camel_hub.advertisement.audit.AuditService.class),
				beans.getBeanProvider(com.camel_hub.advertisement.identity.security.SensitiveValueHasher.class));
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/t/o/" + CAPABILITY));
		writer.authenticationRequired(exchange).block();
		assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain(CAPABILITY);
	}

	@Test
	void unsubscribeCapabilitiesAreRedactedFromRequestContextsAndAuthenticationErrors() {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(
				beans.getBeanProvider(com.camel_hub.advertisement.audit.AuditService.class),
				beans.getBeanProvider(com.camel_hub.advertisement.identity.security.SensitiveValueHasher.class));
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/u/" + CAPABILITY));

		assertThat(com.camel_hub.advertisement.common.api.RequestContextSupport.isCapabilityRequest(exchange)).isTrue();
		assertThat(com.camel_hub.advertisement.common.api.RequestContextSupport.safePath(exchange))
				.isEqualTo("/u/[redacted]");
		writer.authenticationRequired(exchange).block();
		assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain(CAPABILITY);
	}

	@Test
	void unexpectedUnsubscribeExceptionsNeverEchoTheCapabilityInBodyInstanceOrLogs(CapturedOutput output) {
		WebTestClient client = WebTestClient.bindToController(new FailureProbe())
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();

		String body = client.get().uri("/u/{token}", CAPABILITY).exchange().expectStatus().is5xxServerError()
				.expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).doesNotContain(CAPABILITY, "/u/" + CAPABILITY);
		assertThat(output.getAll()).doesNotContain(CAPABILITY, "/u/" + CAPABILITY);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"javascript:alert(1)", "/relative", "https://user:secret@example.test/private",
			"https://example.test/path#fragment", "https://example.test/path%0d%0aInjected",
			"https://example.test:0/path", "https:\\evil.example/path"
	})
	void clickControllerRejectsUnsafeTargetsReturnedByAnyCampaignNamespace(String target) {
		WebTestClient client = WebTestClient.bindToController(
				new MailClickController(disabledTestMail(), namespaceReturning(target))).build();

		client.get().uri("/t/c/opaque-capability").exchange()
				.expectStatus().isNotFound()
				.expectHeader().doesNotExist(HttpHeaders.LOCATION);
	}

	@Test
	void clickControllerAllowsAnAbsoluteSafeTargetReturnedByACampaignNamespace() {
		WebTestClient client = WebTestClient.bindToController(
				new MailClickController(disabledTestMail(),
						namespaceReturning("https://papers.example.test/item?id=42"))).build();

		client.get().uri("/t/c/opaque-capability").exchange()
				.expectStatus().isFound()
				.expectHeader().valueEquals(HttpHeaders.LOCATION, "https://papers.example.test/item?id=42");
	}

	private MailTrackingService disabledTestMail() {
		MailTrackingProperties properties = new MailTrackingProperties(
				false, "http://localhost:8080", null, Duration.ofDays(30));
		return new MailTrackingService(null, properties, null, new MailOpenClassifier(), Clock.systemUTC());
	}

	private CampaignCallbackNamespace namespaceReturning(String target) {
		return new CampaignCallbackNamespace() {
			@Override
			public Mono<Boolean> observeOpen(
					String token, HttpHeaders headers, AuthenticationRequestContext request
			) {
				return Mono.empty();
			}

			@Override
			public Mono<ResolvedClick> click(
					String token, HttpHeaders headers, AuthenticationRequestContext request, boolean observe
			) {
				return Mono.fromSupplier(() -> new ResolvedClick(target));
			}

			@Override
			public Mono<Boolean> unsubscribe(String token, AuthenticationRequestContext request) {
				return Mono.empty();
			}
		};
	}

	@RestController
	static class FailureProbe {
		@GetMapping("/t/o/{token}")
		String fail(@PathVariable String token) {
			throw new IllegalStateException("Callback failed at /t/o/" + token);
		}

		@GetMapping("/t/c/{token}")
		String rejected(@PathVariable String token) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejected path /t/c/" + token);
		}

		@GetMapping("/u/{token}")
		String unsubscribeFailure(@PathVariable String token) {
			throw new IllegalStateException("Unsubscribe failed at /u/" + token);
		}
	}
}
