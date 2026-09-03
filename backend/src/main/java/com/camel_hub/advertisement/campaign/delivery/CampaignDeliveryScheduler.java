package com.camel_hub.advertisement.campaign.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polling safety net for lost/early Kafka wakeups and expired delivery leases. */
public final class CampaignDeliveryScheduler {
	private static final Logger LOGGER = LoggerFactory.getLogger(CampaignDeliveryScheduler.class);

	private final CampaignDeliveryRepository repository;
	private final CampaignDeliveryExecutor executor;
	private final CampaignDeliveryProperties properties;
	private final Clock clock;
	private final Consumer<String> failureReporter;
	private final AtomicBoolean running = new AtomicBoolean();

	public CampaignDeliveryScheduler(
			CampaignDeliveryRepository repository, CampaignDeliveryExecutor executor,
			CampaignDeliveryProperties properties, Clock clock
	) {
		this(repository, executor, properties, clock, category ->
				LOGGER.error("campaign_delivery_poll_failed component=delivery_scheduler category={}", category));
	}

	CampaignDeliveryScheduler(
			CampaignDeliveryRepository repository, CampaignDeliveryExecutor executor,
			CampaignDeliveryProperties properties, Clock clock, Consumer<String> failureReporter
	) {
		this.repository = repository;
		this.executor = executor;
		this.properties = properties;
		this.clock = clock;
		this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
	}

	public Mono<SchedulerRun> runOnce() {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			Mono<Integer> maintenance = repository.activateDueCampaigns(now)
					.flatMap(activated -> repository.reconcileCanceledRecipients(now)
							.map(canceled -> activated + canceled))
					.flatMap(total -> repository.reconcileExpiredLeases(now)
							.map(expired -> total + expired))
					.flatMap(total -> repository.reconcileUndeliverable(now)
							.map(ineligible -> total + ineligible));
			Mono<Long> pumped = Flux.range(0, properties.batchSize())
					.concatMap(ignored -> executor.pumpOnce())
					.takeUntil(result -> result == CampaignDeliveryExecutor.PumpResult.NO_WORK)
					.filter(result -> result != CampaignDeliveryExecutor.PumpResult.NO_WORK)
					.count();
			return maintenance.flatMap(reconciled -> pumped.flatMap(sent ->
					repository.reconcileCampaigns(clock.instant())
							.map(completed -> new SchedulerRun(reconciled + completed, sent))));
		});
	}

	@Scheduled(fixedDelayString = "${app.campaign-delivery.poll-delay:PT1S}")
	void tick() {
		if (!running.compareAndSet(false, true)) return;
		runOnce().doFinally(ignored -> running.set(false)).subscribe(
				ignored -> { }, error -> failureReporter.accept(error.getClass().getSimpleName()));
	}

	public record SchedulerRun(int reconciled, long pumped) { }
}
