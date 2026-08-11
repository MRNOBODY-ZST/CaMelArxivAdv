package com.camel_hub.advertisement.email.mailbox;

import java.util.Locale;
import java.util.regex.Pattern;

public final class MailboxPolicy {
	private static final Pattern HOSTNAME = Pattern.compile(
			"(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*"
					+ "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
	private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");

	private final MailboxProperties properties;

	public MailboxPolicy(MailboxProperties properties) {
		this.properties = properties;
	}

	public void validateDestination(String host, int port, MailboxModels.TlsMode tlsMode) {
		String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > 255 || normalized.endsWith(".")
				|| normalized.contains("/") || normalized.contains(":") || normalized.contains("@")
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new MailboxValidationException("Mailbox host is invalid");
		}
		if (port < 1 || port > 65_535 || tlsMode == null) {
			throw new MailboxValidationException("Mailbox port or TLS mode is invalid");
		}
		boolean local = properties.localAllowedHosts().stream()
				.map(value -> value.strip().toLowerCase(Locale.ROOT)).anyMatch(normalized::equals);
		if (local) {
			return;
		}
		if (!HOSTNAME.matcher(normalized).matches() || IPV4_LITERAL.matcher(normalized).matches()) {
			throw new MailboxValidationException("Public mailbox hosts must use a DNS hostname");
		}
		if (!properties.publicAllowed()) {
			throw new MailboxValidationException("Public mailbox connections are disabled");
		}
		if (tlsMode == MailboxModels.TlsMode.PLAIN_LOCAL_ONLY) {
			throw new MailboxValidationException("Plain mailbox connections are permitted only for an allowlisted local host");
		}
	}
}
