package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.InternetAddress;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class CampaignWorkflowService {

	private final CampaignWorkflowRepository repository;
	private final CampaignPreflightService preflight;
	private final CampaignService campaigns;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final ObjectMapper objectMapper;
	private final TransactionalOperator transactions;

	public CampaignWorkflowService(
			CampaignWorkflowRepository repository, CampaignPreflightService preflight, CampaignService campaigns,
			AuditService auditService, SensitiveValueHasher hasher, ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.preflight = preflight;
		this.campaigns = campaigns;
		this.auditService = auditService;
		this.hasher = hasher;
		this.objectMapper = objectMapper;
		this.transactions = transactions;
	}

	public Mono<CampaignService.CampaignView> update(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion,
			CampaignUpdateCommand command
	) {
		CampaignUpdateCommand normalized = normalize(command);
		return mutate(id, actorId, safeContext(context), expectedLockVersion, Set.of("DRAFT", "REJECTED"),
				"CAMPAIGN_UPDATED", before -> repository.updateDraft(
						id, expectedLockVersion, normalized, actorId), null).as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> submitReview(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion
	) {
		return preflightMutation(id, actorId, safeContext(context), expectedLockVersion, Set.of("DRAFT"),
				"CAMPAIGN_SUBMITTED_FOR_REVIEW",
				digest -> repository.submitReview(id, expectedLockVersion, actorId, digest), null)
				.as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> approve(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion
	) {
		return preflightMutation(id, actorId, safeContext(context), expectedLockVersion,
				Set.of("READY_FOR_REVIEW"), "CAMPAIGN_APPROVED",
				digest -> repository.approve(id, expectedLockVersion, actorId, digest), null)
				.as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> reject(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion, String reason
	) {
		String normalizedReason = text(reason, 1_000, "Campaign rejection reason");
		return mutate(id, actorId, safeContext(context), expectedLockVersion, Set.of("READY_FOR_REVIEW"),
				"CAMPAIGN_REJECTED", before -> repository.reject(
						id, expectedLockVersion, actorId, normalizedReason), null)
				.as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> schedule(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion,
			Instant scheduledAt
	) {
		if (scheduledAt == null || !scheduledAt.isAfter(Instant.now())) {
			return Mono.error(new CampaignValidationException("Campaign schedule must be in the future"));
		}
		return preflightMutation(id, actorId, safeContext(context), expectedLockVersion, Set.of("APPROVED"),
				"CAMPAIGN_SCHEDULED", digest -> repository.schedule(
						id, expectedLockVersion, actorId, scheduledAt, digest), "SCHEDULE")
				.as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> start(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion
	) {
		return preflightMutation(id, actorId, safeContext(context), expectedLockVersion,
				Set.of("APPROVED", "SCHEDULED"), "CAMPAIGN_STARTED",
				digest -> repository.start(id, expectedLockVersion, actorId, digest), "START")
				.as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> pause(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion
	) {
		return mutate(id, actorId, safeContext(context), expectedLockVersion, Set.of("RUNNING"),
				"CAMPAIGN_PAUSED", before -> repository.pause(id, expectedLockVersion, actorId), null)
				.as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> resume(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion
	) {
		return mutate(id, actorId, safeContext(context), expectedLockVersion, Set.of("PAUSED"),
				"CAMPAIGN_RESUMED", before -> repository.resume(id, expectedLockVersion, actorId), null)
				.as(transactions::transactional);
	}

	public Mono<CampaignService.CampaignView> cancel(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion
	) {
		return mutate(id, actorId, safeContext(context), expectedLockVersion,
				Set.of("SCHEDULED", "RUNNING", "PAUSED"), "CAMPAIGN_CANCELED",
				before -> repository.cancel(id, expectedLockVersion, actorId), null)
				.as(transactions::transactional);
	}

	private Mono<CampaignService.CampaignView> preflightMutation(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion,
			Set<String> allowedStatuses, String action,
			Function<byte[], Mono<CampaignWorkflowRepository.StateRecord>> mutation, String wakeupAction
	) {
		return verifyBefore(id, expectedLockVersion, allowedStatuses)
				.flatMap(before -> preflight.preflight(id).flatMap(result -> {
					if (!result.ready()) {
						return Mono.error(new CampaignValidationException("Campaign preflight is not ready"));
					}
					return applyMutation(id, actorId, context, action, before,
							mutation.apply(preflight.digestBytes(result)), wakeupAction);
				}));
	}

	private Mono<CampaignService.CampaignView> mutate(
			UUID id, UUID actorId, AuthenticationRequestContext context, long expectedLockVersion,
			Set<String> allowedStatuses, String action,
			Function<CampaignWorkflowRepository.StateRecord, Mono<CampaignWorkflowRepository.StateRecord>> mutation,
			String wakeupAction
	) {
		return verifyBefore(id, expectedLockVersion, allowedStatuses)
				.flatMap(before -> applyMutation(id, actorId, context, action, before,
						mutation.apply(before), wakeupAction));
	}

	private Mono<CampaignService.CampaignView> applyMutation(
			UUID id, UUID actorId, AuthenticationRequestContext context, String action,
			CampaignWorkflowRepository.StateRecord before,
			Mono<CampaignWorkflowRepository.StateRecord> mutation, String wakeupAction
	) {
		return mutation.switchIfEmpty(Mono.error(conflict()))
				.flatMap(after -> audit(action, id, actorId, context, before, after)
						.then(wakeupAction == null ? Mono.empty() : wakeup(id, after, context, wakeupAction))
						.then(campaigns.get(id)));
	}

	private Mono<CampaignWorkflowRepository.StateRecord> verifyBefore(
			UUID id, long expectedLockVersion, Set<String> allowedStatuses
	) {
		return repository.state(id)
				.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.flatMap(state -> state.lockVersion() == expectedLockVersion && allowedStatuses.contains(state.status())
						? Mono.just(state) : Mono.error(conflict()));
	}

	private Mono<Void> wakeup(
			UUID campaignId, CampaignWorkflowRepository.StateRecord after,
			AuthenticationRequestContext context, String action
	) {
		UUID messageId = UUID.randomUUID();
		DeliveryWakeup payload = new DeliveryWakeup(
				1, messageId, campaignId, action, context.traceId(), Instant.now());
		try {
			return repository.insertDeliveryOutbox(
					messageId, campaignId, action, after.lockVersion(), context.traceId(),
					objectMapper.writeValueAsString(payload));
		}
		catch (JsonProcessingException exception) {
			return Mono.error(new IllegalStateException("Campaign delivery wake-up could not be serialized", exception));
		}
	}

	private Mono<Void> audit(
			String action, UUID id, UUID actorId, AuthenticationRequestContext context,
			CampaignWorkflowRepository.StateRecord before, CampaignWorkflowRepository.StateRecord after
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "CAMPAIGN", id.toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), stateSummary(before), stateSummary(after),
				AuditResult.SUCCESS, null));
	}

	private Map<String, Object> stateSummary(CampaignWorkflowRepository.StateRecord state) {
		return Map.of("status", state.status(), "lockVersion", state.lockVersion());
	}

	private CampaignUpdateCommand normalize(CampaignUpdateCommand command) {
		if (command == null || command.mailboxAccountId() == null) {
			throw new CampaignValidationException("Campaign update and mailbox account are required");
		}
		return new CampaignUpdateCommand(
				text(command.name(), 200, "Campaign name"),
				text(command.purpose(), 4_000, "Campaign purpose"), command.mailboxAccountId(),
				text(command.fromName(), 160, "Campaign sender name"),
				email(command.replyTo()), command.trackingOpensEnabled(), command.trackingClicksEnabled());
	}

	private String text(String value, int maximum, String label) {
		String normalized = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		if (normalized.isEmpty() || normalized.length() > maximum
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new CampaignValidationException(label + " is invalid");
		}
		return normalized;
	}

	private String email(String value) {
		String normalized = value == null ? "" : value.strip();
		try {
			InternetAddress parsed = new InternetAddress(normalized, true);
			if (!normalized.equals(parsed.getAddress()) || normalized.contains("\r") || normalized.contains("\n")) {
				throw new IllegalArgumentException();
			}
			return normalized;
		}
		catch (Exception exception) {
			throw new CampaignValidationException("Campaign reply-to address is invalid");
		}
	}

	private AuthenticationRequestContext safeContext(AuthenticationRequestContext context) {
		if (context != null && context.ipAddress() != null && context.userAgentSummary() != null
				&& context.traceId() != null && context.traceId().matches("[A-Za-z0-9_-]{8,64}")) {
			return context;
		}
		return new AuthenticationRequestContext("unknown", "unknown",
				UUID.randomUUID().toString().replace("-", ""));
	}

	private CampaignConflictException conflict() {
		return new CampaignConflictException("Campaign state or version changed; refresh before continuing");
	}

	public record CampaignUpdateCommand(
			String name, String purpose, UUID mailboxAccountId, String fromName, String replyTo,
			boolean trackingOpensEnabled, boolean trackingClicksEnabled
	) { }

	private record DeliveryWakeup(
			int version, UUID messageId, UUID campaignId, String action, String traceId, Instant createdAt
	) { }
}
