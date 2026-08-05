package com.camel_hub.advertisement.audit;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditLogApiTest {

	private AuditQueryService service;
	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		service = mock(AuditQueryService.class);
		webTestClient = WebTestClient.bindToController(new AuditLogController(service))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.build();
	}

	@Test
	void queriesTraceableAuditSummariesUsingTheStandardPageEnvelope() {
		var item = new AuditQueryService.AuditLogView(
				UUID.randomUUID(), UUID.randomUUID(), "admin", "USER_DISABLED", "USER", "target-id",
				Instant.parse("2026-08-05T08:00:00Z"), "trace-audit-1",
				Map.of("status", "ACTIVE"), Map.of("status", "DISABLED"), AuditResult.SUCCESS, null);
		when(service.query(anyInt(), anyInt(), any(), any(), any(), anyString(), anyString(), anyString()))
				.thenReturn(Mono.just(new PageResponse<>(List.of(item), 1, 20, 1, 1)));

		webTestClient.get().uri("/api/v1/audit-logs?page=1&pageSize=20&action=USER_DISABLED")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.items[0].action").isEqualTo("USER_DISABLED")
				.jsonPath("$.items[0].traceId").isEqualTo("trace-audit-1")
				.jsonPath("$.items[0].password").doesNotExist();
	}

	@Test
	void requiresAuditReadPermission() {
		PreAuthorize annotation = Arrays.stream(AuditLogController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("query"))
				.findFirst().orElseThrow()
				.getAnnotation(PreAuthorize.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.value()).isEqualTo("hasAuthority('audit:read')");
	}
}
