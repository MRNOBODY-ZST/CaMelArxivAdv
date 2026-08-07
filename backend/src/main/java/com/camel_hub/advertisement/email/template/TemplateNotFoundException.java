package com.camel_hub.advertisement.email.template;

public class TemplateNotFoundException extends RuntimeException {
	public TemplateNotFoundException() {
		super("Email template was not found");
	}
}
