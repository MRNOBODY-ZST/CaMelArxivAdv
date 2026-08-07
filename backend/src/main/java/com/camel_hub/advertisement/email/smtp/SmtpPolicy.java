package com.camel_hub.advertisement.email.smtp;

import java.util.Locale;

public final class SmtpPolicy {

	private final SmtpProperties properties;

	public SmtpPolicy(SmtpProperties properties) {
		this.properties = properties;
	}

	public void validateDestination(String host, int port, SmtpModels.TlsMode tlsMode) {
		String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > 255 || normalized.endsWith(".")
				|| normalized.contains("/") || normalized.contains(":")) {
			throw new SmtpValidationException("SMTP host is invalid");
		}
		if (port < 1 || port > 65_535 || tlsMode == null) {
			throw new SmtpValidationException("SMTP port or TLS mode is invalid");
		}
		if (!properties.liveAllowed()) {
			boolean allowed = properties.localAllowedHosts().stream()
					.map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals);
			if (!allowed || tlsMode != SmtpModels.TlsMode.PLAIN_LOCAL_ONLY) {
				throw new SmtpValidationException("Live SMTP is disabled; only an allowlisted local plain destination is permitted");
			}
		}
		else if (tlsMode == SmtpModels.TlsMode.PLAIN_LOCAL_ONLY
				&& properties.localAllowedHosts().stream().noneMatch(normalized::equalsIgnoreCase)) {
			throw new SmtpValidationException("Plain SMTP is permitted only for an allowlisted local destination");
		}
	}
}
