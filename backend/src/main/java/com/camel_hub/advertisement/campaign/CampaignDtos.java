package com.camel_hub.advertisement.campaign;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class CampaignDtos {

	private CampaignDtos() { }

	public record CreateRequest(
			@NotBlank @Size(max = 200) String name,
			@NotBlank @Size(max = 4000) String purpose,
			@NotNull UUID templateId,
			@NotNull UUID segmentId,
			@NotNull UUID smtpAccountId
	) {
		CampaignService.CampaignCommand command() {
			return new CampaignService.CampaignCommand(name, purpose, templateId, segmentId, smtpAccountId);
		}
	}

	public record UpdateRequest(
			@NotNull @PositiveOrZero Long expectedLockVersion,
			@NotBlank @Size(max = 200) String name,
			@NotBlank @Size(max = 4000) String purpose,
			@NotNull UUID mailboxAccountId,
			@NotBlank @Size(max = 160) String fromName,
			@NotBlank @Email @Size(max = 320) String replyTo,
			@NotNull Boolean trackingOpensEnabled,
			@NotNull Boolean trackingClicksEnabled
	) {
		CampaignWorkflowService.CampaignUpdateCommand command() {
			return new CampaignWorkflowService.CampaignUpdateCommand(
					name, purpose, mailboxAccountId, fromName, replyTo,
					trackingOpensEnabled, trackingClicksEnabled);
		}
	}

	public record WorkflowRequest(@NotNull @PositiveOrZero Long expectedLockVersion) { }

	public record RejectRequest(
			@NotNull @PositiveOrZero Long expectedLockVersion,
			@NotBlank @Size(max = 1000) String reason
	) { }

	public record ScheduleRequest(
			@NotNull @PositiveOrZero Long expectedLockVersion,
			@NotNull Instant scheduledAt
	) { }
}
