package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.email.smtp.SmtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateApiTest {

	private TemplateService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(TemplateService.class);
		client = WebTestClient.bindToController(new TemplateController(service, mock(TemplateMailService.class)))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();
	}

	@Test
	void returnsAValidatedTemplateWithoutInternalPersistenceFields() {
		UUID id = UUID.randomUUID();
		var validation = new TemplateModels.ValidationResult(true, List.of(), List.of(), Set.of("paper_title"));
		when(service.get(id)).thenReturn(Mono.just(new TemplateService.TemplateView(
				id, "Outreach", "Description", TemplateRepository.TemplateStatus.DRAFT,
				2, 1, "Paper {{paper_title}}", "Research Team", "reply@example.org",
				"<p>Hello</p>", "Hello", true, 50, validation,
				Instant.parse("2026-08-06T10:00:00Z"), Instant.parse("2026-08-06T11:00:00Z"),
				Instant.parse("2026-08-06T11:00:00Z"))));

		client.get().uri("/api/v1/templates/{id}", id).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.currentVersion").isEqualTo(2)
				.jsonPath("$.autoGenerateText").isEqualTo(true)
				.jsonPath("$.validation.valid").isEqualTo(true)
				.jsonPath("$.password").doesNotExist();
	}

	@Test
	void declaresReadManageAndCombinedTestSendPermissions() {
		Arrays.stream(TemplateController.class.getDeclaredMethods())
				.filter(method -> method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null)
				.forEach(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
						.isEqualTo("hasAuthority('template:read')"));
		PreAuthorize testSend = Arrays.stream(TemplateController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("testSend")).findFirst().orElseThrow()
				.getAnnotation(PreAuthorize.class);
		assertThat(testSend.value()).contains("template:manage").contains("smtp:manage");
	}
}
