package com.camel_hub.advertisement.contact;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
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

	public record BatchVerificationItemRequest(
			@NotNull UUID contactId,
			@NotNull UUID mappingId,
			@Min(0) long expectedVersion
	) {
		ContactService.BatchVerificationItem command() {
			return new ContactService.BatchVerificationItem(contactId, mappingId, expectedVersion);
		}
	}

	public record BatchVerificationRequest(
			@NotNull @Size(min = 1, max = 100) List<@Valid BatchVerificationItemRequest> items,
			@NotNull @Pattern(regexp = "CONFIRMED|REJECTED") String status
	) {
		List<ContactService.BatchVerificationItem> commands() {
			return items.stream().map(BatchVerificationItemRequest::command).toList();
		}
	}
}
