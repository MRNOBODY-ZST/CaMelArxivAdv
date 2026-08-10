package com.camel_hub.advertisement.job.domain;

public enum JobStatus {
	PENDING,
	QUEUED,
	RUNNING,
	PAUSED,
	SUCCEEDED,
	PARTIALLY_SUCCEEDED,
	FAILED,
	CANCELED;

	public boolean isTerminal() {
		return switch (this) {
			case SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, CANCELED -> true;
			default -> false;
		};
	}
}
