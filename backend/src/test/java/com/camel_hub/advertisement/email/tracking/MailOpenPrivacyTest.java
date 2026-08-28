package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.security.SecurityErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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
	}
}
