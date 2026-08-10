package com.camel_hub.advertisement.job.service;

import com.camel_hub.advertisement.job.persistence.JobRepository;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class JobEventStream {

	private final JobRepository repository;
	private final Duration pollInterval;

	public JobEventStream(JobRepository repository, Duration pollInterval) {
		this.repository = repository;
		this.pollInterval = pollInterval;
	}

	public Flux<ServerSentEvent<JobService.JobEventView>> stream(UUID jobId, long lastEventId) {
		if (lastEventId < 0) {
			return Flux.error(new IllegalArgumentException("Last-Event-ID must not be negative"));
		}
		return repository.find(jobId)
				.switchIfEmpty(Mono.error(new JobNotFoundException()))
				.thenMany(Flux.defer(() -> poll(jobId, lastEventId)));
	}

	private Flux<ServerSentEvent<JobService.JobEventView>> poll(UUID jobId, long lastEventId) {
		AtomicLong cursor = new AtomicLong(lastEventId);
		return Flux.interval(Duration.ZERO, pollInterval)
				.concatMap(ignored -> repository.events(jobId, cursor.get(), 100).collectList()
						.flatMap(events -> {
							if (!events.isEmpty()) {
								cursor.set(events.getLast().id());
							}
							return repository.isTerminal(jobId)
									.map(terminal -> new PollResult(events, terminal));
						}))
				.takeUntil(PollResult::terminal)
				.concatMap(result -> {
					if (result.events().isEmpty()) {
						return result.terminal()
								? Flux.empty()
								: Flux.just(ServerSentEvent.<JobService.JobEventView>builder()
										.comment("keep-alive").build());
					}
					return Flux.fromIterable(result.events()).map(this::serverEvent);
				});
	}

	private ServerSentEvent<JobService.JobEventView> serverEvent(JobRepository.JobEventRecord event) {
		JobService.JobEventView data = new JobService.JobEventView(
				event.id(), event.eventType(), event.stage(), event.message(),
				event.details(), event.occurredAt());
		return ServerSentEvent.<JobService.JobEventView>builder(data)
				.id(Long.toString(event.id()))
				.event("job-event")
				.build();
	}

	private record PollResult(List<JobRepository.JobEventRecord> events, boolean terminal) {
	}
}
