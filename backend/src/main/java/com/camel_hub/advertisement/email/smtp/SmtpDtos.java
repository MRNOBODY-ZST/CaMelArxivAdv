package com.camel_hub.advertisement.email.smtp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SmtpDtos {
	private SmtpDtos() {
	}

	public record UpsertRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Size(max = 255) String host,
			@Min(1) @Max(65_535) int port,
			@NotBlank @Pattern(regexp = "STARTTLS_REQUIRED|TLS_IMPLICIT|PLAIN_LOCAL_ONLY") String tlsMode,
			@Size(max = 255) String username,
			@Size(max = 1_024) String password,
			@NotBlank @Email @Size(max = 320) String fromEmail,
			@NotBlank @Size(max = 160) String defaultFromName,
			@NotBlank @Email @Size(max = 320) String replyTo,
			@Min(1) int perMinuteLimit,
			@Min(1) int perHourLimit,
			@Min(1) int perDayLimit,
			@Min(1) int perDomainHourLimit,
			boolean enabled
	) {
		SmtpService.SmtpCommand command() {
			return new SmtpService.SmtpCommand(
					name, host, port, tlsMode, username, password, fromEmail, defaultFromName, replyTo,
					perMinuteLimit, perHourLimit, perDayLimit, perDomainHourLimit, enabled);
		}
	}

	public record UpdateRequest(@Min(0) long expectedLockVersion, @NotNull @Valid UpsertRequest account) { }
	public record ConnectionTestRequest(boolean acknowledgedLocalOnly) { }
	public record TestEmailRequest(
			@NotBlank @Email @Size(max = 320) String recipient,
			@NotBlank @Size(max = 200) String subject,
			@Size(max = 5_000) String body
	) { }
}
