package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Transactional deterministic bridge into the normal campaign review/send workflow. */
public final class OutreachTemplateCampaignService {
	private final CampaignRepository campaigns;
	private final SegmentRepository segments;
	private final CampaignService campaignService;
	private final CampaignWorkflowService workflow;
	private final OutreachTemplateCampaignRepository metadata;
	private final CampaignRecipientContentRepository contents;
	private final OutreachTemplateContentPolicy contentPolicy;
	private final CampaignProductionSelectionPolicy selectionPolicy;
	private final TransactionalOperator transactions;

	public OutreachTemplateCampaignService(CampaignRepository campaigns, SegmentRepository segments,
			CampaignService campaignService, CampaignWorkflowService workflow, OutreachTemplateCampaignRepository metadata,
			CampaignRecipientContentRepository contents, OutreachTemplateContentPolicy contentPolicy,
			CampaignProductionSelectionPolicy selectionPolicy, TransactionalOperator transactions) {
		this.campaigns = campaigns;
		this.segments = segments;
		this.campaignService = campaignService;
		this.workflow = workflow;
		this.metadata = metadata;
		this.contents = contents;
		this.contentPolicy = contentPolicy;
		this.selectionPolicy = selectionPolicy;
		this.transactions = transactions;
	}

	/** Returns an overflow sentinel (121), sufficient for either supported target mode. */
	public Mono<Integer> eligibleCount(List<UUID> paperIds) {
		return Mono.defer(() -> segments.paperContactCandidates(new UUID(0, 0), papers(paperIds),
				selectionPolicy.cooldownCutoff(), selectionPolicy.excludedHmacs()).count().map(Math::toIntExact));
	}

	/** Frozen child is a bounded subset, not a re-selection of the first 120 contacts. */
	public Mono<List<UUID>> unsafeBulkPaperSources(List<UUID> parentPaperIds) {
		return campaigns.lockPaperContactSelection().thenMany(segments.paperContactCandidatePage(new UUID(0, 0),
				parentPaperIds, selectionPolicy.cooldownCutoff(), selectionPolicy.excludedHmacs(), 120))
				.collectList().map(candidates -> {
					var rejected = new java.util.LinkedHashSet<UUID>();
					for (var candidate : candidates) {
						try {
							contentPolicy.validateSource(new CampaignRepository.QueuedTarget(candidate.contactId(), candidate.authorName(),
									candidate.paperTitle(), candidate.abstractText(), candidate.arxivId(), candidate.primaryCategory(), candidate.organization()));
						}
						catch (CampaignValidationException invalidSource) { rejected.add(candidate.paperId()); }
					}
					return List.copyOf(rejected);
				});
	}

	/** Frozen child is a bounded subset, not a re-selection of the first 120 contacts. */
	public Mono<CampaignService.CampaignView> prepareBulk(UUID actorId, UUID runId, UUID templateId,
			UUID smtpAccountId, UUID mailboxAccountId, String purpose, List<UUID> parentPaperIds) {
		return Mono.defer(() -> {
			if (actorId == null || runId == null || templateId == null || smtpAccountId == null
					|| parentPaperIds == null || parentPaperIds.isEmpty() || parentPaperIds.size() > 10000)
				return Mono.error(invalid("Bulk campaign preparation request is invalid"));
			return campaigns.lockPaperContactSelection().thenMany(segments.paperContactCandidatePage(new UUID(0, 0),
					parentPaperIds, selectionPolicy.cooldownCutoff(), selectionPolicy.excludedHmacs(), 120)).collectList()
					.flatMap(candidates -> {
						if (candidates.isEmpty()) return Mono.empty();
						var selected = new java.util.ArrayList<SegmentRepository.CampaignCandidate>();
						var manifest = new java.util.LinkedHashSet<UUID>();
						for (var candidate : candidates) {
							if (!manifest.contains(candidate.paperId()) && manifest.size() == 100) break;
							manifest.add(candidate.paperId()); selected.add(candidate);
						}
						String name = "Bulk outreach " + runId + " " + UUID.randomUUID();
						return metadata.sender(smtpAccountId, mailboxAccountId)
								.flatMap(sender -> segments.create(name, "Frozen bulk child source manifest", actorId)
										.flatMap(segmentId -> campaignService.create(actorId, new CampaignService.CampaignCommand(name, purpose, templateId, segmentId, smtpAccountId)))
										.flatMap(created -> workflow.update(created.id(), actorId, context(runId), created.lockVersion(),
												new CampaignWorkflowService.CampaignUpdateCommand(name, created.purpose(), sender.mailboxId(), sender.fromName(), sender.replyTo(), false, false)))
										.flatMap(configured -> generateSelected(configured.id(), runId, List.copyOf(manifest), selected)))
								.flatMap(id -> review(id).then(campaignService.get(id)));
					});
		}).as(transactions::transactional);
	}


	public Mono<CampaignService.CampaignView> prepare(UUID actorId, UUID runId, UUID templateId, UUID smtpAccountId,
			UUID mailboxAccountId, String purpose, List<UUID> paperIds, Integer exactEmailCount) {
		return Mono.defer(() -> {
			if (actorId == null || runId == null || templateId == null || smtpAccountId == null
					|| exactEmailCount != null && (exactEmailCount < 1 || exactEmailCount > 120)) {
				return Mono.error(invalid("Outreach campaign preparation request is invalid"));
			}
			List<UUID> manifest = papers(paperIds);
			String name = "Outreach " + runId;
			return campaigns.lockPaperContactSelection().then(metadata.sender(smtpAccountId, mailboxAccountId))
					.flatMap(sender -> segments.create(name, "Frozen paper manifest for outreach run " + runId, actorId)
							.flatMap(segmentId -> campaignService.create(actorId, new CampaignService.CampaignCommand(name, purpose, templateId, segmentId, smtpAccountId)))
							.flatMap(created -> workflow.update(created.id(), actorId, context(runId), created.lockVersion(),
									new CampaignWorkflowService.CampaignUpdateCommand(name, created.purpose(), sender.mailboxId(), sender.fromName(), sender.replyTo(), false, false)))
							.flatMap(configured -> generate(configured.id(), runId, manifest, exactEmailCount)))
					.flatMap(id -> review(id).then(campaignService.get(id)));
		}).as(transactions::transactional);
	}

	private Mono<UUID> generate(UUID campaignId, UUID runId, List<UUID> manifest, Integer exactEmailCount) {
		return segments.paperContactCandidates(campaignId, manifest, selectionPolicy.cooldownCutoff(), selectionPolicy.excludedHmacs())
				.collectList().map(candidates -> contentPolicy.select(candidates, exactEmailCount))
				.flatMap(selected -> generateSelected(campaignId, runId, manifest, selected));
	}

	private Mono<UUID> generateSelected(UUID campaignId, UUID runId, List<UUID> manifest, List<SegmentRepository.CampaignCandidate> selected) {
		return Mono.defer(() ->
						campaigns.preparePaperContactGeneration(campaignId, runId, "TEMPLATE", OutreachTemplateContentPolicy.VERSION, manifest, selected.size())
								.flatMap(prepared -> prepared ? metadata.lockContext(campaignId)
										.switchIfEmpty(Mono.error(invalid("Campaign sender or template is unavailable")))
										: Mono.error(invalid("Campaign generation could not be frozen")))
								.flatMap(context -> {
									if (!context.accountsReady()) return Mono.error(invalid("Campaign accounts are no longer ready"));
									return Flux.fromIterable(selected).concatMap(candidate -> campaigns.queueRecipient(campaignId, candidate)
											.switchIfEmpty(Mono.error(invalid("Campaign membership changed during selection")))
											.flatMap(target -> {
												var source = new CampaignRepository.FrozenProductionRecipient(candidate.contactId(), candidate.paperId(), candidate.authorId(), candidate.emailHmac(), "GENERATED", 0, true, target);
												var draft = contentPolicy.render(context.template(), target);
												return campaigns.storeGenerated(campaignId, target.recipientId(), runId, draft)
														.flatMap(stored -> stored ? metadata.saveReview(campaignId, target.recipientId(), contentPolicy.sourceHash(context.template(), source), contentPolicy.contentHash(draft))
																: Mono.error(invalid("Campaign generated content could not be stored"))).thenReturn(target);
											})).count().flatMap(count -> count == selected.size()
												? campaigns.refreshGenerationState(campaignId, runId).thenReturn(campaignId)
												: Mono.error(invalid("Frozen recipient count changed during generation")));
								}));
	}

	/** Validation only. Never refreshes hashes or upgrades changed content into a new review. */
	public Mono<Void> review(UUID campaignId) {
		return Mono.defer(() -> {
			if (campaignId == null) return Mono.error(invalid("Campaign id is required"));
			return metadata.lockContext(campaignId).switchIfEmpty(Mono.error(invalid("Outreach campaign was not found")))
					.flatMap(context -> {
						if (!Set.of("DRAFT", "READY_FOR_REVIEW", "APPROVED").contains(context.status())
								|| !"COMPLETED".equals(context.generationStatus()) || !"TEMPLATE".equals(context.provider())
								|| !OutreachTemplateContentPolicy.VERSION.equals(context.model()) || !"PAPER_CONTACT".equals(context.recipientPolicy())
								|| !context.accountsReady() || context.frozenCount() == null || context.frozenCount() < 1 || context.frozenCount() > 120) {
							return Mono.error(invalid("Outreach campaign is not a pristine, complete template selection"));
						}
						papers(context.paperIds());
						return campaigns.frozenProductionRecipients(campaignId, selectionPolicy.cooldownCutoff(), selectionPolicy.excludedHmacs())
								.collectList().flatMap(recipients -> {
									if (recipients.size() != context.frozenCount()) return Mono.error(invalid("Frozen recipient count no longer matches"));
									return metadata.reviewCount(campaignId).flatMap(count -> count == recipients.size()
											? Flux.fromIterable(recipients).concatMap(source -> verifyRecipient(context, source)).then()
											: Mono.error(invalid("Every frozen recipient must have its original automatic review")));
								});
					});
		}).as(transactions::transactional);
	}

	private Mono<Void> verifyRecipient(OutreachTemplateCampaignRepository.Context context, CampaignRepository.FrozenProductionRecipient source) {
		if (!source.currentlyEligible() || source.attemptCount() != 0 || !"GENERATED".equals(source.personalizationStatus())
				|| !context.paperIds().contains(source.paperId())) return Mono.error(invalid("Frozen recipient is no longer eligible or pristine"));
		return contents.lockRecipient(context.campaignId(), source.target().recipientId())
				.switchIfEmpty(Mono.error(invalid("Frozen recipient is missing"))).flatMap(stored -> {
					if (!stored.pristine()) return Mono.error(invalid("A recipient with delivery artifacts cannot be automatically reviewed"));
					return metadata.review(context.campaignId(), stored.id()).switchIfEmpty(Mono.error(invalid("Frozen recipient review is missing")))
							.doOnNext(review -> contentPolicy.verifyReview(context.template(), source,
									new CampaignRepository.GeneratedDraft(stored.subject(), stored.html(), stored.text(), stored.rationale()),
									review.policyVersion(), review.sourceHash(), review.contentHash())).then();
				});
	}

	private List<UUID> papers(List<UUID> ids) {
		if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) {
			throw invalid("Outreach requires 1–100 distinct paper ids");
		}
		return List.copyOf(ids);
	}

	private AuthenticationRequestContext context(UUID runId) {
		return new AuthenticationRequestContext("127.0.0.1", "AutoOutreach", runId.toString().replace("-", ""));
	}

	private CampaignValidationException invalid(String message) { return new CampaignValidationException(message); }
}
