package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CampaignSafetyService {
	public static final String CONFIRMATION = "SAFETY_REDIRECT";

	private final CampaignSafetyRepository repository;
	private final CampaignSafetyRuntimePolicy policy;
	private final int maximumRecipients;
	private final Clock clock;

	public CampaignSafetyService(
			CampaignSafetyRepository repository, CampaignSafetyRuntimePolicy policy,
			int maximumRecipients, Clock clock
	) {
		this.repository = repository;
		this.policy = policy;
		this.maximumRecipients = maximumRecipients;
		this.clock = clock;
	}

	public Mono<SafetyRunView> start(
			UUID campaignId, UUID actorId, AuthenticationRequestContext context, StartCommand command
	) {
		if (command == null || command.expectedLockVersion() < 0
				|| command.recipientLimit() < 1 || command.recipientLimit() > Math.min(maximumRecipients, 20)
				|| !CONFIRMATION.equals(command.confirmation())) {
			return Mono.error(new CampaignValidationException("Campaign safety confirmation or recipient limit is invalid"));
		}
		if (policy == null) {
			return Mono.error(new CampaignValidationException("Campaign safety mode is unavailable"));
		}
		CampaignSafetyRuntimePolicy.Destination destination = policy.requireReady();
		return repository.materialize(new CampaignSafetyRepository.MaterializeCommand(
				campaignId, actorId, command.expectedLockVersion(), command.recipientLimit(), destination.hmac(),
				destination.masked(), clock.instant(), context.traceId()))
				.flatMap(created -> get(campaignId, created.id()));
	}

	public Mono<List<SafetyRunView>> list(UUID campaignId) {
		return repository.list(campaignId).map(this::view).collectList();
	}

	public Mono<SafetyRunView> get(UUID campaignId, UUID runId) {
		return repository.get(campaignId, runId)
				.switchIfEmpty(Mono.error(new com.camel_hub.advertisement.campaign.CampaignNotFoundException(
						"Campaign safety run was not found")))
				.map(this::view);
	}

	public Mono<SafetyRunView> cancel(
			UUID campaignId, UUID runId, UUID actorId,
			AuthenticationRequestContext context, long expectedLockVersion
	) {
		if (expectedLockVersion < 0) {
			return Mono.error(new CampaignValidationException("Campaign safety lock version is invalid"));
		}
		return repository.cancel(campaignId, runId, expectedLockVersion, actorId, clock.instant(), context.traceId())
				.flatMap(applied -> applied ? get(campaignId, runId)
						: Mono.error(new com.camel_hub.advertisement.campaign.CampaignConflictException(
								"Campaign safety run changed; refresh and retry")));
	}

	private SafetyRunView view(CampaignSafetyRepository.RunSnapshot run) {
		return new SafetyRunView(
				run.id(), run.campaignId(), run.status(), run.recipientLimit(), run.destinationMasked(),
				new SafetyProgress(run.total(), run.queued(), run.connecting(), run.accepted(),
						run.temporaryFailure(), run.permanentFailure(), run.canceled(), run.outcomeUnknown()),
				new SafetyEventCounts(run.opens(), run.clicks(), run.unsubscribes(),
						run.replies(), run.autoReplies(), run.bounces()),
				run.lockVersion(), run.startedAt(), run.completedAt(), run.createdAt(),
				run.messages().stream().map(message -> new SafetyMessageView(
						message.id(), message.campaignRecipientId(), message.status(), message.attemptCount(),
						message.smtpAcceptedAt(), message.outcomeUnknownAt(), message.outcomeUnknownReason())).toList());
	}

	public record StartCommand(long expectedLockVersion, int recipientLimit, String confirmation) { }

	public record SafetyRunView(
			UUID id, UUID campaignId, String status, int recipientLimit, String destinationMasked,
			SafetyProgress progress, SafetyEventCounts events, long lockVersion,
			Instant startedAt, Instant completedAt, Instant createdAt, List<SafetyMessageView> messages
	) {
		public SafetyRunView {
			messages = List.copyOf(messages);
		}
	}

	public record SafetyProgress(
			int total, int queued, int connecting, int smtpAccepted, int temporaryFailure,
			int permanentFailure, int canceled, int outcomeUnknown
	) { }

	public record SafetyEventCounts(
			long open, long click, long unsubscribe, long reply, long autoReply, long bounce
	) { }

	public record SafetyMessageView(
			UUID id, UUID campaignRecipientId, String status, int attemptCount,
			Instant smtpAcceptedAt, Instant outcomeUnknownAt, String outcomeUnknownReason
	) { }
}
