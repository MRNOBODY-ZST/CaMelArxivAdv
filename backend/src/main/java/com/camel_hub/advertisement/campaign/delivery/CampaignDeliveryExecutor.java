package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.safety.CampaignSafetyOutboundPreparer;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;

import static com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import static com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;

/** Executes exactly one durable claim; SMTP I/O always occurs after claim commit. */
public final class CampaignDeliveryExecutor {

	private final CampaignDeliveryRepository repository;
	private final CampaignOutboundPreparer preparer;
	private final CampaignSafetyRepository safetyRepository;
	private final CampaignSafetyOutboundPreparer safetyPreparer;
	private final ContactCrypto contactCrypto;
	private final CampaignSmtpSender sender;
	private final Clock clock;

	public CampaignDeliveryExecutor(
			CampaignDeliveryRepository repository, CampaignOutboundPreparer preparer,
			ContactCrypto contactCrypto, CampaignSmtpSender sender, Clock clock
	) {
		this(repository, preparer, null, null, contactCrypto, sender, clock);
	}

	public CampaignDeliveryExecutor(
			CampaignDeliveryRepository repository, CampaignOutboundPreparer preparer,
			CampaignSafetyRepository safetyRepository, CampaignSafetyOutboundPreparer safetyPreparer,
			ContactCrypto contactCrypto, CampaignSmtpSender sender, Clock clock
	) {
		this.repository = repository;
		this.preparer = preparer;
		this.safetyRepository = safetyRepository;
		this.safetyPreparer = safetyPreparer;
		this.contactCrypto = contactCrypto;
		this.sender = sender;
		this.clock = clock;
	}

	public CampaignDeliveryExecutor(
			CampaignDeliveryRepository repository, CampaignOutboundPreparer preparer,
			ContactCrypto contactCrypto, SmtpTransport transport, Clock clock
	) {
		this(repository, preparer, contactCrypto, transport::sendDetailed, clock);
	}

	public Mono<PumpResult> pumpOnce() {
		Instant claimTime = clock.instant();
		return repository.claimNext(claimTime)
				.flatMap(claim -> switch (claim) {
					case CampaignDeliveryRepository.ProductionClaim production -> execute(production);
					case CampaignDeliveryRepository.SafetyClaim safety -> execute(safety);
				})
				.defaultIfEmpty(PumpResult.NO_WORK);
	}

	private Mono<PumpResult> execute(CampaignDeliveryRepository.ProductionClaim claim) {
		return Mono.defer(() -> preparer.prepare(claim))
				.switchIfEmpty(Mono.error(new IllegalArgumentException("Campaign preparation returned no result")))
				.flatMap(prepared -> Mono.fromCallable(() -> send(claim, prepared))
						.subscribeOn(Schedulers.boundedElastic()))
				.map(SendResolution::accepted)
				.onErrorResume(error -> settleFailure(claim, error).map(SendResolution::settled))
				.flatMap(resolution -> {
					if (resolution.outcome() == null) return Mono.just(resolution.result());
					return repository.completeAccepted(
							claim.recipientId(), claim.attemptId(), claim.leaseDigest(),
							resolution.outcome(), clock.instant())
							.flatMap(applied -> applied
									? Mono.just(PumpResult.SMTP_ACCEPTED)
									: Mono.error(new IllegalStateException(
											"Accepted SMTP outcome requires lease reconciliation")));
				});
	}

	private Mono<PumpResult> execute(CampaignDeliveryRepository.SafetyClaim claim) {
		if (safetyRepository == null || safetyPreparer == null) {
			return settleSafetyFailure(claim, new IllegalStateException("Campaign safety delivery is unavailable"));
		}
		return Mono.defer(() -> safetyPreparer.prepare(claim))
				.switchIfEmpty(Mono.error(new IllegalArgumentException("Campaign safety preparation returned no result")))
				.flatMap(prepared -> Mono.fromCallable(() -> send(claim, prepared))
						.subscribeOn(Schedulers.boundedElastic()))
				.map(SendResolution::accepted)
				.onErrorResume(error -> settleSafetyFailure(claim, error).map(SendResolution::settled))
				.flatMap(resolution -> {
					if (resolution.outcome() == null) return Mono.just(resolution.result());
					return safetyRepository.completeAccepted(
							claim.messageId(), claim.attemptId(), claim.leaseDigest(),
							resolution.outcome(), clock.instant())
							.flatMap(applied -> applied
									? Mono.just(PumpResult.SMTP_ACCEPTED)
									: Mono.error(new IllegalStateException(
											"Accepted campaign safety outcome requires lease reconciliation")));
				});
	}

	private SmtpTransport.SmtpOutcome send(
			CampaignDeliveryRepository.ProductionClaim claim,
			CampaignOutboundPreparer.PreparedOutbound prepared
	) {
		String recipient = contactCrypto.decrypt(new ContactCrypto.EncryptedValue(
				claim.emailCiphertext(), claim.emailNonce()));
		SmtpTransport.OutboundMessage outbound = new SmtpTransport.OutboundMessage(
				recipient, prepared.subject(), claim.fromName(), claim.replyTo(),
				prepared.html(), prepared.text(), claim.correlationId(), claim.fromEmail(),
				claim.rfcMessageId(), prepared.headers());
		return sender.send(claim.smtpAccount(), outbound);
	}

	private SmtpTransport.SmtpOutcome send(
			CampaignDeliveryRepository.SafetyClaim claim,
			CampaignSafetyOutboundPreparer.PreparedSafetyOutbound prepared
	) {
		prepared = safetyPreparer.validateForSend(claim, prepared);
		SmtpTransport.OutboundMessage outbound = new SmtpTransport.OutboundMessage(
				prepared.recipient(), prepared.subject(), claim.fromName(), claim.replyTo(),
				prepared.html(), prepared.text(), claim.correlationId(), claim.fromEmail(),
				claim.rfcMessageId(), prepared.headers());
		return sender.send(claim.smtpAccount(), outbound);
	}

	private Mono<PumpResult> settleFailure(
			CampaignDeliveryRepository.ProductionClaim claim, Throwable error
	) {
		SmtpTransportException failure = error instanceof SmtpTransportException smtp
				? smtp
				: new SmtpTransportException(
						SmtpTransportException.FailureCategory.PREPARATION_FAILED,
						AttemptStatus.PERMANENT_FAILURE, TransportStage.MAIL_FROM,
						null, null, false);
		return repository.completeFailureDetailed(
				claim.recipientId(), claim.attemptId(), claim.leaseDigest(), failure, clock.instant())
				.flatMap(settlement -> settlement.applied()
						? Mono.just(switch (settlement.recipientStatus()) {
							case TEMPORARY_FAILURE -> PumpResult.TEMPORARY_FAILURE;
							case PERMANENT_FAILURE -> PumpResult.PERMANENT_FAILURE;
							case OUTCOME_UNKNOWN -> PumpResult.OUTCOME_UNKNOWN;
							default -> throw new IllegalStateException("Unsupported delivery failure settlement");
						})
						: Mono.error(new IllegalStateException("Delivery lease failure settlement was rejected")));
	}

	private Mono<PumpResult> settleSafetyFailure(
			CampaignDeliveryRepository.SafetyClaim claim, Throwable error
	) {
		SmtpTransportException failure = error instanceof SmtpTransportException smtp
				? smtp
				: new SmtpTransportException(
						SmtpTransportException.FailureCategory.PREPARATION_FAILED,
						AttemptStatus.PERMANENT_FAILURE, TransportStage.MAIL_FROM,
						null, null, false);
		return safetyRepository.completeFailure(
				claim.messageId(), claim.attemptId(), claim.leaseDigest(), failure, clock.instant(),
				repository.maximumAttempts(), repository.firstRetryDelay(), repository.secondRetryDelay())
				.flatMap(settlement -> {
					if (!settlement.applied()) {
						return Mono.error(new IllegalStateException(
								"Campaign safety lease failure settlement was rejected"));
					}
					return Mono.just(switch (settlement.messageStatus()) {
						case "TEMPORARY_FAILURE" -> PumpResult.TEMPORARY_FAILURE;
						case "OUTCOME_UNKNOWN" -> PumpResult.OUTCOME_UNKNOWN;
						default -> PumpResult.PERMANENT_FAILURE;
					});
				});
	}

	@FunctionalInterface
	public interface CampaignSmtpSender {
		SmtpTransport.SmtpOutcome send(
				SmtpRepository.SmtpAccountRecord account, SmtpTransport.OutboundMessage message);
	}

	public enum PumpResult {
		NO_WORK, SMTP_ACCEPTED, TEMPORARY_FAILURE, PERMANENT_FAILURE, OUTCOME_UNKNOWN
	}

	private record SendResolution(SmtpTransport.SmtpOutcome outcome, PumpResult result) {
		private static SendResolution accepted(SmtpTransport.SmtpOutcome outcome) {
			return new SendResolution(outcome, null);
		}

		private static SendResolution settled(PumpResult result) {
			return new SendResolution(null, result);
		}
	}
}
