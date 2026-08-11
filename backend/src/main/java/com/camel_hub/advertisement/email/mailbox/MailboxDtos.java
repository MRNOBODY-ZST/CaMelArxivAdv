package com.camel_hub.advertisement.email.mailbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MailboxDtos {
	private MailboxDtos() {
	}

	public record UpsertRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Pattern(regexp = "IMAP|POP3") String protocol,
			@NotBlank @Size(max = 255) String host,
			@Min(1) @Max(65_535) int port,
			@NotBlank @Pattern(regexp = "STARTTLS_REQUIRED|TLS_IMPLICIT|PLAIN_LOCAL_ONLY") String tlsMode,
			@NotBlank @Size(max = 255) String username,
			@Size(max = 1_024) String password,
			@NotBlank @Size(max = 255) String folderName,
			boolean enabled
	) {
		MailboxService.MailboxCommand command() {
			return new MailboxService.MailboxCommand(
					name, protocol, host, port, tlsMode, username, password, folderName, enabled);
		}
	}

	public record UpdateRequest(@Min(0) long expectedLockVersion, @NotNull @Valid UpsertRequest account) { }
}
