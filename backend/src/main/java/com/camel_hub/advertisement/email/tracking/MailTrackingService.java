package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Consumer;

import static com.camel_hub.advertisement.email.tracking.MailTrackingModels.*;

public final class MailTrackingService {
	private static final Logger LOGGER = LoggerFactory.getLogger(MailTrackingService.class);
	private static final Duration CALLBACK_RESOLUTION_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration CALLBACK_OBSERVATION_TIMEOUT = Duration.ofMillis(250);
	private final MailTrackingRepository repository;
	private final MailTrackingProperties properties;
	private final MailTrackingSigner signer;
	private final MailOpenClassifier classifier;
	private final MailLinkRewriter linkRewriter;
	private final Clock clock;
	private final TransactionalOperator transactions;

	public MailTrackingService(
			MailTrackingRepository repository, MailTrackingProperties properties, MailTrackingSigner signer,
			MailOpenClassifier classifier, Clock clock
	) {
		this(repository, properties, signer, classifier, clock, null);
	}

	public MailTrackingService(
			MailTrackingRepository repository, MailTrackingProperties properties, MailTrackingSigner signer,
			MailOpenClassifier classifier, Clock clock, TransactionalOperator transactions
	) {
		this.repository = repository;
		this.properties = properties;
		this.signer = signer;
		this.classifier = classifier;
		this.linkRewriter = signer == null ? null : new MailLinkRewriter(signer, properties.publicBaseUrl());
		this.clock = clock;
		this.transactions = transactions;
	}

	public TrackingStatus status() {
		return new TrackingStatus(properties.enabled(), properties.publicBaseUrl(), properties.callbackScope(),
				properties.tokenTtl().getSeconds());
	}

	public Mono<PageResponse<MailSendRecord>> list(int page, int pageSize) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			return Mono.error(new MailTrackingValidationException("Mail send record page is invalid"));
		}
		return Mono.zip(repository.list((page - 1) * pageSize, pageSize).collectList(), repository.count())
				.map(values -> PageResponse.of(values.getT1(), page, pageSize, values.getT2()));
	}

	public Mono<Detail> detail(UUID id) {
		if (id == null) return Mono.error(new MailTrackingValidationException("Mail send record ID is required"));
		return repository.find(id).switchIfEmpty(Mono.error(new MailTrackingNotFoundException()))
				.flatMap(record -> Mono.zip(repository.latestEvents(id).collectList(), repository.latestLinks(id).collectList(),
						repository.latestClickEvents(id).collectList())
						.map(values -> new Detail(record, values.getT1(), values.getT2(), values.getT3())));
	}

	public Mono<Void> send(
			UUID actorId, UUID accountId, Source source, SmtpTransport.OutboundMessage message, boolean trackOpens,
			Consumer<SmtpTransport.OutboundMessage> smtpAttempt
	) {
		return Mono.defer(() -> {
			if (trackOpens && !properties.enabled()) {
				return Mono.error(new MailTrackingValidationException("Open tracking is disabled; send without tracking or enable it first"));
			}
			UUID id = UUID.fromString(message.correlationId());
			Instant createdAt = clock.instant();
			Instant expiresAt = trackOpens ? createdAt.plus(properties.tokenTtl()).truncatedTo(ChronoUnit.SECONDS) : null;
			String token = trackOpens ? signer.issue(id, expiresAt) : null;
			MailLinkRewriter.RewriteResult rewrite = trackOpens ? linkRewriter.rewrite(message.html(), id, expiresAt)
					: new MailLinkRewriter.RewriteResult(message.html(), java.util.List.of());
			SmtpTransport.OutboundMessage rewritten = withHtml(message, rewrite.html());
			SmtpTransport.OutboundMessage outbound = trackOpens ? withPixel(rewritten, token) : message;
			Mono<Void> preparation = repository.insert(id, actorId, accountId, source, mask(message.recipient()), message.subject(),
					createdAt, expiresAt, token == null ? null : MailTrackingSigner.digest(token))
					.then(repository.insertLinks(id, rewrite.links(), createdAt));
			if (transactions != null) preparation = preparation.as(transactions::transactional);
			Mono<Outcome> durableAttempt = Mono.fromRunnable(() -> smtpAttempt.accept(outbound))
					.subscribeOn(Schedulers.boundedElastic()).thenReturn(new Outcome(Status.SMTP_ACCEPTED, null))
					.onErrorResume(error -> Mono.just(outcome(error)))
					.flatMap(outcome -> repository.complete(id, outcome.status(), outcome.failure() == null ? null
							: outcome.failure().category().name(), clock.instant())
							.onErrorResume(error -> {
								// Outcome persistence must never cause a resend or downgrade an accepted attempt.
								LOGGER.warn("Mail send outcome persistence unavailable recordId={} outcome={}", id, outcome.status());
								return Mono.empty();
							}).thenReturn(outcome))
					.cache();
			return preparation.then(Mono.defer(() -> durableAttempt.flatMap(outcome -> outcome.failure() == null
					? Mono.empty() : Mono.error(outcome.failure()))));
		});
	}

	public Mono<ResolvedClick> click(String token, HttpHeaders headers, boolean observe) {
		return Mono.defer(() -> {
			if (!properties.enabled()) return Mono.empty();
			Instant now = clock.instant();
			Mono<ResolvedClick> resolution = signer.verifyClick(token, now)
					.map(verified -> repository.resolveClick(verified, now)
							.timeout(CALLBACK_RESOLUTION_TIMEOUT).onErrorResume(ignored -> Mono.empty()))
					.orElseGet(Mono::empty);
			return resolution.flatMap(resolved -> (observe
					? repository.observeClick(resolved.linkId(), classifier.classify(headers), now)
							.timeout(CALLBACK_OBSERVATION_TIMEOUT).onErrorResume(ignored -> Mono.empty())
					: Mono.<Void>empty()).thenReturn(resolved));
		});
	}

	public Mono<Void> observe(String token, HttpHeaders headers) {
		return Mono.defer(() -> {
			if (!properties.enabled()) return Mono.empty();
			Instant now = clock.instant();
			return signer.verify(token, now).map(verified -> repository.observe(verified, classifier.classify(headers), now))
					.orElseGet(Mono::empty);
		}).timeout(Duration.ofSeconds(2)).onErrorResume(ignored -> Mono.empty());
	}

	private SmtpTransport.OutboundMessage withPixel(SmtpTransport.OutboundMessage message, String token) {
		String pixel = "<img src=\"" + properties.publicBaseUrl() + "/t/o/" + token
				+ "\" width=\"1\" height=\"1\" alt=\"\" style=\"width:1px;height:1px;border:0\" referrerpolicy=\"no-referrer\">";
		return new SmtpTransport.OutboundMessage(message.recipient(), message.subject(), message.fromName(), message.replyTo(),
				message.html() + pixel, message.text(), message.correlationId());
	}

	private SmtpTransport.OutboundMessage withHtml(SmtpTransport.OutboundMessage message, String html) {
		return new SmtpTransport.OutboundMessage(message.recipient(), message.subject(), message.fromName(), message.replyTo(),
				html, message.text(), message.correlationId());
	}

	private String mask(String recipient) {
		int at = recipient.lastIndexOf('@');
		if (at < 1 || at == recipient.length() - 1 || recipient.length() > 320) {
			throw new MailTrackingValidationException("Test recipient is invalid");
		}
		String masked = recipient.substring(0, recipient.offsetByCodePoints(0, 1)) + "***" + recipient.substring(at);
		if (masked.length() > 320) {
			throw new MailTrackingValidationException("Test recipient is too long to mask");
		}
		return masked;
	}

	private Outcome outcome(Throwable error) {
		SmtpTransportException failure = error instanceof SmtpTransportException smtp ? smtp
				: new SmtpTransportException(SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE);
		Status status = switch (failure.category()) {
			case CONNECTION_TIMEOUT, UNEXPECTED_FAILURE -> Status.UNKNOWN;
			default -> Status.FAILED;
		};
		return new Outcome(status, failure);
	}

	private record Outcome(Status status, SmtpTransportException failure) { }
}
