package com.camel_hub.advertisement.job.service;

public class InvalidJobStateException extends RuntimeException {
	public InvalidJobStateException(String message) {
		super(message);
	}
}
