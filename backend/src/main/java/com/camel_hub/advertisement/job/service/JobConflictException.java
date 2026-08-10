package com.camel_hub.advertisement.job.service;

public class JobConflictException extends RuntimeException {
	public JobConflictException(String message) {
		super(message);
	}
}
