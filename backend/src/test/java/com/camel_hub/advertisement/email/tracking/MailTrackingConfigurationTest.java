package com.camel_hub.advertisement.email.tracking;

import io.r2dbc.spi.ConnectionFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;

class MailTrackingConfigurationTest {
	private static final String KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";
	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(MailTrackingConfiguration.class)
			.withPropertyValues("spring.profiles.active=api", "app.mail-tracking.public-base-url=http://localhost:8080")
			.withBean(DatabaseClient.class, () -> DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://unused:unused@localhost/unused")));

	@Test
	void disabledConfigurationStartsWithoutAKeyAndEnabledConfigurationFailsClosedWithoutOne() {
		context.withPropertyValues("app.mail-tracking.enabled=false").run(application -> {
			assertThat(application).hasNotFailed();
			assertThat(application.getBean(MailTrackingService.class).status().enabled()).isFalse();
		});
		context.withPropertyValues("app.mail-tracking.enabled=true").run(application -> assertThat(application).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"app.auth.signing-key-base64", "app.auth.fingerprint-hmac-key-base64",
			"app.smtp.encryption-key-base64", "app.template.assets.signing-key-base64",
			"app.contact.data-protection.encryption-key-base64", "app.contact.data-protection.email-hmac-key-base64"})
	void anEnabledTrackingKeyCannotReuseAnExistingApplicationKey(String otherKey) {
		context.withPropertyValues("app.mail-tracking.enabled=true", "app.mail-tracking.signing-key-base64=" + KEY,
				otherKey + "=" + KEY).run(application -> {
			assertThat(application).hasFailed();
			assertThat(application.getStartupFailure()).hasRootCauseMessage("Tracking signing key must be independent of other application keys");
		});
	}
}
