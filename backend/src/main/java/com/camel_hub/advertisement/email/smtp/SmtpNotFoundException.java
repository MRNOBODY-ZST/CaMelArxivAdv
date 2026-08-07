package com.camel_hub.advertisement.email.smtp;

public class SmtpNotFoundException extends RuntimeException {
	public SmtpNotFoundException() {
		super("SMTP account was not found");
	}
}
