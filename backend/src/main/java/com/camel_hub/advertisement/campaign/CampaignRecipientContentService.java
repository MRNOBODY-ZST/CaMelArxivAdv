package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.template.TemplateModels;
import com.camel_hub.advertisement.email.template.TemplateValidationException;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CampaignRecipientContentService {
	private static final String UNSUBSCRIBE_PLACEHOLDER = "{{unsubscribe_url}}";
	private static final Pattern RECOGNIZED_UNSUBSCRIBE_PLACEHOLDER =
			Pattern.compile("\\{\\{\\s*unsubscribe_url\\s*}}");

	private final CampaignRecipientContentRepository repository;
	private final TemplateEngine templateEngine;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public CampaignRecipientContentService(
			CampaignRecipientContentRepository repository, TemplateEngine templateEngine,
			AuditService auditService, SensitiveValueHasher hasher, TransactionalOperator transactions
	) {
		this.repository = repository;
		this.templateEngine = templateEngine;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<EditResult> update(
			UUID campaignId, UUID recipientId, UUID actorId, AuthenticationRequestContext context,
			EditCommand command
	) {
		return Mono.defer(() -> {
			NormalizedCommand normalized = normalize(campaignId, recipientId, actorId, command);
			AuthenticationRequestContext requestContext = safeContext(context);
			return repository.lockCampaign(campaignId)
					.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
					.flatMap(campaign -> verifyCampaign(campaign, normalized.expectedLockVersion())
							.then(repository.lockRecipient(campaignId, recipientId)
									.switchIfEmpty(Mono.error(conflict())))
							.flatMap(recipient -> edit(
									campaign, recipient, actorId, requestContext, normalized)));
		}).as(transactions::transactional);
	}

	private Mono<EditResult> edit(
			CampaignRecipientContentRepository.CampaignLock campaign,
			CampaignRecipientContentRepository.RecipientLock recipient,
			UUID actorId, AuthenticationRequestContext context, NormalizedCommand command
	) {
		if (!recipient.pristine()) return Mono.error(conflict());
		PreparedContent content = prepare(campaign, command);
		String oldHash = contentHash(
				recipient.subject(), recipient.html(), recipient.text(), recipient.rationale());
		String newHash = contentHash(content.subject(), content.html(), content.text(), command.rationale());
		return repository.updateRecipient(
					campaign.id(), recipient.id(), content.subject(), content.html(), content.text(), command.rationale())
				.switchIfEmpty(Mono.error(conflict()))
				.flatMap(edited -> repository.invalidateReview(
						campaign.id(), command.expectedLockVersion(), actorId)
						.switchIfEmpty(Mono.error(conflict()))
						.flatMap(campaignEdit -> audit(
								campaign.id(), recipient.id(), actorId, context,
								command.expectedLockVersion(), campaignEdit.lockVersion(),
								oldHash, newHash, command.reason())
								.thenReturn(new EditResult(
										campaign.id(), recipient.id(), campaignEdit.lockVersion(),
										edited.subject(), edited.html(), edited.text(), edited.rationale(),
										campaignEdit.updatedAt()))));
	}

	private Mono<Void> verifyCampaign(
			CampaignRecipientContentRepository.CampaignLock campaign, long expectedLockVersion
	) {
		boolean generationTerminal = Set.of("COMPLETED", "PARTIALLY_FAILED", "FAILED")
				.contains(campaign.generationStatus());
		return campaign.lockVersion() == expectedLockVersion && "DRAFT".equals(campaign.status())
				&& generationTerminal ? Mono.empty() : Mono.error(conflict());
	}

	private PreparedContent prepare(
			CampaignRecipientContentRepository.CampaignLock campaign, NormalizedCommand command
	) {
		try {
			TemplateModels.PreparedTemplate prepared = templateEngine.prepare(new TemplateModels.TemplateDraft(
					command.subject(), campaign.fromName(), campaign.replyTo(),
					command.html(), command.text(), false));
			if (!prepared.validation().valid()) {
				throw new CampaignValidationException("Recipient content did not pass email safety validation");
			}
			if (prepared.subjectTemplate().codePoints().anyMatch(Character::isISOControl)) {
				throw new CampaignValidationException("Recipient subject contains an unsafe control character");
			}
			if (!prepared.validation().variables().contains("unsubscribe_url")
					|| !Set.of("unsubscribe_url", "partner_claim_url").containsAll(prepared.validation().variables())) {
				throw new CampaignValidationException(
						"Recipient content must retain unsubscribe_url and only optional partner_claim_url");
			}
			if (RECOGNIZED_UNSUBSCRIBE_PLACEHOLDER.matcher(prepared.subjectTemplate()).find()
					|| !prepared.sanitizedHtml().contains(UNSUBSCRIBE_PLACEHOLDER)
					|| !prepared.textContent().contains(UNSUBSCRIBE_PLACEHOLDER)) {
				throw new CampaignValidationException(
						"Recipient HTML and plain text must each retain the exact unsubscribe_url placeholder");
			}
			if (prepared.validation().warnings().contains("Unsafe or unsupported HTML was removed")) {
				throw new CampaignValidationException("Recipient HTML contains unsafe or unsupported content");
			}
			return new PreparedContent(
					prepared.subjectTemplate(), prepared.sanitizedHtml(), prepared.textContent());
		}
		catch (TemplateValidationException exception) {
			throw new CampaignValidationException("Recipient content did not pass email safety validation");
		}
	}

	private NormalizedCommand normalize(
			UUID campaignId, UUID recipientId, UUID actorId, EditCommand command
	) {
		if (campaignId == null || recipientId == null || actorId == null || command == null
				|| command.expectedLockVersion() < 0) {
			throw new CampaignValidationException("Recipient content correction request is invalid");
		}
		return new NormalizedCommand(
				command.expectedLockVersion(), command.subject(), command.html(), command.text(),
				multiline(command.rationale(), 2_000, "Recipient personalization rationale"),
				multiline(command.reason(), 1_000, "Recipient content correction reason"));
	}

	private String multiline(String value, int maximum, String label) {
		String normalized = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		if (com.camel_hub.advertisement.campaign.partner.PartnerContentPolicy.containsCapability(normalized)) {
			throw new CampaignValidationException(label + " contains a private capability");
		}
		boolean invalidControl = normalized.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
				&& codePoint != '\r' && codePoint != '\n' && codePoint != '\t');
		if (normalized.isEmpty() || normalized.length() > maximum || invalidControl) {
			throw new CampaignValidationException(label + " is invalid");
		}
		return normalized;
	}

	private Mono<Void> audit(
			UUID campaignId, UUID recipientId, UUID actorId, AuthenticationRequestContext context,
			long beforeVersion, long afterVersion, String oldHash, String newHash, String reason
	) {
		return auditService.record(new AuditEvent(
				actorId, "CAMPAIGN_RECIPIENT_CONTENT_UPDATED", "CAMPAIGN_RECIPIENT", recipientId.toString(),
				hasher.hash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
				Map.of("campaignId", campaignId, "lockVersion", beforeVersion, "contentHash", oldHash),
				Map.of("campaignId", campaignId, "lockVersion", afterVersion, "contentHash", newHash,
						"reason", reason), AuditResult.SUCCESS, null));
	}

	private String contentHash(String subject, String html, String text, String rationale) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String value : new String[] {subject, html, text, rationale}) {
				if (value == null) {
					digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
					continue;
				}
				byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
				digest.update(bytes);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private AuthenticationRequestContext safeContext(AuthenticationRequestContext context) {
		if (context != null && context.ipAddress() != null && context.userAgentSummary() != null
				&& context.traceId() != null && context.traceId().matches("[A-Za-z0-9_-]{8,64}")) {
			return context;
		}
		return new AuthenticationRequestContext(
				"unknown", "unknown", UUID.randomUUID().toString().replace("-", ""));
	}

	private CampaignConflictException conflict() {
		return new CampaignConflictException(
				"Campaign recipient state or version changed; refresh before continuing");
	}

	public record EditCommand(
			long expectedLockVersion, String subject, String html, String text, String rationale, String reason
	) { }

	public record EditResult(
			UUID campaignId, UUID recipientId, long lockVersion,
			String subject, String html, String text, String rationale, Instant updatedAt
	) { }

	private record NormalizedCommand(
			long expectedLockVersion, String subject, String html, String text, String rationale, String reason
	) { }

	private record PreparedContent(String subject, String html, String text) { }
}
