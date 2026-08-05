package com.camel_hub.advertisement.job.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class JobStateMachine {

	private static final Map<JobStatus, Map<JobAction, JobStatus>> TRANSITIONS = transitions();

	public JobStatus transition(JobStatus status, JobAction action) {
		JobStatus target = TRANSITIONS.getOrDefault(status, Map.of()).get(action);
		if (target == null) {
			throw new IllegalStateException("Job in " + status + " cannot perform " + action);
		}
		return target;
	}

	public Set<JobAction> allowedActions(JobStatus status) {
		return Set.copyOf(TRANSITIONS.getOrDefault(status, Map.of()).keySet());
	}

	private static Map<JobStatus, Map<JobAction, JobStatus>> transitions() {
		Map<JobStatus, Map<JobAction, JobStatus>> values = new EnumMap<>(JobStatus.class);
		values.put(JobStatus.PENDING, Map.of(JobAction.CANCEL, JobStatus.CANCELED));
		values.put(JobStatus.QUEUED, Map.of(
				JobAction.PAUSE, JobStatus.PAUSED,
				JobAction.CANCEL, JobStatus.CANCELED));
		values.put(JobStatus.RUNNING, Map.of(
				JobAction.PAUSE, JobStatus.PAUSED,
				JobAction.CANCEL, JobStatus.CANCELED));
		values.put(JobStatus.PAUSED, Map.of(
				JobAction.RESUME, JobStatus.QUEUED,
				JobAction.CANCEL, JobStatus.CANCELED));
		values.put(JobStatus.SUCCEEDED, Map.of());
		values.put(JobStatus.PARTIALLY_SUCCEEDED, Map.of(JobAction.RETRY, JobStatus.PENDING));
		values.put(JobStatus.FAILED, Map.of(JobAction.RETRY, JobStatus.PENDING));
		values.put(JobStatus.CANCELED, Map.of(JobAction.RETRY, JobStatus.PENDING));
		return Map.copyOf(values);
	}
}
