package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.messaging.PersonalizationCommandMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CampaignService {

	private final CampaignRepository repository;
	private final SegmentRepository segments;
	private final PersonalizationProperties properties;
	private final ObjectMapper objectMapper;
	private final TransactionalOperator transactions;

	public CampaignService(
			CampaignRepository repository, SegmentRepository segments, PersonalizationProperties properties,
			ObjectMapper objectMapper, TransactionalOperator transactions
	) {
		this.repository = repository;
		this.segments = segments;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.transactions = transactions;
	}

	public Mono<PageResponse<CampaignView>> list(int page, int pageSize) {
		validatePage(page, pageSize);
		int offset = Math.multiplyExact(page - 1, pageSize);
		return Mono.zip(repository.list(offset, pageSize).map(this::view).collectList(), repository.count())
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<CampaignView> get(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.map(this::view);
	}

	public Mono<CampaignView> create(UUID actorId, CampaignCommand command) {
		CampaignCommand normalized = normalize(command);
		return transactions.transactional(repository.create(normalized, actorId)
				.switchIfEmpty(Mono.error(new CampaignValidationException(
						"Campaign requires an active template, segment, and enabled SMTP account"))))
				.flatMap(this::get);
	}

	public Mono<GenerationStart> startPersonalization(
			UUID campaignId, AuthenticationRequestContext context
	) {
		if (!properties.enabled()) {
			return Mono.error(new PersonalizationUnavailableException(
					"Personalization is disabled until an API key and worker are configured"));
		}
		return Mono.defer(() -> repository.generationContext(campaignId)
				.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.flatMap(generation -> queue(generation, context))).as(transactions::transactional);
	}

	public Mono<PageResponse<RecipientView>> recipients(UUID campaignId, int page, int pageSize) {
		validatePage(page, pageSize);
		int offset = Math.multiplyExact(page - 1, pageSize);
		return get(campaignId).then(Mono.zip(
				repository.recipients(campaignId, offset, pageSize).map(this::recipient).collectList(),
				repository.recipientCount(campaignId)))
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	private Mono<GenerationStart> queue(
			CampaignRepository.GenerationContext generation, AuthenticationRequestContext context
	) {
		if (!"DRAFT".equals(generation.status())) {
			return Mono.error(new CampaignValidationException("Only draft campaigns can generate personalization"));
		}
		UUID jobId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		return segments.rules(generation.segmentId()).collectList()
				.map(SegmentModels::criteria)
				.flatMap(criteria -> repository.prepareGeneration(
						generation.campaignId(), jobId, properties.provider(), properties.model())
						.flatMap(prepared -> prepared
								? segments.campaignCandidates(criteria, properties.maxRecipients())
										.concatMap(candidate -> repository.queueRecipient(
												generation.campaignId(), candidate)).collectList()
								: Mono.error(new CampaignValidationException(
										"Campaign generation is already running"))))
				.flatMap(targets -> targets.isEmpty()
						? Mono.error(new CampaignValidationException("Segment has no eligible recipients"))
						: createMessage(generation, context, jobId, messageId, targets)
								.flatMap(message -> repository.insertOutbox(message, json(message))
										.thenReturn(new GenerationStart(jobId, targets.size()))));
	}

	private Mono<PersonalizationCommandMessage> createMessage(
			CampaignRepository.GenerationContext generation, AuthenticationRequestContext context,
			UUID jobId, UUID messageId, List<CampaignRepository.QueuedTarget> targets
	) {
		String trace = context == null || context.traceId() == null
				? UUID.randomUUID().toString().replace("-", "") : context.traceId();
		if (!trace.matches("[A-Za-z0-9_-]{8,64}")) {
			trace = UUID.randomUUID().toString().replace("-", "");
		}
		String idempotency = "personalization:" + generation.campaignId() + ":" + jobId;
		List<PersonalizationCommandMessage.Target> commandTargets = targets.stream()
				.map(target -> new PersonalizationCommandMessage.Target(
						target.recipientId(), target.authorName(), target.paperTitle(), target.abstractText(),
						target.arxivId(), target.primaryCategory(),
						"https://arxiv.org/abs/" + target.arxivId(), target.organization())).toList();
		return Mono.just(new PersonalizationCommandMessage(
				1, messageId, "PERSONALIZE_CAMPAIGN", jobId, generation.campaignId(), idempotency,
				trace, Instant.now(), new PersonalizationCommandMessage.Payload(
						generation.purpose(), generation.templateSubject(), generation.templateHtml(),
						generation.templateText(), commandTargets)));
	}

	private CampaignCommand normalize(CampaignCommand command) {
		if (command == null || command.name() == null || command.purpose() == null
				|| command.templateId() == null || command.segmentId() == null || command.smtpAccountId() == null) {
			throw new CampaignValidationException("Campaign name, purpose, template, segment, and SMTP account are required");
		}
		String name = command.name().strip();
		String purpose = command.purpose().strip();
		if (name.isEmpty() || name.length() > 200) {
			throw new CampaignValidationException("Campaign name must contain 1 to 200 characters");
		}
		if (purpose.isEmpty() || purpose.length() > 4_000) {
			throw new CampaignValidationException("Campaign purpose must contain 1 to 4000 characters");
		}
		return new CampaignCommand(name, purpose, command.templateId(), command.segmentId(), command.smtpAccountId());
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Personalization command could not be serialized", exception);
		}
	}

	private CampaignView view(CampaignRepository.CampaignRecord record) {
		return new CampaignView(
				record.id(), record.name(), record.purpose(), record.status(), record.templateId(),
				record.templateName(), record.templateVersion(), record.segmentId(), record.segmentName(),
				record.smtpAccountId(), record.smtpName(), record.mailboxAccountId(),
				record.fromName(), record.fromEmail(), record.replyTo(), record.trackingOpensEnabled(),
				record.trackingClicksEnabled(),
				record.generationStatus(), record.generationProvider(), record.generationModel(), record.generationJobId(),
				new RecipientCounts(record.queued(), record.running(), record.generated(), record.failed()),
				new DeliveryCounts(
						record.deliveryQueued(), record.deliveryConnecting(), record.deliverySmtpAccepted(),
						record.deliveryTemporaryFailure(), record.deliveryPermanentFailure(), record.deliveryBounced(),
						record.deliverySuppressed(), record.deliveryUnsubscribed(), record.deliveryCanceled(),
						record.deliveryOutcomeUnknown()),
				record.lockVersion(), record.submittedForReviewAt(), record.approvedAt(), record.approvedBy(),
				record.rejectedAt(), record.rejectedBy(), record.rejectionReason(), record.scheduledAt(),
				record.startedAt(), record.completedAt(), record.canceledAt(), record.statusChangedAt(),
				record.statusChangedBy(),
				record.createdAt(), record.updatedAt());
	}

	private RecipientView recipient(CampaignRepository.RecipientRecord record) {
		return new RecipientView(
				record.id(), record.authorName(), record.paperTitle(), record.category(), record.organization(),
				record.personalizationStatus(), record.subject(), record.html(), record.text(), record.rationale(),
				record.errorCode(), record.errorMessage(), record.personalizedAt(), record.createdAt());
	}

	private void validatePage(int page, int pageSize) {
		if (page < 1 || pageSize < 1 || pageSize > 100) {
			throw new CampaignValidationException("Page must be at least 1 and pageSize between 1 and 100");
		}
	}

	public record CampaignCommand(
			String name, String purpose, UUID templateId, UUID segmentId, UUID smtpAccountId
	) { }

	public record RecipientCounts(int queued, int running, int generated, int failed) {
		public int total() {
			return queued + running + generated + failed;
		}
	}

	public record DeliveryCounts(
			int queued, int connecting, int smtpAccepted, int temporaryFailure, int permanentFailure,
			int bounced, int suppressed, int unsubscribed, int canceled, int outcomeUnknown
	) {
		public int total() {
			return queued + connecting + smtpAccepted + temporaryFailure + permanentFailure + bounced
					+ suppressed + unsubscribed + canceled + outcomeUnknown;
		}
	}

	public record CampaignView(
			UUID id, String name, String purpose, String status, UUID templateId, String templateName,
			int templateVersion, UUID segmentId, String segmentName, UUID smtpAccountId, String smtpName,
			UUID mailboxAccountId, String fromName, String fromEmail, String replyTo,
			boolean trackingOpensEnabled, boolean trackingClicksEnabled, String generationStatus,
			String generationProvider, String generationModel, UUID generationJobId,
			RecipientCounts recipientCounts, DeliveryCounts deliveryCounts, long lockVersion,
			Instant submittedForReviewAt, Instant approvedAt, UUID approvedBy,
			Instant rejectedAt, UUID rejectedBy, String rejectionReason, Instant scheduledAt,
			Instant startedAt, Instant completedAt, Instant canceledAt, Instant statusChangedAt,
			UUID statusChangedBy, Instant createdAt, Instant updatedAt
	) { }

	public record RecipientView(
			UUID id, String authorName, String paperTitle, String category, String organization,
			String personalizationStatus, String subject, String html, String text, String rationale,
			String errorCode, String errorMessage, Instant personalizedAt, Instant createdAt
	) { }

	public record GenerationStart(UUID jobId, int queuedRecipients) { }
}
