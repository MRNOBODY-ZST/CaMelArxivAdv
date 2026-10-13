package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.campaign.tracking.CampaignPublicContentRedactor;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.messaging.PersonalizationCommandMessage;
import com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.MigrationReference;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.RecipientTransfer;


public class CampaignService {
	private static final int MAX_PRODUCTION_MESSAGE_BYTES = 900 * 1024;

	private final CampaignRepository repository;
	private final SegmentRepository segments;
	private final PersonalizationProperties properties;
	private final ObjectMapper objectMapper;
	private final TransactionalOperator transactions;
	private final CampaignPublicContentRedactor contentRedactor;
	private final CampaignProductionSelectionPolicy productionPolicy;
	private final PersonalizationSettingsService settings;

	public CampaignService(
			CampaignRepository repository, SegmentRepository segments, PersonalizationProperties properties,
			ObjectMapper objectMapper, TransactionalOperator transactions
	) {
		this(repository, segments, properties, objectMapper, transactions, new CampaignPublicContentRedactor());
	}

	public CampaignService(
			CampaignRepository repository, SegmentRepository segments, PersonalizationProperties properties,
			ObjectMapper objectMapper, TransactionalOperator transactions,
			CampaignPublicContentRedactor contentRedactor
	) {
		this(repository, segments, properties, objectMapper, transactions, contentRedactor, null);
	}

	public CampaignService(
			CampaignRepository repository, SegmentRepository segments, PersonalizationProperties properties,
			ObjectMapper objectMapper, TransactionalOperator transactions,
			CampaignPublicContentRedactor contentRedactor, CampaignProductionSelectionPolicy productionPolicy
	) {
		this(repository, segments, properties, objectMapper, transactions, contentRedactor, productionPolicy, null);
	}

	public CampaignService(
			CampaignRepository repository, SegmentRepository segments, PersonalizationProperties properties,
			ObjectMapper objectMapper, TransactionalOperator transactions,
			CampaignPublicContentRedactor contentRedactor, CampaignProductionSelectionPolicy productionPolicy,
			PersonalizationSettingsService settings
	) {
		this.repository = repository;
		this.segments = segments;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.transactions = transactions;
		this.contentRedactor = contentRedactor;
		this.productionPolicy = productionPolicy;
		this.settings = settings;
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
		return startPersonalization(campaignId, context, null);
	}

	public Mono<GenerationStart> startPersonalization(
			UUID campaignId, AuthenticationRequestContext context, List<UUID> paperIds
	) {
		return startPersonalization(campaignId, context, paperIds, null);
	}

	public Mono<GenerationStart> startPersonalization(
			UUID campaignId, AuthenticationRequestContext context, List<UUID> paperIds, Integer recipientLimit
	) {
		List<UUID> selection = validateSelection(paperIds);
		if (recipientLimit != null && (recipientLimit < 1 || recipientLimit > 120 || paperIds != null)) {
			throw new CampaignValidationException("recipientLimit must be between 1 and 120 and cannot be combined with paperIds");
		}
		return Mono.defer(() -> generationConfiguration().flatMap(configuration -> repository.generationContext(campaignId)
				.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.flatMap(generation -> "PAPER_CONTACT".equals(generation.recipientPolicy())
						? Mono.error(new CampaignValidationException("Use the frozen paperContactIds selector for this campaign"))
						: generation.recipientLimit() != null || recipientLimit != null
						? queueProduction(generation, context, selection, recipientLimit, configuration)
						: queue(generation, context, selection, configuration)))).as(transactions::transactional);
	}

	public Mono<GenerationStart> startPaperContactPersonalization(UUID campaignId,
			AuthenticationRequestContext context, List<UUID> paperIds) {
		if (paperIds == null || paperIds.isEmpty() || paperIds.size() > 100
				|| paperIds.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(paperIds).size() != paperIds.size()) {
			throw new CampaignValidationException("Select between 1 and 100 distinct paperContactIds");
		}
		List<UUID> selection = List.copyOf(paperIds);
		if (productionPolicy == null) return Mono.error(new PersonalizationUnavailableException("Production selection policy is unavailable"));
		return Mono.defer(() -> generationConfiguration().flatMap(configuration -> repository.lockPaperContactSelection()
				.then(repository.generationContext(campaignId))
				.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.flatMap(generation -> queuePaperContacts(generation, context, selection, configuration))))
				.as(transactions::transactional);
	}

	private Mono<GenerationStart> queuePaperContacts(CampaignRepository.GenerationContext generation,
			AuthenticationRequestContext context, List<UUID> selection, GenerationConfiguration configuration) {
		boolean frozen = "PAPER_CONTACT".equals(generation.recipientPolicy());
		if (!"DRAFT".equals(generation.status()) || generation.paperIds() != null || generation.recipientLimit() != null
				|| (frozen && !new HashSet<>(generation.paperContactIds()).equals(new HashSet<>(selection)))) {
			return Mono.error(new CampaignValidationException("The campaign policy and paper manifest are fixed; use a new empty draft"));
		}
		UUID job = UUID.randomUUID();
		return repository.frozenProductionRecipients(generation.campaignId(), productionPolicy.cooldownCutoff(),
				productionPolicy.excludedHmacs()).collectList().flatMap(existing -> {
			Mono<List<CampaignRepository.QueuedTarget>> queued;
			if (!frozen) {
				if (!existing.isEmpty() || !"NOT_REQUESTED".equals(generation.generationStatus())) {
					return Mono.error(new CampaignValidationException("Paper-contact mode requires a new empty draft"));
				}
				queued = segments.paperContactCandidates(generation.campaignId(), selection, productionPolicy.cooldownCutoff(),
						productionPolicy.excludedHmacs()).collectList().flatMap(candidates -> {
					if (candidates.isEmpty() || candidates.size() > 120) return Mono.error(new CampaignValidationException(
							"Paper-contact selection requires 1–120 eligible distinct addresses; split the paper manifest"));
					return preparePaperContacts(generation.campaignId(), job, selection, candidates.size(), configuration)
							.thenMany(Flux.fromIterable(candidates)).concatMap(candidate -> repository.queueRecipient(generation.campaignId(), candidate)).collectList();
				});
			} else {
				if (existing.isEmpty() || existing.size() > 120 || generation.paperContactRecipientCount() == null
						|| existing.size() != generation.paperContactRecipientCount()) throw invalidFrozenProduction();
				for (var recipient : existing) {
					if (!recipient.currentlyEligible() || recipient.attemptCount() != 0
							|| !selection.contains(recipient.paperId())
							|| !Set.of("PENDING", "FAILED", "GENERATED").contains(recipient.personalizationStatus())) throw invalidFrozenProduction();
				}
				queued = preparePaperContacts(generation.campaignId(), job, generation.paperContactIds(), existing.size(), configuration)
						.thenMany(Flux.fromIterable(existing)).filter(r -> !"GENERATED".equals(r.personalizationStatus()))
						.concatMap(r -> repository.requeueFrozenRecipient(generation.campaignId(), r)).collectList();
			}
			return queued.flatMap(targets -> targets.isEmpty()
					? Mono.error(new CampaignValidationException("Frozen selection has no failed or pending recipients"))
					: createMessage(generation, context, job, UUID.randomUUID(), targets, true)
							.flatMap(message -> insertProductionOutbox(message).thenReturn(new GenerationStart(job, targets.size()))));
		});
	}

	private Mono<Void> preparePaperContacts(UUID campaignId, UUID job, List<UUID> papers, int recipientCount, GenerationConfiguration configuration) {
		return repository.preparePaperContactGeneration(campaignId, job, configuration.provider(), configuration.model(), papers, recipientCount)
				.flatMap(prepared -> prepared ? snapshot(job, configuration)
						: Mono.error(new CampaignValidationException("Campaign generation is already running")));
	}

	private Mono<GenerationConfiguration> generationConfiguration() {
		if (settings == null) {
			return properties.enabled()
					? Mono.just(new GenerationConfiguration(properties.provider(), properties.model(), null))
					: Mono.error(new PersonalizationUnavailableException("Personalization is disabled"));
		}
		return settings.effective().flatMap(effective -> effective.enabled() && effective.keyConfigured()
				? Mono.just(new GenerationConfiguration(effective.provider(), effective.model(), effective))
				: Mono.error(new PersonalizationUnavailableException(
						"Enable personalization and configure an API key in system settings")));
	}

	private Mono<Void> snapshot(UUID jobId, GenerationConfiguration configuration) {
		return settings == null ? Mono.empty() : settings.snapshot(jobId, configuration.snapshot());
	}

	private record GenerationConfiguration(String provider, String model,
			PersonalizationSettingsService.EffectiveSettings snapshot) { }

	private Mono<GenerationStart> queueProduction(
			CampaignRepository.GenerationContext generation, AuthenticationRequestContext context,
			List<UUID> paperIds, Integer requestedLimit, GenerationConfiguration configuration
	) {
		if (productionPolicy == null) return Mono.error(new PersonalizationUnavailableException("Production selection policy is unavailable"));
		if (!"DRAFT".equals(generation.status())) {
			return Mono.error(new CampaignValidationException("Only draft campaigns can generate personalization"));
		}
		if (paperIds != null || generation.paperIds() != null
				|| (generation.recipientLimit() != null && requestedLimit != null && !generation.recipientLimit().equals(requestedLimit))) {
			return Mono.error(new CampaignValidationException("The campaign selection mode and recipient limit are fixed; use a new draft"));
		}
		int limit = generation.recipientLimit() == null ? requestedLimit : generation.recipientLimit();
		UUID jobId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		return repository.frozenProductionRecipients(generation.campaignId(), productionPolicy.cooldownCutoff(),
				productionPolicy.excludedHmacs()).collectList().flatMap(existing -> {
			Mono<List<CampaignRepository.QueuedTarget>> queued;
			if (generation.recipientLimit() == null) {
				if (!existing.isEmpty() || !"NOT_REQUESTED".equals(generation.generationStatus())) {
					return Mono.error(new CampaignValidationException("Start an exact production selection on a new empty draft"));
				}
				queued = segments.rules(generation.segmentId()).collectList().map(SegmentModels::criteria)
						.flatMapMany(criteria -> segments.productionCandidates(criteria, productionPolicy.cooldownCutoff(), productionPolicy.excludedHmacs()))
						.collectList().map(candidates -> selectDistinctAuthors(candidates, limit))
						.flatMap(selected -> prepareProduction(generation.campaignId(), jobId, limit, configuration)
								.thenMany(Flux.fromIterable(selected))
								.concatMap(candidate -> repository.queueRecipient(generation.campaignId(), candidate)).collectList());
			} else {
				validateFrozenProduction(existing, limit);
				queued = prepareProduction(generation.campaignId(), jobId, limit, configuration)
						.thenMany(Flux.fromIterable(existing))
						.filter(recipient -> !"GENERATED".equals(recipient.personalizationStatus()))
						.concatMap(recipient -> repository.requeueFrozenRecipient(generation.campaignId(), recipient)).collectList();
			}
			return queued.flatMap(targets -> targets.isEmpty()
					? Mono.error(new CampaignValidationException("Frozen selection has no failed or pending recipients to generate"))
					: createMessage(generation, context, jobId, messageId, targets)
							.flatMap(message -> insertProductionOutbox(message)
									.thenReturn(new GenerationStart(jobId, targets.size()))));
		});
	}

	private Mono<Void> insertProductionOutbox(PersonalizationCommandMessage message) {
		String payload = json(message);
		if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PRODUCTION_MESSAGE_BYTES) {
			return Mono.error(new CampaignValidationException(
					"Production personalization message is too large; use a smaller batch on a new draft"));
		}
		return repository.insertOutbox(message, payload);
	}

	private Mono<Void> prepareProduction(UUID campaignId, UUID jobId, int limit, GenerationConfiguration configuration) {
		return repository.prepareGeneration(campaignId, jobId, configuration.provider(), configuration.model(), null, limit)
				.flatMap(prepared -> prepared ? snapshot(jobId, configuration)
						: Mono.error(new CampaignValidationException("Campaign generation is already running")));
	}

	private List<SegmentRepository.CampaignCandidate> selectDistinctAuthors(
			List<SegmentRepository.CampaignCandidate> candidates, int limit
	) {
		Map<UUID, List<SegmentRepository.CampaignCandidate>> byAuthor = new LinkedHashMap<>();
		for (var candidate : candidates) {
			byAuthor.computeIfAbsent(candidate.authorId(), ignored -> new ArrayList<>()).add(candidate);
		}
		Map<String, SegmentRepository.CampaignCandidate> assigned = new LinkedHashMap<>();
		for (UUID author : byAuthor.keySet()) {
			assignDistinctAuthor(author, byAuthor, assigned, new HashSet<>());
			if (assigned.size() == limit) return List.copyOf(assigned.values());
		}
		throw new CampaignValidationException("Exact production selection requires " + limit
				+ " distinct eligible authors and addresses with traceable HIGH evidence or human-confirmed MEDIUM evidence; no recipients were queued");
	}

	private boolean assignDistinctAuthor(UUID author, Map<UUID, List<SegmentRepository.CampaignCandidate>> byAuthor,
			Map<String, SegmentRepository.CampaignCandidate> assigned, Set<String> visited) {
		for (var candidate : byAuthor.get(author)) {
			String key = Base64.getEncoder().encodeToString(candidate.emailHmac());
			if (!visited.add(key)) continue;
			var previous = assigned.get(key);
			if (previous == null || assignDistinctAuthor(previous.authorId(), byAuthor, assigned, visited)) {
				assigned.put(key, candidate);
				return true;
			}
		}
		return false;
	}

	private void validateFrozenProduction(List<CampaignRepository.FrozenProductionRecipient> existing, int limit) {
		Set<UUID> authors = new HashSet<>();
		Set<String> emails = new HashSet<>();
		if (existing.size() != limit) throw invalidFrozenProduction();
		for (var recipient : existing) {
			var target = recipient.target();
			if (recipient.contactId() == null || recipient.paperId() == null || recipient.authorId() == null
					|| !recipient.currentlyEligible()
					|| !authors.add(recipient.authorId()) || !emails.add(Base64.getEncoder().encodeToString(recipient.emailHmac()))
					|| productionPolicy.excludes(recipient.emailHmac()) || recipient.attemptCount() != 0
					|| !Set.of("PENDING", "FAILED", "GENERATED").contains(recipient.personalizationStatus())
					|| target.authorName() == null || target.authorName().isBlank()
					|| target.paperTitle() == null || target.paperTitle().isBlank()
					|| target.abstractText() == null || target.abstractText().isBlank()
					|| target.arxivId() == null || target.arxivId().isBlank()) throw invalidFrozenProduction();
		}
	}

	private CampaignValidationException invalidFrozenProduction() {
		return new CampaignValidationException("Frozen production selection changed or is not retryable; no recipients were added or replaced");
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
			CampaignRepository.GenerationContext generation, AuthenticationRequestContext context,
			List<UUID> requestedSelection, GenerationConfiguration configuration
	) {
		if (!"DRAFT".equals(generation.status())) {
			return Mono.error(new CampaignValidationException("Only draft campaigns can generate personalization"));
		}
		List<UUID> selection = effectiveSelection(generation.paperIds(), requestedSelection);
		UUID jobId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		return segments.rules(generation.segmentId()).collectList()
				.map(SegmentModels::criteria)
				.flatMap(criteria -> repository.prepareGeneration(
						generation.campaignId(), jobId, configuration.provider(), configuration.model(), selection)
						.flatMap(prepared -> prepared
								? snapshot(jobId, configuration).then(candidates(generation.campaignId(), criteria, selection))
										.flatMapMany(Flux::fromIterable)
										.concatMap(candidate -> repository.queueRecipient(generation.campaignId(), candidate))
										.collectList()
								: Mono.error(new CampaignValidationException(
										"Campaign generation is already running"))))
				.flatMap(targets -> targets.isEmpty()
						? Mono.error(new CampaignValidationException("Segment has no eligible recipients"))
						: createMessage(generation, context, jobId, messageId, targets)
								.flatMap(message -> repository.insertOutbox(message, json(message))
										.thenReturn(new GenerationStart(jobId, targets.size()))));
	}

	private List<UUID> effectiveSelection(List<UUID> persisted, List<UUID> requested) {
		if (persisted == null) return requested;
		List<UUID> selected = validateSelection(persisted);
		if (requested != null && !new HashSet<>(selected).equals(new HashSet<>(requested))) {
			throw new CampaignValidationException("The campaign paper selection is fixed; use a new draft to select different papers");
		}
		return selected;
	}

	private List<UUID> validateSelection(List<UUID> paperIds) {
		if (paperIds == null) return null;
		if (paperIds.isEmpty() || paperIds.size() > Math.min(properties.maxRecipients(), 20)
				|| paperIds.stream().anyMatch(java.util.Objects::isNull)
				|| new HashSet<>(paperIds).size() != paperIds.size()) {
			throw new CampaignValidationException("Select between 1 and 20 distinct papers within the generation limit");
		}
		return List.copyOf(paperIds);
	}

	private Mono<List<SegmentRepository.CampaignCandidate>> candidates(
			UUID campaignId, SegmentModels.SegmentCriteria criteria, List<UUID> paperIds
	) {
		if (paperIds == null) return segments.campaignCandidates(criteria, properties.maxRecipients()).collectList();
		return segments.selectedPaperCandidates(criteria, paperIds).collectList()
				.flatMap(eligible -> segments.existingCampaignSelection(campaignId).collectList()
						.map(existing -> selectOneContactPerPaper(paperIds, eligible, existing)));
	}

	private List<SegmentRepository.CampaignCandidate> selectOneContactPerPaper(
			List<UUID> paperIds, List<SegmentRepository.CampaignCandidate> eligible,
			List<SegmentRepository.ExistingSelection> existing
	) {
		Map<UUID, List<SegmentRepository.CampaignCandidate>> byPaper = new LinkedHashMap<>();
		paperIds.forEach(paperId -> byPaper.put(paperId, new ArrayList<>()));
		for (SegmentRepository.CampaignCandidate candidate : eligible) {
			if (byPaper.containsKey(candidate.paperId())) byPaper.get(candidate.paperId()).add(candidate);
		}
		if (byPaper.values().stream().anyMatch(List::isEmpty)) {
			throw new CampaignValidationException("Every selected paper must have an eligible contact in this segment");
		}
		if (!existing.isEmpty()) return preserveExistingSelection(paperIds, byPaper, existing);

		Map<String, SegmentRepository.CampaignCandidate> assigned = new LinkedHashMap<>();
		for (UUID paperId : paperIds) {
			if (!assignDistinctContact(paperId, byPaper, assigned, new HashSet<>())) {
				throw new CampaignValidationException("Selected papers require one distinct eligible contact per paper");
			}
		}
		Map<UUID, SegmentRepository.CampaignCandidate> selected = new LinkedHashMap<>();
		assigned.values().forEach(candidate -> selected.put(candidate.paperId(), candidate));
		return paperIds.stream().map(selected::get).toList();
	}

	private List<SegmentRepository.CampaignCandidate> preserveExistingSelection(
			List<UUID> paperIds, Map<UUID, List<SegmentRepository.CampaignCandidate>> eligible,
			List<SegmentRepository.ExistingSelection> existing
	) {
		Map<UUID, SegmentRepository.ExistingSelection> previous = new LinkedHashMap<>();
		for (SegmentRepository.ExistingSelection row : existing) {
			if (row.paperId() == null || previous.put(row.paperId(), row) != null
					|| !Set.of("PENDING", "FAILED", "GENERATED").contains(row.personalizationStatus())) {
				throw changedSelection();
			}
		}
		if (!previous.keySet().equals(new HashSet<>(paperIds))) throw changedSelection();
		return paperIds.stream().map(paperId -> eligible.get(paperId).stream()
				.filter(candidate -> Arrays.equals(candidate.emailHmac(), previous.get(paperId).emailHmac()))
				.findFirst().orElseThrow(this::changedSelection)).toList();
	}

	/** Augmenting paths choose distinct addresses even when papers share their first-choice author. */
	private boolean assignDistinctContact(
			UUID paperId, Map<UUID, List<SegmentRepository.CampaignCandidate>> eligible,
			Map<String, SegmentRepository.CampaignCandidate> assigned, Set<String> visited
	) {
		for (SegmentRepository.CampaignCandidate candidate : eligible.get(paperId)) {
			String key = Base64.getEncoder().encodeToString(candidate.emailHmac());
			if (!visited.add(key)) continue;
			SegmentRepository.CampaignCandidate previous = assigned.get(key);
			if (previous == null || assignDistinctContact(previous.paperId(), eligible, assigned, visited)) {
				assigned.put(key, candidate);
				return true;
			}
		}
		return false;
	}

	private CampaignValidationException changedSelection() {
		return new CampaignValidationException(
				"Existing recipients must match the complete selected paper and eligible contact set; use a new draft otherwise");
	}

	private Mono<PersonalizationCommandMessage> createMessage(
			CampaignRepository.GenerationContext generation, AuthenticationRequestContext context,
			UUID jobId, UUID messageId, List<CampaignRepository.QueuedTarget> targets
	) {
		return createMessage(generation, context, jobId, messageId, targets, false);
	}

	private Mono<PersonalizationCommandMessage> createMessage(
			CampaignRepository.GenerationContext generation, AuthenticationRequestContext context,
			UUID jobId, UUID messageId, List<CampaignRepository.QueuedTarget> targets, boolean paperContact
	) {
		if (targets.stream().anyMatch(target -> target.authorName() == null ? !paperContact : target.authorName().isBlank())) {
			return Mono.error(new CampaignValidationException("Unknown author identity requires explicit paper-contact mode"));
		}
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
				record.createdAt(), record.updatedAt(), record.selectionCounts(), record.migrationReferences());
	}

	private RecipientView recipient(CampaignRepository.RecipientRecord record) {
		CampaignPublicContentRedactor.RedactedContent content = contentRedactor.redact(
				record.subject(), record.html(), record.text(), record.trackingArtifactsFrozen());
		return new RecipientView(
				record.id(), record.authorName(), record.paperTitle(), record.category(), record.organization(),
				record.personalizationStatus(), content.subject(), content.html(), content.text(), record.rationale(),
				record.errorCode(), record.errorMessage(), record.personalizedAt(), record.createdAt(),
				content.trackingArtifactsRedacted(), record.transfer());
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
			UUID statusChangedBy, Instant createdAt, Instant updatedAt,
            SelectionCounts selectionCounts, List<MigrationReference> migrationReferences
	) {
        public CampaignView(UUID id, String name, String purpose, String status, UUID templateId, String templateName,
			int templateVersion, UUID segmentId, String segmentName, UUID smtpAccountId, String smtpName,
			UUID mailboxAccountId, String fromName, String fromEmail, String replyTo,
			boolean trackingOpensEnabled, boolean trackingClicksEnabled, String generationStatus,
			String generationProvider, String generationModel, UUID generationJobId,
			RecipientCounts recipientCounts, DeliveryCounts deliveryCounts, long lockVersion,
			Instant submittedForReviewAt, Instant approvedAt, UUID approvedBy,
			Instant rejectedAt, UUID rejectedBy, String rejectionReason, Instant scheduledAt,
			Instant startedAt, Instant completedAt, Instant canceledAt, Instant statusChangedAt,
			UUID statusChangedBy, Instant createdAt, Instant updatedAt) {
            this(id, name, purpose, status, templateId, templateName, templateVersion, segmentId, segmentName, smtpAccountId, smtpName, mailboxAccountId, fromName, fromEmail, replyTo, trackingOpensEnabled, trackingClicksEnabled, generationStatus, generationProvider, generationModel, generationJobId, recipientCounts, deliveryCounts, lockVersion, submittedForReviewAt, approvedAt, approvedBy, rejectedAt, rejectedBy, rejectionReason, scheduledAt, startedAt, completedAt, canceledAt, statusChangedAt, statusChangedBy, createdAt, updatedAt, null, null);
        }
    }

	public record RecipientView(
			UUID id, String authorName, String paperTitle, String category, String organization,
			String personalizationStatus, String subject, String html, String text, String rationale,
			String errorCode, String errorMessage, Instant personalizedAt, Instant createdAt,
			boolean trackingArtifactsRedacted,
            RecipientTransfer transfer
	) {
        public RecipientView(UUID id, String authorName, String paperTitle, String category, String organization,
			String personalizationStatus, String subject, String html, String text, String rationale,
			String errorCode, String errorMessage, Instant personalizedAt, Instant createdAt,
			boolean trackingArtifactsRedacted) {
            this(id, authorName, paperTitle, category, organization, personalizationStatus, subject, html, text, rationale, errorCode, errorMessage, personalizedAt, createdAt, trackingArtifactsRedacted, null);
        }
    }

	public record GenerationStart(UUID jobId, int queuedRecipients) { }
}
