package com.camel_hub.advertisement.email.mailbox;

public class MailboxTransportException extends RuntimeException {
	private final FailureCategory category;

	public MailboxTransportException(FailureCategory category) {
		super("Mailbox operation failed: " + category.name());
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
		FOLDER_NOT_FOUND,
		PROTOCOL_REJECTED,
		UNEXPECTED_FAILURE
	}
}
