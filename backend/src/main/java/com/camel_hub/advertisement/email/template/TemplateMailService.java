package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.email.smtp.SmtpService;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

public final class TemplateMailService {

	private final TemplateRepository templates;
	private final TemplateEngine engine;
	private final SmtpService smtp;
	private final TemplateAssetSigner assetSigner;

	public TemplateMailService(
			TemplateRepository templates, TemplateEngine engine, SmtpService smtp,
			TemplateAssetSigner assetSigner
	) {
		this.templates = templates;
		this.engine = engine;
		this.smtp = smtp;
		this.assetSigner = assetSigner;
	}

	public Mono<SmtpService.TestResult> sendTest(
			UUID actorId, UUID templateId, UUID smtpAccountId, String recipient,
			Map<String, String> variables, AuthenticationRequestContext context
	) {
		String safeRecipient = email(recipient);
		return Mono.zip(
				templates.find(templateId).switchIfEmpty(Mono.error(new TemplateNotFoundException())),
				smtp.account(smtpAccountId))
				.flatMap(tuple -> {
					TemplateRepository.TemplateRecord template = tuple.getT1();
					var prepared = new TemplateModels.PreparedTemplate(
							template.subjectTemplate(), template.fromNameTemplate(), template.replyTo(),
							template.htmlContent(), template.textContent(), template.autoGenerateText(), template.contentSizeBytes(),
							template.validation());
					TemplateModels.RenderedTemplate rendered = engine.render(prepared, variables);
					String correlationId = UUID.randomUUID().toString();
					return smtp.send(tuple.getT2(), new SmtpTransport.OutboundMessage(
							safeRecipient, rendered.subject(), rendered.fromName(), rendered.replyTo(),
							assetSigner.absolutizeHtml(rendered.html()), rendered.text(), correlationId), actorId, context,
							"TEMPLATE_TEST_SEND");
				});
	}

	private String email(String value) {
		String normalized = value == null ? "" : value.strip();
		try {
			InternetAddress address = new InternetAddress(normalized, true);
			if (!address.getAddress().equals(normalized) || normalized.contains("\r") || normalized.contains("\n")) {
				throw new AddressException();
			}
			return normalized;
		}
		catch (AddressException exception) {
			throw new TemplateValidationException("Test recipient is invalid");
		}
	}
}
