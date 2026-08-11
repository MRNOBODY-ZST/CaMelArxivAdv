package com.camel_hub.advertisement.email.mailbox;

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

class MailboxApiTest {

	private MailboxService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(MailboxService.class);
		client = WebTestClient.bindToController(new MailboxController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();
	}

	@Test
	void neverSerializesMailboxSecrets() {
		UUID id = UUID.randomUUID();
		when(service.get(id)).thenReturn(Mono.just(new MailboxService.MailboxAccountView(
				id, "Local IMAP", MailboxModels.Protocol.IMAP, "mail-test", 3143,
				MailboxModels.TlsMode.PLAIN_LOCAL_ONLY, "researcher@example.org", true, "INBOX", true,
				Instant.parse("2026-08-11T10:00:00Z"), "SUCCEEDED", null, 0,
				Instant.parse("2026-08-11T09:00:00Z"), Instant.parse("2026-08-11T10:00:00Z"))));

		client.get().uri("/api/v1/mailbox-accounts/{id}", id).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.passwordConfigured").isEqualTo(true)
				.jsonPath("$.password").doesNotExist()
				.jsonPath("$.passwordCiphertext").doesNotExist()
				.jsonPath("$.passwordNonce").doesNotExist();
	}

	@Test
	void declaresReadForGetsAndManageForMutations() {
		Arrays.stream(MailboxController.class.getDeclaredMethods()).forEach(method -> {
			PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
			assertThat(permission).isNotNull();
			if (method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null) {
				assertThat(permission.value()).isEqualTo("hasAuthority('mailbox:read')");
			}
			else {
				assertThat(permission.value()).isEqualTo("hasAuthority('mailbox:manage')");
			}
		});
	}
}
