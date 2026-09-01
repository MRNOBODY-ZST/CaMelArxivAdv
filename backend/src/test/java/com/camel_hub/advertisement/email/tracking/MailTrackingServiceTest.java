package com.camel_hub.advertisement.email.tracking;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailTrackingServiceTest {
	private static final String KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

	@Test
	void aStalledObservationCannotDiscardAnAlreadyResolvedRedirect() {
		Instant now = Instant.parse("2026-09-01T08:00:00Z");
		UUID recordId = UUID.randomUUID();
		UUID linkId = UUID.randomUUID();
		String target = "https://example.invalid/paper/42";
		MailTrackingSigner signer = new MailTrackingSigner(KEY);
		String token = signer.issueClick(recordId, linkId, now.plusSeconds(60));
		MailTrackingModels.ResolvedClick resolved = new MailTrackingModels.ResolvedClick(recordId, linkId, target);
		MailTrackingRepository repository = mock(MailTrackingRepository.class);
		when(repository.resolveClick(any(), eq(now))).thenReturn(Mono.just(resolved));
		when(repository.observeClick(eq(linkId), any(), eq(now))).thenReturn(Mono.never());
		MailTrackingService service = new MailTrackingService(repository,
				new MailTrackingProperties(true, "http://localhost:8080", KEY, Duration.ofDays(30)),
				signer, new MailOpenClassifier(), Clock.fixed(now, ZoneOffset.UTC));

		StepVerifier.create(service.click(token, new HttpHeaders(), true))
				.expectNext(resolved)
				.expectComplete()
				.verify(Duration.ofSeconds(1));
	}
}
