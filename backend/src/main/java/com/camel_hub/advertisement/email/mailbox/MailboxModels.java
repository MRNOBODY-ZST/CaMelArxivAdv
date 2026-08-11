package com.camel_hub.advertisement.email.mailbox;

public final class MailboxModels {
	private MailboxModels() {
	}

	public enum Protocol {
		IMAP,
		POP3
	}

	public enum TlsMode {
		STARTTLS_REQUIRED,
		TLS_IMPLICIT,
		PLAIN_LOCAL_ONLY
	}
}
