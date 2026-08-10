package com.camel_hub.advertisement.job.service;

import com.camel_hub.advertisement.job.domain.JobStatus;
import com.camel_hub.advertisement.job.persistence.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobEventStreamTest {

	private static final UUID JOB_ID = UUID.fromString("ed1e38b7-4465-4360-9974-058d45bf24b0");

	@Test
	void replaysAfterTheLastEventIdAndCompletesForATerminalJob() {
		JobRepository repository = mock(JobRepository.class);
		when(repository.find(JOB_ID)).thenReturn(Mono.just(job(JobStatus.FAILED)));
		when(repository.events(JOB_ID, 5, 100)).thenReturn(Flux.just(event(6, "JOB_FAILED")));
		when(repository.isTerminal(JOB_ID)).thenReturn(Mono.just(true));
		JobEventStream stream = new JobEventStream(repository, Duration.ofMillis(5));

		StepVerifier.create(stream.stream(JOB_ID, 5))
				.assertNext(event -> {
					org.assertj.core.api.Assertions.assertThat(event.id()).isEqualTo("6");
					org.assertj.core.api.Assertions.assertThat(event.data().eventType())
							.isEqualTo("JOB_FAILED");
				})
				.verifyComplete();
	}

	@Test
	void emitsHeartbeatCommentsWhileAnActiveJobHasNoNewEvents() {
		JobRepository repository = mock(JobRepository.class);
		when(repository.find(JOB_ID)).thenReturn(Mono.just(job(JobStatus.RUNNING)));
		when(repository.events(JOB_ID, 0, 100)).thenReturn(Flux.empty());
		when(repository.isTerminal(JOB_ID)).thenReturn(Mono.just(false));
		JobEventStream stream = new JobEventStream(repository, Duration.ofMillis(5));

		Mono<ServerSentEvent<JobService.JobEventView>> first = stream.stream(JOB_ID, 0).next();

		StepVerifier.create(first)
				.assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.comment())
						.isEqualTo("keep-alive"))
				.verifyComplete();
	}

	private JobRepository.JobRecord job(JobStatus status) {
		Instant now = Instant.parse("2026-08-05T00:00:00Z");
		return new JobRepository.JobRecord(
				JOB_ID, "ARXIV_IMPORT_METADATA", status, UUID.randomUUID(), null, null,
				0, "job:test", 1, 1, 0, 0, 1, "FAILED", 100,
				now, status.isTerminal() ? now : null, now, now, now, "failed");
	}

	private JobRepository.JobEventRecord event(long id, String type) {
		return new JobRepository.JobEventRecord(
				id, JOB_ID, type, "FAILED", "failed", "{}",
				Instant.parse("2026-08-05T00:00:00Z"));
	}
}
