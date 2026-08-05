package com.camel_hub.advertisement.identity.service;

public final class AdministrationNotFoundException extends RuntimeException {

	public AdministrationNotFoundException(String resource) {
		super(resource + " was not found");
	}
}
