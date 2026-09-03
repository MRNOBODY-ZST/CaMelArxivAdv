package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.campaign.CampaignRepository;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.template.TemplateModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Set;

public final class PersonalizationResultHandler {

	private static final int MAXIMUM_MESSAGE_CHARACTERS = 512 * 1024;
	private static final String UNSUBSCRIBE_PLACEHOLDER = "{{unsubscribe_url}}";
	private static final Set<String> STATUSES = Set.of("GENERATED", "FAILED");
	private final CampaignRepository repository;
	private final TemplateEngine templateEngine;
	private final ObjectMapper objectMapper;
	private final TransactionalOperator transactions;

	public PersonalizationResultHandler(
			CampaignRepository repository, TemplateEngine templateEngine,
			ObjectMapper objectMapper, TransactionalOperator transactions
	) {
		this.repository = repository;
		this.templateEngine = templateEngine;
		this.objectMapper = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		this.transactions = transactions;
	}

	public Mono<HandleResult> handle(String body) {
		return Mono.fromCallable(() -> parse(body))
				.flatMap(message -> repository.markPersonalizationResultProcessed(
						message.messageId(), message.idempotencyKey())
						.flatMap(inserted -> inserted ? apply(message) : Mono.just(new HandleResult(true))))
				.as(transactions::transactional);
	}

	private Mono<HandleResult> apply(PersonalizationResultMessage message) {
		return repository.resultContext(message.campaignId(), message.recipientId(), message.jobId())
				.switchIfEmpty(Mono.error(new IllegalArgumentException("Personalization result target is stale")))
				.flatMap(context -> "GENERATED".equals(message.payload().status())
						? storeGenerated(message, context)
						: storeFailed(message))
				.flatMap(updated -> updated
						? repository.refreshGenerationState(message.campaignId(), message.jobId())
								.thenReturn(new HandleResult(false))
						: Mono.error(new IllegalArgumentException("Personalization result target was not updated")));
	}

	private Mono<Boolean> storeGenerated(
			PersonalizationResultMessage message, CampaignRepository.ResultContext context
	) {
		PersonalizationResultMessage.Payload payload = message.payload();
		TemplateModels.PreparedTemplate prepared = templateEngine.prepare(new TemplateModels.TemplateDraft(
				payload.subject(), context.fromName(), context.replyTo(), payload.html(), payload.text(), false));
		if (!prepared.validation().valid()
				|| !prepared.validation().variables().equals(Set.of("unsubscribe_url"))) {
			throw new IllegalArgumentException("Generated draft did not pass the email safety policy");
		}
		var draft = new CampaignRepository.GeneratedDraft(
				prepared.subjectTemplate(), prepared.sanitizedHtml(), canonicalText(prepared.textContent()),
				payload.rationale().strip());
		return repository.storeGenerated(message.campaignId(), message.recipientId(), message.jobId(), draft);
	}

	private String canonicalText(String value) {
		int placeholder = value.indexOf(UNSUBSCRIBE_PLACEHOLDER);
		if (placeholder < 1 || Character.isWhitespace(value.charAt(placeholder - 1))) return value;
		return value.substring(0, placeholder) + " " + value.substring(placeholder);
	}

	private Mono<Boolean> storeFailed(PersonalizationResultMessage message) {
		return repository.storeFailed(
				message.campaignId(), message.recipientId(), message.jobId(),
				message.payload().errorCode(), safe(message.payload().errorMessage(), 500));
	}

	private PersonalizationResultMessage parse(String body) {
		if (body == null || body.isBlank() || body.length() > MAXIMUM_MESSAGE_CHARACTERS) {
			throw new IllegalArgumentException("Personalization result message size is invalid");
		}
		try {
			PersonalizationResultMessage message = objectMapper.readValue(body, PersonalizationResultMessage.class);
			validate(message);
			return message;
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Personalization result message is malformed", exception);
		}
	}

	private void validate(PersonalizationResultMessage message) {
		if (message == null || message.version() != 1 || message.messageId() == null
				|| !"PERSONALIZATION_RESULT".equals(message.type()) || message.jobId() == null
				|| message.campaignId() == null || message.recipientId() == null
				|| invalid(message.idempotencyKey(), 200) || message.traceId() == null
				|| !message.traceId().matches("[A-Za-z0-9_-]{8,64}") || message.occurredAt() == null
				|| message.payload() == null || !STATUSES.contains(message.payload().status())
				|| invalid(message.payload().provider(), 80) || invalid(message.payload().model(), 120)) {
			throw new IllegalArgumentException("Personalization result contract is invalid");
		}
		PersonalizationResultMessage.Payload payload = message.payload();
		if ("GENERATED".equals(payload.status())) {
			if (invalid(payload.subject(), 998) || invalid(payload.html(), 200_000)
					|| invalid(payload.text(), 100_000) || invalid(payload.rationale(), 2_000)
					|| payload.errorCode() != null || payload.errorMessage() != null) {
				throw new IllegalArgumentException("Generated personalization result is invalid");
			}
		}
		else if (payload.subject() != null || payload.html() != null || payload.text() != null
				|| payload.rationale() != null || payload.errorCode() == null
				|| !payload.errorCode().matches("[A-Z0-9_]{1,80}")
				|| invalid(payload.errorMessage(), 500)) {
			throw new IllegalArgumentException("Failed personalization result is invalid");
		}
	}

	private boolean invalid(String value, int maximum) {
		return value == null || value.isBlank() || value.length() > maximum;
	}

	private String safe(String value, int maximum) {
		String safe = value.replaceAll("[\\p{Cntrl}]", " ").strip();
		return safe.substring(0, Math.min(maximum, safe.length()));
	}

	public record HandleResult(boolean duplicate) { }
}
