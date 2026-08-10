package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.campaign.CampaignRepository;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalizationResultHandlerTest {

	private static final UUID CAMPAIGN = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID RECIPIENT = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID JOB = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private CampaignRepository repository;
	private PersonalizationResultHandler handler;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		repository = mock(CampaignRepository.class);
		TransactionalOperator transactions = mock(TransactionalOperator.class);
		when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
		objectMapper = new ObjectMapper().findAndRegisterModules();
		handler = new PersonalizationResultHandler(
				repository, new TemplateEngine(102_400), objectMapper, transactions);
	}

	@Test
	void sanitizesAndStoresAValidGeneratedDraftThenRefreshesCampaignState() throws Exception {
		var message = message("GENERATED",
				"A safe subject",
				"<p>Hello Ada<script>alert(1)</script></p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
				"Hello Ada {{unsubscribe_url}}", "Connected the invitation to the paper", null, null);
		when(repository.markPersonalizationResultProcessed(message.messageId(), message.idempotencyKey()))
				.thenReturn(Mono.just(true));
		when(repository.resultContext(CAMPAIGN, RECIPIENT, JOB)).thenReturn(Mono.just(
				new CampaignRepository.ResultContext("Research Team", "reply@example.org")));
		when(repository.storeGenerated(eq(CAMPAIGN), eq(RECIPIENT), eq(JOB), any()))
				.thenReturn(Mono.just(true));
		when(repository.refreshGenerationState(CAMPAIGN, JOB)).thenReturn(Mono.empty());

		var result = handler.handle(objectMapper.writeValueAsString(message)).block();

		assertThat(result.duplicate()).isFalse();
		ArgumentCaptor<CampaignRepository.GeneratedDraft> draft =
				ArgumentCaptor.forClass(CampaignRepository.GeneratedDraft.class);
		verify(repository).storeGenerated(eq(CAMPAIGN), eq(RECIPIENT), eq(JOB), draft.capture());
		assertThat(draft.getValue().html()).doesNotContain("script", "alert(1)")
				.contains("{{unsubscribe_url}}");
		assertThat(draft.getValue().rationale()).isEqualTo("Connected the invitation to the paper");
		verify(repository).refreshGenerationState(CAMPAIGN, JOB);
	}

	@Test
	void rejectsMissingUnsubscribeAndHeaderInjectionBeforePersistence() throws Exception {
		var message = message("GENERATED", "Hello\r\nBcc: hidden@example.org",
				"<p>Hello Ada</p>", "Hello Ada", "Rationale", null, null);
		when(repository.markPersonalizationResultProcessed(message.messageId(), message.idempotencyKey()))
				.thenReturn(Mono.just(true));
		when(repository.resultContext(CAMPAIGN, RECIPIENT, JOB)).thenReturn(Mono.just(
				new CampaignRepository.ResultContext("Research Team", "reply@example.org")));

		assertThatThrownBy(() -> handler.handle(objectMapper.writeValueAsString(message)).block())
				.isInstanceOf(IllegalArgumentException.class);
		verify(repository, never()).storeGenerated(any(), any(), any(), any());
	}

	@Test
	void storesProviderFailureWithoutDraftContentAndHandlesDuplicateMessages() throws Exception {
		var failed = message("FAILED", null, null, null, null, "RATE_LIMITED", "Provider rate limited");
		when(repository.markPersonalizationResultProcessed(failed.messageId(), failed.idempotencyKey()))
				.thenReturn(Mono.just(true));
		when(repository.resultContext(CAMPAIGN, RECIPIENT, JOB)).thenReturn(Mono.just(
				new CampaignRepository.ResultContext("Research Team", "reply@example.org")));
		when(repository.storeFailed(CAMPAIGN, RECIPIENT, JOB, "RATE_LIMITED", "Provider rate limited"))
				.thenReturn(Mono.just(true));
		when(repository.refreshGenerationState(CAMPAIGN, JOB)).thenReturn(Mono.empty());

		assertThat(handler.handle(objectMapper.writeValueAsString(failed)).block().duplicate()).isFalse();
		verify(repository).storeFailed(CAMPAIGN, RECIPIENT, JOB, "RATE_LIMITED", "Provider rate limited");

		var duplicate = message("FAILED", null, null, null, null, "RATE_LIMITED", "Provider rate limited");
		when(repository.markPersonalizationResultProcessed(duplicate.messageId(), duplicate.idempotencyKey()))
				.thenReturn(Mono.just(false));
		assertThat(handler.handle(objectMapper.writeValueAsString(duplicate)).block().duplicate()).isTrue();
	}

	private PersonalizationResultMessage message(
			String status, String subject, String html, String text, String rationale,
			String errorCode, String errorMessage
	) {
		UUID messageId = UUID.randomUUID();
		return new PersonalizationResultMessage(
				1, messageId, "PERSONALIZATION_RESULT", JOB, CAMPAIGN, RECIPIENT,
				"result:" + messageId, "resulttrace1", Instant.now(),
				new PersonalizationResultMessage.Payload(
						status, subject, html, text, rationale, "openai", "gpt-test", errorCode, errorMessage));
	}
}
