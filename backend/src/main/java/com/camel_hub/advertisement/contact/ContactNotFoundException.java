package com.camel_hub.advertisement.contact;

public class ContactNotFoundException extends RuntimeException {
	public ContactNotFoundException() {
		super("Contact does not exist");
	}
}
