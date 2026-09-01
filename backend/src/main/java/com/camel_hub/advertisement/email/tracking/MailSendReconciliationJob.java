package com.camel_hub.advertisement.email.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MailSendReconciliationJob {
	private static final Logger LOGGER = LoggerFactory.getLogger(MailSendReconciliationJob.class);
	private final MailTrackingRepository repository;
	private final MailTrackingProperties properties;
	private final Clock clock;
	private final AtomicBoolean running = new AtomicBoolean();

	public MailSendReconciliationJob(
			MailTrackingRepository repository, MailTrackingProperties properties, Clock clock
	) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public Mono<Long> reconcileNow() {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			return repository.reconcileStale(now.minus(properties.staleSendingAfter()), now);
		});
	}

	@EventListener(ApplicationReadyEvent.class)
	void onApplicationReady() {
		reconcile();
	}

	@Scheduled(fixedDelayString = "${app.mail-tracking.reconcile-delay-ms:60000}")
	void reconcile() {
		if (!running.compareAndSet(false, true)) return;
		reconcileNow().doFinally(ignored -> running.set(false)).subscribe(count -> {
			if (count > 0) LOGGER.warn("Reconciled {} stale test mail send record(s) to UNKNOWN", count);
		}, error -> LOGGER.error("Stale test mail send reconciliation unavailable"));
	}
}
