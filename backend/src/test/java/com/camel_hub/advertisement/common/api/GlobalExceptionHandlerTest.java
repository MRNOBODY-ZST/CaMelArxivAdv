package com.camel_hub.advertisement.common.api;

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
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = {
		"app.auth.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.auth.fingerprint-hmac-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
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
