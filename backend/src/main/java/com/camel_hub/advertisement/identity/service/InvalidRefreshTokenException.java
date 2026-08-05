package com.camel_hub.advertisement.identity.service;

public final class InvalidRefreshTokenException extends RuntimeException {

	public InvalidRefreshTokenException() {
		super("Session is invalid or expired");
	}
}
