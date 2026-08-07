package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpApiTest {

	private SmtpService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(SmtpService.class);
		client = WebTestClient.bindToController(new SmtpController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();
	}

	@Test
	void neverSerializesPasswordCiphertextNonceOrPlaintext() {
		UUID id = UUID.randomUUID();
		when(service.get(id)).thenReturn(Mono.just(new SmtpService.SmtpAccountView(
				id, "Mailpit", "mailpit", 1025, SmtpModels.TlsMode.PLAIN_LOCAL_ONLY, "local-user",
				true, "sender@example.org", "Sender", "reply@example.org", 10, 100, 1_000, 50,
				true, Instant.parse("2026-08-06T10:00:00Z"), "SUCCEEDED", null, 0,
				Instant.parse("2026-08-06T09:00:00Z"), Instant.parse("2026-08-06T10:00:00Z"))));

		client.get().uri("/api/v1/smtp-accounts/{id}", id).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.passwordConfigured").isEqualTo(true)
				.jsonPath("$.password").doesNotExist()
				.jsonPath("$.passwordCiphertext").doesNotExist()
				.jsonPath("$.passwordNonce").doesNotExist();
	}

	@Test
	void declaresReadForGetsAndManageForMutations() {
		Arrays.stream(SmtpController.class.getDeclaredMethods()).forEach(method -> {
			PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
			assertThat(permission).isNotNull();
			if (method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null) {
				assertThat(permission.value()).isEqualTo("hasAuthority('smtp:read')");
			}
			else {
				assertThat(permission.value()).isEqualTo("hasAuthority('smtp:manage')");
			}
		});
	}
}
