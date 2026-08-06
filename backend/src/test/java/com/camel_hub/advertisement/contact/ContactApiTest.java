package com.camel_hub.advertisement.contact;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContactApiTest {

	private ContactService service;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		service = mock(ContactService.class);
		AuthenticatedUser user = new AuthenticatedUser(
				UUID.randomUUID(), "analyst", "Analyst", Set.of("DATA_ANALYST"),
				Set.of("contact:read_masked"), false, 0);
		WebFilter principal = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(user)).build());
		client = WebTestClient.bindToController(new ContactController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null)).webFilter(principal).build();
	}

	@Test
	void exposesPaginatedMaskedContacts() {
		when(service.list(eq(1), eq(20), any(), any())).thenReturn(Mono.just(PageResponse.of(
				List.of(), 1, 20, 0)));

		client.get().uri("/api/v1/contacts?page=1&pageSize=20")
				.exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.total").isEqualTo(0);
	}

	@Test
	void validatesVerificationBody() {
		client.patch().uri("/api/v1/contacts/{id}/verification", UUID.randomUUID())
				.bodyValue(java.util.Map.of("status", "INVALID", "expectedVersion", -1))
				.exchange().expectStatus().isBadRequest();
	}

	@Test
	void appliesFineGrainedContactPermissions() {
		Arrays.stream(ContactController.class.getDeclaredMethods())
				.filter(method -> method.getAnnotation(
						org.springframework.web.bind.annotation.RequestMapping.class) != null
						|| method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null
						|| method.getAnnotation(org.springframework.web.bind.annotation.PatchMapping.class) != null)
				.forEach(method -> {
			PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
			if (method.getName().equals("verify")) {
				assertThat(permission.value()).isEqualTo("hasAuthority('contact:verify')");
			}
			else {
				assertThat(permission.value()).isEqualTo("hasAuthority('contact:read_masked')");
			}
				});
	}
}
