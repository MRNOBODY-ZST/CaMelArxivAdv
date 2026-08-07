package com.camel_hub.advertisement.email.smtp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpPolicyTest {

	private final SmtpProperties localOnly = new SmtpProperties(
			false, Set.of("mailpit", "localhost", "127.0.0.1"), Duration.ofSeconds(5),
			Duration.ofSeconds(10), Duration.ofSeconds(10), "test-key");

	@Test
	void allowsOnlyExactLocalHostsAndPlainModeWhenLiveSmtpIsDisabled() {
		SmtpPolicy policy = new SmtpPolicy(localOnly);

		assertThatCode(() -> policy.validateDestination("mailpit", 1025, SmtpModels.TlsMode.PLAIN_LOCAL_ONLY))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.validateDestination("mailpit.evil.example", 1025,
				SmtpModels.TlsMode.PLAIN_LOCAL_ONLY)).isInstanceOf(SmtpValidationException.class);
		assertThatThrownBy(() -> policy.validateDestination("smtp.example.org", 587,
				SmtpModels.TlsMode.STARTTLS_REQUIRED)).isInstanceOf(SmtpValidationException.class);
		assertThatThrownBy(() -> policy.validateDestination("mailpit", 1025,
				SmtpModels.TlsMode.STARTTLS_REQUIRED)).isInstanceOf(SmtpValidationException.class);
	}
}
