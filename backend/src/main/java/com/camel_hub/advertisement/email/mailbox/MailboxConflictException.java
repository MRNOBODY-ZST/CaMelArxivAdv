package com.camel_hub.advertisement.email.mailbox;

public class MailboxConflictException extends RuntimeException {
	public MailboxConflictException(String message) {
		super(message);
	}
}
