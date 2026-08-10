package com.camel_hub.advertisement.job.api;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.job.domain.JobAction;
import com.camel_hub.advertisement.job.domain.JobStatus;
import com.camel_hub.advertisement.job.service.JobService;
import com.camel_hub.advertisement.job.service.JobEventStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobApiTest {

	private static final UUID ACTOR_ID = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");
	private static final UUID JOB_ID = UUID.fromString("ed1e38b7-4465-4360-9974-058d45bf24b0");
	private JobService service;
	private WebTestClient webTestClient;
	private JobEventStream eventStream;

	@BeforeEach
	void setUp() {
		service = mock(JobService.class);
		eventStream = mock(JobEventStream.class);
		var authentication = new UsernamePasswordAuthenticationToken(ACTOR_ID.toString(), "n/a");
		WebFilter principalFilter = (exchange, chain) -> chain.filter(
				exchange.mutate().principal(Mono.just(authentication)).build());
		webTestClient = WebTestClient.bindToController(new JobController(service, eventStream))
				.controllerAdvice(new GlobalExceptionHandler(null, null))
				.webFilter(principalFilter).build();
	}

	@Test
	void listsAndReadsJobsUsingPollingResponses() {
		when(service.list(1, 20, null, null)).thenReturn(Mono.just(
				new PageResponse<>(List.of(job()), 1, 20, 1, 1)));
		when(service.get(JOB_ID)).thenReturn(Mono.just(job()));

		webTestClient.get().uri("/api/v1/jobs").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.items[0].status").isEqualTo("RUNNING");
		webTestClient.get().uri("/api/v1/jobs/{id}", JOB_ID).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.allowedActions[0]").exists();
	}

	@Test
	void invokesAuthorizedControlEndpoints() {
		when(service.control(eq(JOB_ID), any(), eq(ACTOR_ID), any())).thenReturn(Mono.just(job()));

		for (String action : List.of("pause", "resume", "cancel", "retry")) {
			webTestClient.post().uri("/api/v1/jobs/{id}/{action}", JOB_ID, action)
					.exchange().expectStatus().isOk();
		}
	}

	@Test
	void declaresReadAndManagePermissions() {
		assertPermission("list", "hasAuthority('paper:read')");
		assertPermission("get", "hasAuthority('paper:read')");
		assertPermission("events", "hasAuthority('paper:read')");
		assertPermission("stream", "hasAuthority('paper:read')");
		assertPermission("pause", "hasAuthority('job:manage')");
		assertPermission("resume", "hasAuthority('job:manage')");
		assertPermission("cancel", "hasAuthority('job:manage')");
		assertPermission("retry", "hasAuthority('job:manage')");
	}

	private void assertPermission(String methodName, String expected) {
		PreAuthorize annotation = Arrays.stream(JobController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals(methodName)).findFirst().orElseThrow()
				.getAnnotation(PreAuthorize.class);
		assertThat(annotation.value()).isEqualTo(expected);
	}

	private JobService.JobView job() {
		return new JobService.JobView(
				JOB_ID, "ARXIV_IMPORT_METADATA", JobStatus.RUNNING, ACTOR_ID,
				null, null, 0, 100, 10, 8, 1, 1, "FETCHING", 10.0,
				Instant.parse("2026-08-05T00:00:00Z"), null,
				Instant.parse("2026-08-05T00:00:10Z"), Instant.parse("2026-08-05T00:00:00Z"),
				Instant.parse("2026-08-05T00:00:10Z"), false, null,
				Set.of(JobAction.PAUSE, JobAction.CANCEL));
	}
}
