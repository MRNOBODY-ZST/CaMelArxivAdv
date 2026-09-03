package com.camel_hub.advertisement.email.mailbox;

import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.campaign.inbound.InboundMailModels;
import jakarta.mail.Session;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@Test
	void extractsOnlyBoundedReplyHeadersAndStructuredDeliveryStatus() throws Exception {
		MimeMessage deliveryStatus = new MimeMessage(Session.getInstance(new Properties()));
		deliveryStatus.setHeader("Message-ID", "<inbound@example.test>");
		deliveryStatus.setHeader("In-Reply-To",
				"<10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>");
		deliveryStatus.setHeader("Auto-Submitted", "auto-generated");
		MimeMultipart report = new MimeMultipart("report");
		MimeBodyPart explanation = new MimeBodyPart();
		explanation.setText("This private body must never enter the inbound envelope");
		report.addBodyPart(explanation);
		MimeBodyPart status = new MimeBodyPart(new ByteArrayInputStream((
				"Content-Type: message/delivery-status\r\n\r\n"
				+ "Reporting-MTA: dns; mx.example.test\r\n\r\n"
				+ "Action: failed\r\nStatus: 5.1.1\r\nDiagnostic-Code: smtp; 550 unavailable\r\n"
				+ "Original-Message-ID: <10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>\r\n")
				.getBytes(StandardCharsets.US_ASCII)));
		report.addBodyPart(status);
		deliveryStatus.setContent(report);
		deliveryStatus.saveChanges();

		InboundMailModels.InboundEnvelope envelope = transport.envelope(7, deliveryStatus);

		assertThat(envelope.remoteUid()).isEqualTo(7);
		assertThat(envelope.inReplyTo()).contains("10000000-0000-0000-0000-000000000001");
		assertThat(envelope.autoSubmitted()).isEqualTo("auto-generated");
		assertThat(envelope.receivedAt()).isNull();
		assertThat(envelope.dsn()).isEqualTo(new InboundMailModels.DsnFields(
				"failed", "5.1.1", "smtp; 550 unavailable",
				"<10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>"));
		assertThat(envelope.toString()).doesNotContain("private body", "mx.example.test");
	}

	@Test
	void oversizedHeadersBecomeMalformedWithoutCopyingTheirValue() throws Exception {
		MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setHeader("References", "x".repeat(5_000));
		message.setText("ignored");
		message.saveChanges();

		InboundMailModels.InboundEnvelope envelope = transport.envelope(9, message);

		assertThat(envelope.malformed()).isTrue();
		assertThat(envelope.remoteUid()).isEqualTo(9);
		assertThat(envelope.toString()).doesNotContain("x".repeat(100));
	}

	@Test
	void rejectsReportsWithTooManyPartsOrACombinedMetadataBudgetOverflow() throws Exception {
		MimeMessage tooMany = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart manyParts = new MimeMultipart("report");
		for (int index = 0; index < 17; index++) {
			MimeBodyPart part = new MimeBodyPart();
			part.setText("ignored");
			manyParts.addBodyPart(part);
		}
		tooMany.setContent(manyParts);
		tooMany.saveChanges();

		MimeMessage tooLarge = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart largeReport = new MimeMultipart("report");
		for (int index = 0; index < 2; index++) {
			String metadata = "Content-Type: message/delivery-status\r\n\r\n"
					+ "Action: delayed\r\nStatus: 4.2.0\r\nX-Padding: " + "x".repeat(39_000) + "\r\n";
			largeReport.addBodyPart(new MimeBodyPart(new ByteArrayInputStream(
					metadata.getBytes(StandardCharsets.US_ASCII))));
		}
		tooLarge.setContent(largeReport);
		tooLarge.saveChanges();

		assertThat(transport.envelope(10, tooMany).malformed()).isTrue();
		assertThat(transport.envelope(11, tooLarge).malformed()).isTrue();
	}

	@Test
	void readsOnlyAttachedOriginalHeadersWithoutCallingNestedGetContent() throws Exception {
		MimeMessage reportMessage = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart report = new MimeMultipart("report");
		MimeBodyPart status = new MimeBodyPart(new ByteArrayInputStream((
				"Content-Type: message/delivery-status\r\n\r\nAction: failed\r\nStatus: 5.1.1\r\n")
				.getBytes(StandardCharsets.US_ASCII)));
		report.addBodyPart(status);
		report.addBodyPart(new HeaderOnlyOriginalPart(
				"Message-ID: <10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>\r\n"
						+ "Subject: private\r\n\r\nprivate body and attachment bytes" + "x".repeat(100_000)));
		reportMessage.setContent(report);
		reportMessage.saveChanges();

		InboundMailModels.InboundEnvelope envelope = transport.envelope(12, reportMessage);

		assertThat(envelope.malformed()).isFalse();
		assertThat(envelope.dsn().originalMessageId())
				.isEqualTo("<10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>");
		assertThat(envelope.toString()).doesNotContain("private body", "attachment bytes", "Subject");
	}

	@Test
	void acceptsTransferEncodedTextRfc822HeadersThatEndAtPartEofWithoutABlankLine() throws Exception {
		MimeMessage reportMessage = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart report = new MimeMultipart("report");
		report.addBodyPart(deliveryStatusPart("Action: failed\r\nStatus: 5.1.1\r\n"));
		report.addBodyPart(new EncodedHeaderTextPart(
				"Message-ID: <10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>=0D=0A"));
		reportMessage.setContent(report);
		reportMessage.saveChanges();

		InboundMailModels.InboundEnvelope envelope = transport.envelope(121, reportMessage);

		assertThat(envelope.malformed()).isFalse();
		assertThat(envelope.dsn().originalMessageId())
				.isEqualTo("<10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>");
	}

	@Test
	void propagatesHeaderContentAndPartStreamTimeoutsInsteadOfAdvancingMalformedMail() throws Exception {
		MimeMessage headerTimeout = new FailingReportMessage(FailurePoint.HEADER);
		MimeMessage contentTimeout = new FailingReportMessage(FailurePoint.CONTENT);
		MimeMessage bodyStructureFailure = new FailingReportMessage(FailurePoint.BODY_STRUCTURE);

		MimeMessage streamTimeout = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart report = new MimeMultipart("report");
		report.addBodyPart(new TimeoutStatusPart());
		streamTimeout.setContent(report);
		streamTimeout.saveChanges();
		MimeMessage connectionReset = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart resetReport = new MimeMultipart("report");
		resetReport.addBodyPart(new ConnectionResetStatusPart());
		connectionReset.setContent(resetReport);
		connectionReset.saveChanges();

		for (MimeMessage message : new MimeMessage[]{
				headerTimeout, contentTimeout, bodyStructureFailure, streamTimeout, connectionReset}) {
			assertThatThrownBy(() -> transport.envelope(13, message))
					.isInstanceOf(MailboxTransportException.class);
		}
	}

	@Test
	void bootstrapOrUidValidityResetAnchorsAtOnlyTheNewestBoundedWindow() {
		assertThat(transport.bootstrapSequence(1_000_000, 50)).isEqualTo(999_951);
		assertThat(transport.bootstrapSequence(27, 50)).isEqualTo(1);
		assertThat(MailboxTransport.validRemoteUidValidity(0)).isFalse();
		assertThat(MailboxTransport.validRemoteUidValidity(1)).isTrue();
	}

	@Test
	void conflictingDeliveryStatusOrOriginalMessageIdentifiersFailClosed() throws Exception {
		MimeMessage dualOriginal = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart dualOriginalReport = new MimeMultipart("report");
		dualOriginalReport.addBodyPart(deliveryStatusPart(
				"Action: failed\r\nStatus: 5.1.1\r\nOriginal-Message-ID: "
						+ "<10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>\r\n"));
		MimeBodyPart otherOriginal = new MimeBodyPart(new ByteArrayInputStream((
				"Content-Type: text/rfc822-headers\r\n\r\nMessage-ID: "
						+ "<20000000-0000-0000-0000-000000000002@delivery.camel-arxiv.invalid>\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII)));
		dualOriginalReport.addBodyPart(otherOriginal);
		dualOriginal.setContent(dualOriginalReport);
		dualOriginal.saveChanges();

		MimeMessage contradictory = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart contradictoryReport = new MimeMultipart("report");
		contradictoryReport.addBodyPart(deliveryStatusPart("Action: failed\r\nStatus: 5.1.1\r\n"));
		contradictoryReport.addBodyPart(deliveryStatusPart("Action: failed\r\nStatus: 4.2.0\r\n"));
		contradictory.setContent(contradictoryReport);
		contradictory.saveChanges();

		MimeMessage duplicateField = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart duplicateFieldReport = new MimeMultipart("report");
		duplicateFieldReport.addBodyPart(deliveryStatusPart(
				"Action: failed\r\nStatus: 5.1.1\r\nStatus: 4.2.0\r\n"));
		duplicateField.setContent(duplicateFieldReport);
		duplicateField.saveChanges();

		assertThat(transport.envelope(14, dualOriginal).malformed()).isTrue();
		assertThat(transport.envelope(15, contradictory).malformed()).isTrue();
		assertThat(transport.envelope(16, duplicateField).malformed()).isTrue();
	}

	@Test
	void malformedMultipartBoundaryBecomesUnmatchedInsteadOfPoisoningTheCursor() throws Exception {
		String raw = "MIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/report; report-type=delivery-status; boundary=missing\r\n"
				+ "\r\nthis has no MIME boundary\r\n";
		MimeMessage malformed = new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));

		assertThat(transport.envelope(17, malformed).malformed()).isTrue();
	}

	@Test
	void reportWithoutStructuredDeliveryStatusCannotFallBackToAHumanReply() throws Exception {
		MimeMessage invalidReport = new MimeMessage(Session.getInstance(new Properties()));
		invalidReport.setHeader("In-Reply-To",
				"<10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>");
		MimeMultipart report = new MimeMultipart("report");
		MimeBodyPart explanation = new MimeBodyPart();
		explanation.setText("automated report without a delivery-status part");
		report.addBodyPart(explanation);
		invalidReport.setContent(report);
		invalidReport.saveChanges();

		assertThat(transport.envelope(18, invalidReport).malformed()).isTrue();
	}

	@Test
	void boundsRawTransferEncodedOctetsBeforeDecodingDeliveryStatus() throws Exception {
		AtomicInteger rawReads = new AtomicInteger();
		MimeMessage encodedReport = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart report = new MimeMultipart("report");
		report.addBodyPart(new EncodedExpansionStatusPart(rawReads));
		encodedReport.setContent(report);
		encodedReport.saveChanges();

		assertThat(transport.envelope(19, encodedReport).malformed()).isTrue();
		assertThat(rawReads.get()).isBetween(1, 65_537);
	}

	@Test
	void invalidTransferEncodingIsMalformedAfterRawBytesAreSafelyBuffered() throws Exception {
		MimeMessage invalidEncoding = new MimeMessage(Session.getInstance(new Properties()));
		MimeMultipart report = new MimeMultipart("report");
		report.addBodyPart(new InvalidBase64StatusPart());
		invalidEncoding.setContent(report);
		invalidEncoding.saveChanges();

		assertThat(transport.envelope(20, invalidEncoding).malformed()).isTrue();
	}

	private MailboxRepository.MailboxAccountRecord record(
			MailboxModels.Protocol protocol, MailboxModels.TlsMode tlsMode
	) {
		return new MailboxRepository.MailboxAccountRecord(
				UUID.randomUUID(), "Mailbox", protocol, "mail-test", 3143, tlsMode,
				"user@example.org", new byte[16], new byte[12], "INBOX", true,
				null, null, null, 0, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now());
	}

	private MimeBodyPart deliveryStatusPart(String fields) throws Exception {
		return new MimeBodyPart(new ByteArrayInputStream((
				"Content-Type: message/delivery-status\r\n\r\n" + fields)
				.getBytes(StandardCharsets.US_ASCII)));
	}

	private static final class HeaderOnlyOriginalPart extends MimeBodyPart {
		private final byte[] value;

		private HeaderOnlyOriginalPart(String value) {
			this.value = value.getBytes(StandardCharsets.US_ASCII);
		}

		@Override public String getContentType() {
			return "message/rfc822";
		}

		@Override public InputStream getInputStream() {
			return new ByteArrayInputStream(value);
		}

		@Override public InputStream getRawInputStream() {
			int headerEnd = new String(value, StandardCharsets.US_ASCII).indexOf("\r\n\r\n") + 4;
			return new InputStream() {
				private int position;
				@Override public int read() {
					if (position >= headerEnd) {
						throw new AssertionError("Original message body must not be read");
					}
					return value[position++] & 0xff;
				}
			};
		}

		@Override public Object getContent() throws IOException {
			throw new AssertionError("Nested message content must not be materialized");
		}
	}

	private enum FailurePoint { HEADER, CONTENT, BODY_STRUCTURE }

	private static final class FailingReportMessage extends MimeMessage {
		private final FailurePoint failurePoint;

		private FailingReportMessage(FailurePoint failurePoint) {
			super(Session.getInstance(new Properties()));
			this.failurePoint = failurePoint;
		}

		@Override public String[] getHeader(String name) throws jakarta.mail.MessagingException {
			if (failurePoint == FailurePoint.HEADER) {
				throw new jakarta.mail.MessagingException(
						"timeout", new SocketTimeoutException("fixture timeout"));
			}
			return "Content-Type".equalsIgnoreCase(name)
					? new String[]{"multipart/report; report-type=delivery-status"} : null;
		}

		@Override public Object getContent() throws IOException, MessagingException {
			if (failurePoint == FailurePoint.BODY_STRUCTURE) {
				throw new MessagingException(
						"temporary BODYSTRUCTURE failure", new ProtocolException("fixture protocol failure"));
			}
			throw new IOException(new SocketTimeoutException("fixture timeout"));
		}
	}

	private static final class TimeoutStatusPart extends MimeBodyPart {
		@Override public String getContentType() {
			return "message/delivery-status";
		}

		@Override public InputStream getRawInputStream() {
			return new InputStream() {
				@Override public int read() throws IOException {
					throw new SocketTimeoutException("fixture timeout");
				}
			};
		}
	}

	private static final class ConnectionResetStatusPart extends MimeBodyPart {
		@Override public String getContentType() {
			return "message/delivery-status";
		}

		@Override public InputStream getRawInputStream() {
			return new InputStream() {
				@Override public int read() throws IOException {
					throw new SocketException("fixture connection reset");
				}
			};
		}
	}

	private static final class EncodedExpansionStatusPart extends MimeBodyPart {
		private final AtomicInteger rawReads;

		private EncodedExpansionStatusPart(AtomicInteger rawReads) {
			this.rawReads = rawReads;
		}

		@Override public String getContentType() {
			return "message/delivery-status";
		}

		@Override public String getEncoding() {
			return "quoted-printable";
		}

		@Override public int getSize() {
			return -1;
		}

		@Override public InputStream getInputStream() {
			return new ByteArrayInputStream(
					"Action: failed\r\nStatus: 5.1.1\r\n".getBytes(StandardCharsets.US_ASCII));
		}

		@Override public InputStream getRawInputStream() {
			byte[] raw = "=\r\n".repeat(30_000).getBytes(StandardCharsets.US_ASCII);
			return new InputStream() {
				private int position;
				@Override public int read() {
					if (position >= raw.length) return -1;
					rawReads.incrementAndGet();
					return raw[position++] & 0xff;
				}
			};
		}
	}

	private static final class InvalidBase64StatusPart extends MimeBodyPart {
		@Override public String getContentType() {
			return "message/delivery-status";
		}

		@Override public String getEncoding() {
			return "base64";
		}

		@Override public InputStream getRawInputStream() {
			return new ByteArrayInputStream("A".getBytes(StandardCharsets.US_ASCII));
		}
	}

	private static final class EncodedHeaderTextPart extends MimeBodyPart {
		private final byte[] raw;

		private EncodedHeaderTextPart(String raw) {
			this.raw = raw.getBytes(StandardCharsets.US_ASCII);
		}

		@Override public String getContentType() {
			return "text/rfc822-headers";
		}

		@Override public String getEncoding() {
			return "quoted-printable";
		}

		@Override public InputStream getRawInputStream() {
			return new ByteArrayInputStream(raw);
		}
	}
}
