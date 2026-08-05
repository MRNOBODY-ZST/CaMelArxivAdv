package com.camel_hub.advertisement.identity.service;

public final class LoginRateLimitedException extends RuntimeException {

	public LoginRateLimitedException() {
		super("Too many login attempts; try again later");
	}
}
