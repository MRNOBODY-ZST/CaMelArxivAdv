package com.camel_hub.advertisement.system;

import com.camel_hub.advertisement.campaign.PersonalizationProperties;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStatusControllerTest {

	@Test
	void exposesOnlyNonSecretReadinessInformation() {
		var controller = new RuntimeStatusController(
				new PersonalizationProperties(false, "openai", "gpt-test", 100),
				new RuntimeStatusProperties(true, true, false, true));
		WebTestClient client = WebTestClient.bindToController(controller)
				.controllerAdvice(new GlobalExceptionHandler(null, null)).build();

		client.get().uri("/api/v1/system/runtime").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.personalizationEnabled").isEqualTo(false)
				.jsonPath("$.provider").isEqualTo("openai")
				.jsonPath("$.model").isEqualTo("gpt-test")
				.jsonPath("$.rayConfigured").isEqualTo(true)
				.jsonPath("$.kafkaConfigured").isEqualTo(true)
				.jsonPath("$.liveSmtpAllowed").isEqualTo(false)
				.jsonPath("$.publicMailboxAllowed").isEqualTo(true)
				.jsonPath("$.apiKey").doesNotExist()
				.jsonPath("$.password").doesNotExist()
				.jsonPath("$.secret").doesNotExist();
		PreAuthorize permission = java.util.Arrays.stream(RuntimeStatusController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("status")).findFirst().orElseThrow()
				.getAnnotation(PreAuthorize.class);
		assertThat(permission.value()).isEqualTo("hasAuthority('system:manage')");
	}

	@Test
	void returnsAReactiveTypeForMethodSecurity() throws NoSuchMethodException {
		assertThat(RuntimeStatusController.class.getDeclaredMethod("status").getReturnType())
				.isEqualTo(Mono.class);
	}
}
