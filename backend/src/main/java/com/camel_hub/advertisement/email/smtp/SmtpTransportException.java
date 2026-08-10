package com.camel_hub.advertisement.email.smtp;

public class SmtpTransportException extends RuntimeException {

	private final FailureCategory category;

	public SmtpTransportException(FailureCategory category) {
		super("SMTP operation failed: " + category.name());
		this.category = category;
	}

	public FailureCategory category() {
		return category;
	}

	public enum FailureCategory {
		CONNECTION_TIMEOUT,
		DNS_FAILURE,
		TLS_FAILURE,
		AUTHENTICATION_FAILED,
		CONNECTION_REJECTED,
		SMTP_REJECTED,
		UNEXPECTED_FAILURE
	}
}
