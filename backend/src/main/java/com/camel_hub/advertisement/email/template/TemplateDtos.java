package com.camel_hub.advertisement.email.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public final class TemplateDtos {
	private TemplateDtos() {
	}

	public record ContentRequest(
			@NotBlank @Size(max = 998) String subjectTemplate,
			@NotBlank @Size(max = 160) String fromNameTemplate,
			@NotBlank @Email @Size(max = 320) String replyTo,
			@NotBlank String htmlContent,
			String textContent,
			boolean autoGenerateText
	) {
		TemplateModels.TemplateDraft draft() {
			return new TemplateModels.TemplateDraft(
					subjectTemplate, fromNameTemplate, replyTo, htmlContent, textContent, autoGenerateText);
		}
	}

	public record UpsertRequest(
			@NotBlank @Size(max = 160) String name,
			@Size(max = 500) String description,
			@NotBlank @Pattern(regexp = "DRAFT|ACTIVE|ARCHIVED") String status,
			@NotNull @Valid ContentRequest content
	) {
		TemplateService.TemplateCommand command() {
			return new TemplateService.TemplateCommand(name, description, status, content.draft());
		}
	}

	public record UpdateRequest(@Min(0) long expectedLockVersion, @NotNull @Valid UpsertRequest template) { }
	public record RestoreRequest(@Min(0) long expectedLockVersion) { }
	public record CopyRequest(@NotBlank @Size(max = 160) String name) { }

	public record PreviewRequest(
			@NotNull @Valid UpsertRequest template,
			@NotNull Map<@Pattern(regexp = "[a-z_]+") String, @Size(max = 2_048) String> variables
	) { }

	public record TestSendRequest(
			@NotNull UUID smtpAccountId,
			@NotBlank @Email @Size(max = 320) String recipient,
			@NotNull Map<@Pattern(regexp = "[a-z_]+") String, @Size(max = 2_048) String> variables
	) { }
}
