package com.camel_hub.advertisement.contact;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public final class ContactDtos {

	private ContactDtos() {
	}

	public record VerificationRequest(
			@NotNull UUID mappingId,
			@Min(0) long expectedVersion,
			@NotNull @Pattern(regexp = "CONFIRMED|REJECTED") String status
	) {
		ContactService.VerificationCommand command() {
			return new ContactService.VerificationCommand(mappingId, expectedVersion, status);
		}
	}
}
