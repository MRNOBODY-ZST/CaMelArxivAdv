package com.camel_hub.advertisement.email.mailbox;

import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MailboxTransportTest {

	private final MailboxProperties properties = new MailboxProperties(
			true, Set.of("mail-test"), Duration.ofSeconds(3), Duration.ofSeconds(7), 50);
	private final MailboxTransport transport = new MailboxTransport(
			new SmtpSecretCrypto(Base64.getEncoder().encodeToString(
					"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))),
			new MailboxPolicy(properties), properties);

	@Test
	void configuresRequiredStartTlsAndHostnameVerification() {
		Properties values = transport.configurationFor(record(
				MailboxModels.Protocol.IMAP, MailboxModels.TlsMode.STARTTLS_REQUIRED));

		assertThat(values.getProperty("mail.imap.starttls.enable")).isEqualTo("true");
		assertThat(values.getProperty("mail.imap.starttls.required")).isEqualTo("true");
		assertThat(values.getProperty("mail.imap.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(values.getProperty("mail.imap.connectiontimeout")).isEqualTo("3000");
	}

	@Test
	void configuresImplicitPop3TlsWithoutStartTlsFallback() {
		Properties values = transport.configurationFor(record(
				MailboxModels.Protocol.POP3, MailboxModels.TlsMode.TLS_IMPLICIT));

		assertThat(values.getProperty("mail.pop3.ssl.enable")).isEqualTo("true");
		assertThat(values.getProperty("mail.pop3.starttls.enable")).isEqualTo("false");
		assertThat(values.getProperty("mail.pop3.ssl.checkserveridentity")).isEqualTo("true");
	}

	private MailboxRepository.MailboxAccountRecord record(
			MailboxModels.Protocol protocol, MailboxModels.TlsMode tlsMode
	) {
		return new MailboxRepository.MailboxAccountRecord(
				UUID.randomUUID(), "Mailbox", protocol, "mail-test", 3143, tlsMode,
				"user@example.org", new byte[16], new byte[12], "INBOX", true,
				null, null, null, 0, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now());
	}
}
