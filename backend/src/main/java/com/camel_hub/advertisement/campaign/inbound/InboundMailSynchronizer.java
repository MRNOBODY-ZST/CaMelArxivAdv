package com.camel_hub.advertisement.campaign.inbound;

import com.camel_hub.advertisement.email.mailbox.MailboxTransport;
import com.camel_hub.advertisement.email.mailbox.MailboxTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls healthy IMAP accounts and advances each UID only after durable event persistence. */
public final class InboundMailSynchronizer {
	private static final Logger LOGGER = LoggerFactory.getLogger(InboundMailSynchronizer.class);
	private final InboundMailRepository repository;
	private final MailboxTransport transport;
	private final InboundMailParser parser;
	private final Clock clock;
	private final Duration leaseDuration;
	private final int batchSize;
	private final Scheduler heartbeatScheduler;
	private final AtomicBoolean running = new AtomicBoolean();

	public InboundMailSynchronizer(
			InboundMailRepository repository, MailboxTransport transport, InboundMailParser parser,
			Clock clock, Duration leaseDuration, int batchSize
	) {
		this(repository, transport, parser, clock, leaseDuration, batchSize, Schedulers.parallel());
	}

	InboundMailSynchronizer(
			InboundMailRepository repository, MailboxTransport transport, InboundMailParser parser,
			Clock clock, Duration leaseDuration, int batchSize, Scheduler heartbeatScheduler
	) {
		this.repository = Objects.requireNonNull(repository, "repository");
		this.transport = Objects.requireNonNull(transport, "transport");
		this.parser = Objects.requireNonNull(parser, "parser");
		this.clock = Objects.requireNonNull(clock, "clock");
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("Inbound mailbox lease duration must be positive");
		}
		this.leaseDuration = leaseDuration;
		this.batchSize = Math.max(1, Math.min(batchSize, 50));
		this.heartbeatScheduler = Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
	}

	public Mono<SyncResult> syncOnce(UUID mailboxId) {
		return Mono.defer(() -> repository.claim(mailboxId, clock.instant(), leaseDuration)
				.flatMap(lease -> readWithLeaseHeartbeat(lease)
						.flatMap(read -> persistBatch(lease, read))
						.onErrorResume(error -> repository.fail(
								lease, failureCategory(error), clock.instant())
								.thenReturn(new SyncResult(SyncStatus.FAILED, 0))))
				.switchIfEmpty(Mono.just(new SyncResult(SyncStatus.NO_WORK, 0))));
	}

	private Mono<InboundMailModels.MailboxRead> readWithLeaseHeartbeat(
			InboundMailRepository.CursorLease lease
	) {
		Mono<InboundMailModels.MailboxRead> read = Mono.fromCallable(() -> transport.readSince(
				lease.account(), lease.folderName(), lease.uidValidity(), lease.lastRemoteUid(), batchSize))
				.subscribeOn(Schedulers.boundedElastic());
		Duration interval = leaseDuration.dividedBy(3);
		Mono<InboundMailModels.MailboxRead> leaseLoss = Flux.interval(interval, heartbeatScheduler)
				.concatMap(ignored -> repository.renew(lease, clock.instant(), leaseDuration))
				.then(Mono.never());
		return Mono.firstWithSignal(read, leaseLoss);
	}

	public Mono<Long> syncDueOnce() {
		Instant now = clock.instant();
		return repository.dueMailboxIds(now, 20)
				.concatMap(this::syncOnce)
				.filter(result -> result.status() != SyncStatus.NO_WORK)
				.count();
	}

	private Mono<SyncResult> persistBatch(
			InboundMailRepository.CursorLease lease, InboundMailModels.MailboxRead read
	) {
		Instant now = clock.instant();
		return repository.renew(lease, now, leaseDuration)
				.then(repository.alignUidValidity(lease, read.uidValidity(), read.cursorFloor(), now))
				.flatMap(lastUid -> Flux.fromIterable(read.envelopes())
						.filter(envelope -> envelope.remoteUid() > lastUid)
						.sort(Comparator.comparingLong(InboundMailModels.InboundEnvelope::remoteUid))
						.take(batchSize)
						.concatMap(envelope -> {
							Instant eventNow = clock.instant();
							return repository.renew(lease, eventNow, leaseDuration)
									.then(repository.persist(
											lease, read.uidValidity(), envelope.remoteUid(), parser.classify(envelope),
											envelope.receivedAt(), eventNow));
						})
						.filter(Boolean::booleanValue)
						.count())
				.flatMap(processed -> repository.complete(lease, clock.instant())
						.thenReturn(new SyncResult(SyncStatus.COMPLETED, processed.intValue())));
	}

	private String failureCategory(Throwable error) {
		if (error instanceof MailboxTransportException transportFailure) {
			return transportFailure.category().name();
		}
		return "UNEXPECTED_FAILURE";
	}

	@Scheduled(fixedDelayString = "${app.campaign-inbound.poll-delay:PT30S}")
	void tick() {
		if (!running.compareAndSet(false, true)) return;
		syncDueOnce().doFinally(ignored -> running.set(false)).subscribe(
				ignored -> { }, error -> LOGGER.error(
						"campaign_inbound_poll_failed component=inbound_mail category={}",
						error.getClass().getSimpleName()));
	}

	public enum SyncStatus { NO_WORK, COMPLETED, FAILED }
	public record SyncResult(SyncStatus status, int processed) { }
}
