package com.camel_hub.advertisement.job.service;

public class JobNotFoundException extends RuntimeException {
	public JobNotFoundException() {
		super("Job was not found");
	}
}
