package com.camel_hub.advertisement.email.template;

import java.util.List;
import java.util.Set;

public final class TemplateModels {

	private TemplateModels() {
	}

	public record TemplateDraft(
			String subjectTemplate,
			String fromNameTemplate,
			String replyTo,
			String htmlContent,
			String textContent,
			boolean autoGenerateText
	) { }

	public record ValidationResult(
			boolean valid,
			List<String> errors,
			List<String> warnings,
			Set<String> variables
	) { }

	public record PreparedTemplate(
			String subjectTemplate,
			String fromNameTemplate,
			String replyTo,
			String sanitizedHtml,
			String textContent,
			boolean autoGenerateText,
			int contentSizeBytes,
			ValidationResult validation
	) { }

	public record RenderedTemplate(String subject, String fromName, String replyTo, String html, String text) { }
}
