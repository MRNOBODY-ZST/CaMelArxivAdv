package com.camel_hub.advertisement.email.smtp;

public final class SmtpModels {
	private SmtpModels() {
	}

	public enum TlsMode {
		STARTTLS_REQUIRED,
		TLS_IMPLICIT,
		PLAIN_LOCAL_ONLY
	}
}
