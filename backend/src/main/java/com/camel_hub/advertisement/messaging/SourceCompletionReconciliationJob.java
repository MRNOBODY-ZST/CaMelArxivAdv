package com.camel_hub.advertisement.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SourceCompletionReconciliationJob {

	private static final Logger LOGGER = LoggerFactory.getLogger(SourceCompletionReconciliationJob.class);
	private final ArxivResultRepository repository;
	private final ArxivMessagingProperties properties;
	private final Clock clock;
	private final AtomicBoolean running = new AtomicBoolean();

	public SourceCompletionReconciliationJob(
			ArxivResultRepository repository,
			ArxivMessagingProperties properties,
			Clock clock
	) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public Mono<Long> reconcileNow() {
		return Mono.defer(() -> {
			Instant completedAt = clock.instant();
			return repository.reconcileStaleDeferredSourceCompletions(
					completedAt.minus(properties.sourceCompletionGrace()), completedAt);
		});
	}

	@EventListener(ApplicationReadyEvent.class)
	void onApplicationReady() {
		reconcile();
	}

	@Scheduled(fixedDelayString = "${app.messaging.source-completion-reconcile-delay-ms:60000}")
	void reconcile() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		reconcileNow().doFinally(ignored -> running.set(false)).subscribe(count -> {
			if (count > 0) {
				LOGGER.warn("Failed {} stale Source extraction completion(s) with missing item results", count);
			}
		}, error -> LOGGER.error("Stale Source extraction completion reconciliation unavailable"));
	}
}
