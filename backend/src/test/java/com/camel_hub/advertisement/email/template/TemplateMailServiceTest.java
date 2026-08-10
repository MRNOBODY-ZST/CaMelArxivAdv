package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpService;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateMailServiceTest {

	@Test
	void convertsSignedRelativeAssetUrlsToTheConfiguredAbsoluteOriginBeforeSmtp() {
		UUID templateId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		UUID smtpId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		TemplateAssetSigner signer = new TemplateAssetSigner(
				"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "http://localhost:8080");
		String assetPath = signer.path(templateId, assetId);
		var validation = new TemplateModels.ValidationResult(
				true, List.of(), List.of(), Set.of("unsubscribe_url"));
		var template = new TemplateRepository.TemplateRecord(
				templateId, "With image", null, TemplateRepository.TemplateStatus.DRAFT,
				1, 0, actorId, actorId, Instant.now(), Instant.now(), "Subject", "Research Team",
				"reply@example.org", "<p><img src=\"" + assetPath + "\"></p>"
						+ "<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
				"Unsubscribe {{unsubscribe_url}}", false, 100, validation, Instant.now());
		TemplateRepository templates = mock(TemplateRepository.class);
		SmtpService smtp = mock(SmtpService.class);
		SmtpRepository.SmtpAccountRecord account = mock(SmtpRepository.SmtpAccountRecord.class);
		when(templates.find(templateId)).thenReturn(Mono.just(template));
		when(smtp.account(smtpId)).thenReturn(Mono.just(account));
		when(smtp.send(eq(account), any(), eq(actorId), any(), eq("TEMPLATE_TEST_SEND")))
				.thenReturn(Mono.just(new SmtpService.TestResult("SMTP_ACCEPTED", null, "correlation")));
		TemplateMailService service = new TemplateMailService(
				templates, new TemplateEngine(102_400), smtp, signer);

		service.sendTest(actorId, templateId, smtpId, "qa@example.org",
				Map.of("unsubscribe_url", "https://example.org/unsubscribe/1"),
				new AuthenticationRequestContext("127.0.0.1", "JUnit", "mail-asset")).block();

		ArgumentCaptor<SmtpTransport.OutboundMessage> message =
				ArgumentCaptor.forClass(SmtpTransport.OutboundMessage.class);
		verify(smtp).send(eq(account), message.capture(), eq(actorId), any(), eq("TEMPLATE_TEST_SEND"));
		assertThat(message.getValue().html())
				.contains("src=\"http://localhost:8080/api/v1/template-assets/")
				.doesNotContain("src=\"/api/v1/template-assets/");
	}
}
