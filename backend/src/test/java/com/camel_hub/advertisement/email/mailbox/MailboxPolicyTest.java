package com.camel_hub.advertisement.email.mailbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailboxPolicyTest {

	private final MailboxProperties publicMail = new MailboxProperties(
			true, Set.of("mail-test", "localhost", "127.0.0.1"),
			Duration.ofSeconds(5), Duration.ofSeconds(10), 50);

	@Test
	void requiresTlsForPublicHostsAndRejectsIpLiterals() {
		MailboxPolicy policy = new MailboxPolicy(publicMail);

		assertThatCode(() -> policy.validateDestination(
				"imap.example.org", 993, MailboxModels.TlsMode.TLS_IMPLICIT)).doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.validateDestination(
				"imap.example.org", 143, MailboxModels.TlsMode.PLAIN_LOCAL_ONLY))
				.isInstanceOf(MailboxValidationException.class);
		assertThatThrownBy(() -> policy.validateDestination(
				"203.0.113.8", 993, MailboxModels.TlsMode.TLS_IMPLICIT))
				.isInstanceOf(MailboxValidationException.class);
		assertThatThrownBy(() -> policy.validateDestination(
				"[2001:db8::1]", 993, MailboxModels.TlsMode.TLS_IMPLICIT))
				.isInstanceOf(MailboxValidationException.class);
	}

	@Test
	void allowsPlainOnlyForAnExactAllowlistedLocalHost() {
		MailboxPolicy policy = new MailboxPolicy(publicMail);

		assertThatCode(() -> policy.validateDestination(
				"mail-test", 3143, MailboxModels.TlsMode.PLAIN_LOCAL_ONLY)).doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.validateDestination(
				"mail-test.evil.example", 3143, MailboxModels.TlsMode.PLAIN_LOCAL_ONLY))
				.isInstanceOf(MailboxValidationException.class);
	}

	@Test
	void canDisableAllPublicMailboxConnections() {
		MailboxPolicy policy = new MailboxPolicy(new MailboxProperties(
				false, Set.of("mail-test"), Duration.ofSeconds(5), Duration.ofSeconds(10), 50));

		assertThatThrownBy(() -> policy.validateDestination(
				"imap.example.org", 993, MailboxModels.TlsMode.TLS_IMPLICIT))
				.isInstanceOf(MailboxValidationException.class);
	}
}
