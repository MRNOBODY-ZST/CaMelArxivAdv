package com.camel_hub.advertisement.identity.service;

public final class AuthenticationFailedException extends RuntimeException {

	public AuthenticationFailedException() {
		super("Invalid username/email or password");
	}
}
