package com.camel_hub.advertisement.campaign;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
}
