package com.camel_hub.advertisement.email.tracking;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class MailOpenClassifierTest {
	private final MailOpenClassifier classifier = new MailOpenClassifier();

	@Test
	void absentOrUnrecognizedSignalsAreNotCalledHumanReads() {
		assertThat(classifier.classify(new HttpHeaders()).classification()).isEqualTo(MailTrackingModels.Classification.UNCLASSIFIED);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/605.1.15");
		assertThat(classifier.classify(headers).classification()).isEqualTo(MailTrackingModels.Classification.UNCLASSIFIED);
	}

	@Test
	void recognizedPrefetchHeadersTakePrecedenceOverProxyAndBotSignatures() {
		for (String name : new String[] {"Purpose", "Sec-Purpose", "X-Purpose", "X-Moz"}) {
			HttpHeaders headers = new HttpHeaders();
			headers.set(name, "prefetch");
			headers.set(HttpHeaders.USER_AGENT, "GoogleImageProxy scanner");
			assertThat(classifier.classify(headers).classification()).isEqualTo(MailTrackingModels.Classification.PREFETCH);
		}
	}

	@Test
	void fingerprintInputIsBoundedAndDoesNotDependOnSourceIpOrUnboundedSuffixes() {
		HttpHeaders first = new HttpHeaders();
		first.set(HttpHeaders.USER_AGENT, "x".repeat(512) + "private suffix one");
		first.set("X-Forwarded-For", "192.0.2.1");
		HttpHeaders second = new HttpHeaders();
		second.set(HttpHeaders.USER_AGENT, "x".repeat(512) + "private suffix two");
		second.set("X-Forwarded-For", "198.51.100.1");
		assertThat(classifier.classify(first).fingerprintHash()).hasSize(32)
				.containsExactly(classifier.classify(second).fingerprintHash());
		second.set("Purpose", "prefetch");
		assertThat(classifier.classify(first).fingerprintHash()).isNotEqualTo(classifier.classify(second).fingerprintHash());
	}
}
