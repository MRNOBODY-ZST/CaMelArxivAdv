package com.camel_hub.advertisement.email.tracking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTrackingPropertiesTest {
	private static final String KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

	@ParameterizedTest
	@ValueSource(strings = {"http://localhost:8080", "https://localhost", "http://127.0.0.1", "http://10.1.2.3",
			"http://172.16.1.2", "http://192.168.1.2", "http://169.254.1.2", "http://[::1]", "http://[fd00::1]",
			"http://[fe80::1]", "https://mailpit", "https://test.local", "https://10.0.0.1", "http://[::ffff:127.0.0.1]"})
	void localOriginsRemainLocalEvenOverHttps(String origin) {
		assertThat(properties(origin).callbackScope()).isEqualTo(MailTrackingModels.CallbackScope.LOCAL_ONLY);
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://mail.example.org", "https://8.8.8.8", "https://[2001:4860:4860::8888]"})
	void publicHttpsOnlyDescribesUnverifiedConfiguration(String origin) {
		assertThat(properties(origin).callbackScope()).isEqualTo(MailTrackingModels.CallbackScope.PUBLIC_HTTPS_UNVERIFIED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "/relative", "//localhost", "http://example.org", "http://8.8.8.8", "https://example.org/",
			"https://example.org/path", "https://user:secret@example.org", "https://example.org?token=secret", "https://example.org#fragment",
			"ftp://localhost", "https://localhost:0", "https://localhost:65536", "https://localhost:-1"})
	void rejectsNonOriginAndInsecurePublicUrlsWithoutExposingTheirContents(String origin) {
		assertThatThrownBy(() -> properties(origin)).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Tracking callback URL must be an absolute origin; public hosts require HTTPS");
	}

	@Test
	void onlyEnabledTrackingNeedsAKeyAndTtlHasAnExplicitSafeBound() {
		assertThat(new MailTrackingProperties(false, "http://localhost:8080", "", null).tokenTtl()).isEqualTo(Duration.ofDays(30));
		assertThatThrownBy(() -> new MailTrackingProperties(true, "http://localhost:8080", "", null)).isInstanceOf(IllegalArgumentException.class);
		for (Duration ttl : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(59), Duration.ofDays(91),
				Duration.ofSeconds(60).plusNanos(1)}) {
			assertThatThrownBy(() -> new MailTrackingProperties(false, "http://localhost:8080", "", ttl))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(new MailTrackingProperties(true, "http://localhost:8080", KEY, Duration.ofMinutes(1)).tokenTtl()).isEqualTo(Duration.ofMinutes(1));
		assertThat(new MailTrackingProperties(true, "http://localhost:8080", KEY, Duration.ofDays(90)).tokenTtl()).isEqualTo(Duration.ofDays(90));
	}

	@Test
	void staleSendingWindowDefaultsToFifteenMinutesAndHasSafeBounds() {
		assertThat(new MailTrackingProperties(false, "http://localhost:8080", "", null, null).staleSendingAfter())
				.isEqualTo(Duration.ofMinutes(15));
		assertThat(new MailTrackingProperties(false, "http://localhost:8080", "", Duration.ofDays(30),
				Duration.ofMinutes(5)).staleSendingAfter()).isEqualTo(Duration.ofMinutes(5));
		assertThat(new MailTrackingProperties(false, "http://localhost:8080", "", Duration.ofDays(30),
				Duration.ofDays(1)).staleSendingAfter()).isEqualTo(Duration.ofDays(1));
		for (Duration window : new Duration[] {Duration.ofMinutes(5).minusSeconds(1), Duration.ofDays(1).plusSeconds(1),
				Duration.ofMinutes(15).plusNanos(1)}) {
			assertThatThrownBy(() -> new MailTrackingProperties(false, "http://localhost:8080", "",
					Duration.ofDays(30), window)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	private MailTrackingProperties properties(String origin) {
		return new MailTrackingProperties(true, origin, KEY, Duration.ofDays(30));
	}
}
