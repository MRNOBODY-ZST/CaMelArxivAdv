package com.camel_hub.advertisement.email.mailbox;

public class MailboxNotFoundException extends RuntimeException {
	public MailboxNotFoundException() {
		super("Mailbox account was not found");
	}
}
