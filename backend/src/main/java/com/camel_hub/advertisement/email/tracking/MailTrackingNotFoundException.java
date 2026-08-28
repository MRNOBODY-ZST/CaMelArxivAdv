package com.camel_hub.advertisement.email.tracking;

public class MailTrackingNotFoundException extends RuntimeException {
	public MailTrackingNotFoundException() {
		super("Mail send record was not found");
	}
}
