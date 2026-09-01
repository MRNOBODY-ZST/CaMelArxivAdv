package com.camel_hub.advertisement.email.tracking;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSendReconciliationJobTest {
	private static final String KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

	@Test
	void aSlowReconciliationCannotOverlapTheNextScheduledTrigger() {
		MailTrackingRepository repository = mock(MailTrackingRepository.class);
		when(repository.reconcileStale(any(), any())).thenReturn(Mono.never());
		MailSendReconciliationJob job = new MailSendReconciliationJob(repository,
				new MailTrackingProperties(true, "http://localhost:8080", KEY, Duration.ofDays(30)),
				Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC));

		job.reconcile();
		job.reconcile();

		verify(repository, times(1)).reconcileStale(any(), any());
	}
}
