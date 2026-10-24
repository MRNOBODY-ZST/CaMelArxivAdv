package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.email.template.*;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyOutboundPreparer.PreparedSafetyOutbound;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PartnerContentIntegrationUnitTest {
	private static final String HTML = "<a href=\"{{partner_claim_url}}\" rel=\"noopener noreferrer\">Activate</a><a href=\"{{unsubscribe_url}}\" rel=\"noopener noreferrer\">Stop</a>";
	private static final String TEXT = "Activate {{partner_claim_url}} Stop {{unsubscribe_url}}";
	@Test
	void previewOverridesCallerCapabilityWithoutAnyPartnerDependency() {
		var service = new TemplateService(null, new TemplateEngine(102400), null, null, null, null);
		var result = service.preview(new TemplateService.TemplateCommand("Invite", null, "DRAFT",
				new TemplateModels.TemplateDraft("Invite", "Team", "reply@example.org", HTML, TEXT, false)),
				Map.of("partner_claim_url", "https://api.camel-hub.cn/partner/claim?token=private", "unsubscribe_url", "https://example.invalid/stop"));
		assertThat(result.rendered().html()).contains("https://example.invalid/partner-activation-preview").doesNotContain("private");
	}
	@Test
	void autoTextDerivesBothActivationAndUnsubscribeDestinationsForPreview() {
		var service = new TemplateService(null, new TemplateEngine(102400), null, null, null, null);
		var result = service.preview(new TemplateService.TemplateCommand("Invite", null, "DRAFT",
				new TemplateModels.TemplateDraft("Invite", "Team", "reply@example.org", HTML, "stale", true)),
				Map.of("unsubscribe_url", "https://example.invalid/stop"));
		assertThat(result.rendered().text()).contains("Activate (https://example.invalid/partner-activation-preview)", "Stop (https://example.invalid/stop)");
	}
	@Test
	void safetyOutboundIsInertWithoutAnyPartnerDependency() {
		var outbound = new PreparedSafetyOutbound("test@example.org", "Invite", HTML, TEXT, Map.of());
		assertThat(outbound.html()).contains("https://example.invalid/partner-activation-preview").doesNotContain("partner_claim_url");
		assertThat(outbound.text()).contains("https://example.invalid/partner-activation-preview");
	}
	@Test
	void manualRationaleAndAuditReasonCannotStoreRawPartnerCapabilities() {
		var transactions=mock(TransactionalOperator.class);
		when(transactions.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
		var repository=mock(CampaignRecipientContentRepository.class);
		when(repository.lockCampaign(any())).thenReturn(Mono.empty());
		var service=new CampaignRecipientContentService(repository,new TemplateEngine(102400),null,null,transactions);
		String raw="https://api.camel-hub.cn/partner/claim?token=private";
		assertThatThrownBy(() -> service.update(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
				new CampaignRecipientContentService.EditCommand(1,"Invite",HTML,TEXT,raw,"Reason")).block()).isInstanceOf(CampaignValidationException.class);
		assertThatThrownBy(() -> service.update(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
				new CampaignRecipientContentService.EditCommand(1,"Invite",HTML,TEXT,"Rationale",raw)).block()).isInstanceOf(CampaignValidationException.class);
		verifyNoInteractions(repository);
	}
	@Test
	void manualEditRetainsOptionalPartnerPlaceholderAndInvalidatesReview() {
		UUID campaign = UUID.randomUUID(), recipient = UUID.randomUUID(), actor = UUID.randomUUID();
		var repository = mock(CampaignRecipientContentRepository.class);
		var audit = mock(AuditService.class);
		var transactions = mock(TransactionalOperator.class);
		when(transactions.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
		when(repository.lockCampaign(campaign)).thenReturn(Mono.just(new CampaignRecipientContentRepository.CampaignLock(campaign, "DRAFT", "COMPLETED", 2, "Team", "reply@example.org")));
		when(repository.lockRecipient(campaign, recipient)).thenReturn(Mono.just(new CampaignRecipientContentRepository.RecipientLock(recipient, "Old", "old", "old", "old", true)));
		when(repository.updateRecipient(eq(campaign), eq(recipient), anyString(), anyString(), anyString(), anyString()))
				.thenAnswer(call -> Mono.just(new CampaignRecipientContentRepository.EditedRecipient(recipient, call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5))));
		when(repository.invalidateReview(campaign, 2, actor)).thenReturn(Mono.just(new CampaignRecipientContentRepository.CampaignEdit(campaign, 3, Instant.now())));
		when(audit.record(any())).thenReturn(Mono.empty());
		var service = new CampaignRecipientContentService(repository, new TemplateEngine(102400), audit, mock(SensitiveValueHasher.class), transactions);
		var edited = service.update(campaign, recipient, actor, null,
				new CampaignRecipientContentService.EditCommand(2, "Invite", HTML, TEXT, "Paper rationale", "Add opt-in")).block();
		assertThat(edited.html()).contains("{{partner_claim_url}}");
		assertThat(edited.text()).isEqualTo(TEXT);
		assertThat(edited.lockVersion()).isEqualTo(3);
	}
}
