package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class SmtpTransport {

	private static final Set<String> SAFE_EXTENSION_HEADERS = Set.of(
			"List-Unsubscribe", "List-Unsubscribe-Post");
	private static final String MESSAGE_ID_PATTERN = "^<[^<>\\s]{1,250}>$";

	private final SmtpSecretCrypto crypto;
	private final SmtpPolicy policy;
	private final SmtpProperties properties;

	public SmtpTransport(SmtpSecretCrypto crypto, SmtpPolicy policy, SmtpProperties properties) {
		this.crypto = crypto;
		this.policy = policy;
		this.properties = properties;
	}

	public void testConnection(SmtpRepository.SmtpAccountRecord account) {
		policy(account);
		Session session = Session.getInstance(configuration(account));
		char[] password = null;
		String passwordString = null;
		StageAwareTransport transport = new StageAwareTransport(session);
		boolean connected = false;
		try {
			if (account.passwordCiphertext() != null) {
				password = crypto.decrypt(new SmtpSecretCrypto.EncryptedSecret(
						account.passwordCiphertext(), account.passwordNonce()));
				passwordString = new String(password);
			}
			transport.connect(account.host(), account.port(), account.username(), passwordString);
			connected = true;
		}
		catch (MessagingException | RuntimeException exception) {
			throw failure(exception, transport);
		}
		finally {
			close(transport);
			if (password != null) Arrays.fill(password, '\0');
			passwordString = null;
		}
	}

	/** Existing diagnostic/template call sites retain their original source contract. */
	public void send(SmtpRepository.SmtpAccountRecord account, OutboundMessage message) {
		sendDetailed(account, message);
	}

	public SmtpOutcome sendDetailed(SmtpRepository.SmtpAccountRecord account, OutboundMessage message) {
		policy(account);
		Session session = Session.getInstance(configuration(account));
		MimeMessage mime;
		try {
			mime = mimeMessage(session, account, message);
		}
		catch (MessagingException | UnsupportedEncodingException | HeaderWriteException exception) {
			throw new SmtpTransportException(
					SmtpTransportException.FailureCategory.CONFIGURATION_FAILURE,
					AttemptStatus.PERMANENT_FAILURE, TransportStage.MAIL_FROM,
					null, null, false);
		}

		char[] password = null;
		String passwordString = null;
		StageAwareTransport transport = new StageAwareTransport(session);
		try {
			if (account.passwordCiphertext() != null) {
				password = crypto.decrypt(new SmtpSecretCrypto.EncryptedSecret(
						account.passwordCiphertext(), account.passwordNonce()));
				passwordString = new String(password);
			}
			transport.connect(account.host(), account.port(), account.username(), passwordString);
			transport.sendMessage(mime, mime.getAllRecipients());
			Integer code = explicitCode(transport.getLastReturnCode());
			return new SmtpOutcome(
					AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA,
					code == null ? 250 : code,
					SmtpTransportException.sanitize(transport.getLastServerResponse()));
		}
		catch (SmtpTransportException exception) {
			throw exception;
		}
		catch (MessagingException | RuntimeException exception) {
			throw failure(exception, transport);
		}
		finally {
			close(transport);
			if (password != null) Arrays.fill(password, '\0');
			passwordString = null;
		}
	}

	private void policy(SmtpRepository.SmtpAccountRecord account) {
		try {
			policy.validateDestination(account.host(), account.port(), account.tlsMode());
		}
		catch (RuntimeException exception) {
			throw new SmtpTransportException(
					SmtpTransportException.FailureCategory.CONFIGURATION_FAILURE,
					AttemptStatus.PERMANENT_FAILURE, TransportStage.CONNECT,
					null, null, false);
		}
	}

	private void close(StageAwareTransport transport) {
		try {
			transport.close();
		}
		catch (MessagingException ignored) {
			// QUIT cannot undo an already completed connection or DATA transaction.
		}
	}

	private Properties configuration(SmtpRepository.SmtpAccountRecord account) {
		Properties values = new Properties();
		values.setProperty("mail.smtp.auth", Boolean.toString(account.username() != null));
		values.setProperty("mail.smtp.connectiontimeout", Long.toString(properties.connectTimeout().toMillis()));
		values.setProperty("mail.smtp.timeout", Long.toString(properties.readTimeout().toMillis()));
		values.setProperty("mail.smtp.writetimeout", Long.toString(properties.writeTimeout().toMillis()));
		values.setProperty("mail.smtp.starttls.enable", Boolean.toString(
				account.tlsMode() == SmtpModels.TlsMode.STARTTLS_REQUIRED));
		values.setProperty("mail.smtp.starttls.required", Boolean.toString(
				account.tlsMode() == SmtpModels.TlsMode.STARTTLS_REQUIRED));
		values.setProperty("mail.smtp.ssl.enable", Boolean.toString(
				account.tlsMode() == SmtpModels.TlsMode.TLS_IMPLICIT));
		values.setProperty("mail.smtp.ssl.checkserveridentity", "true");
		values.setProperty("mail.smtp.chunking", "false");
		values.setProperty("mail.debug", "false");
		return values;
	}

	private MimeMessage mimeMessage(
			Session session, SmtpRepository.SmtpAccountRecord account, OutboundMessage value
	) throws MessagingException, UnsupportedEncodingException {
		StableMimeMessage message = new StableMimeMessage(session);
		String fromEmail = value.fromEmail() == null ? account.fromEmail() : value.fromEmail();
		message.setFrom(new InternetAddress(fromEmail, value.fromName(), "UTF-8"));
		message.setReplyTo(new InternetAddress[] {new InternetAddress(value.replyTo(), true)});
		message.setRecipient(Message.RecipientType.TO, new InternetAddress(value.recipient(), true));
		message.setSubject(value.subject(), "UTF-8");
		message.setSentDate(new Date());
		MimeBodyPart text = new MimeBodyPart();
		text.setText(value.text(), "UTF-8");
		MimeBodyPart html = new MimeBodyPart();
		html.setContent(value.html(), "text/html; charset=UTF-8");
		MimeMultipart alternatives = new MimeMultipart("alternative");
		alternatives.addBodyPart(text);
		alternatives.addBodyPart(html);
		message.setContent(alternatives);
		message.saveChanges();
		// MimeMessage generates Message-ID during saveChanges, so the approved stable value must follow it.
		message.installStableMessageId(value.rfcMessageId());
		message.setHeader("X-CaMel-Correlation-Id", value.correlationId());
		value.headers().forEach((name, headerValue) -> {
			try {
				message.setHeader(name, headerValue);
			}
			catch (MessagingException exception) {
				throw new HeaderWriteException(exception);
			}
		});
		return message;
	}

	private SmtpTransportException failure(Throwable error, StageAwareTransport transport) {
		FailureEvidence evidence = evidence(error, transport);
		boolean authenticationFailure = evidence.authenticationFailure() || transport.authenticationFailure();
		TransportStage stage = authenticationFailure ? TransportStage.AUTH : transport.stage();
		if (authenticationFailure) {
			return new SmtpTransportException(
					SmtpTransportException.FailureCategory.AUTHENTICATION_FAILED,
					AttemptStatus.PERMANENT_FAILURE, TransportStage.AUTH,
					evidence.explicitCode(), evidence.summary(), false);
		}
		if (evidence.tlsFailure()) {
			return new SmtpTransportException(
					SmtpTransportException.FailureCategory.TLS_FAILURE,
					AttemptStatus.PERMANENT_FAILURE, stage,
					evidence.explicitCode(), evidence.summary(), false);
		}
		if (evidence.explicitCode() != null && evidence.explicitCode() >= 400) {
			boolean temporary = evidence.explicitCode() <= 499;
			return new SmtpTransportException(
					SmtpTransportException.FailureCategory.SMTP_REJECTED,
					temporary ? AttemptStatus.TEMPORARY_FAILURE : AttemptStatus.PERMANENT_FAILURE,
					stage, evidence.explicitCode(), evidence.summary(), temporary);
		}
		if (transport.dataStarted()) {
			return new SmtpTransportException(
					SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE,
					AttemptStatus.OUTCOME_UNKNOWN, TransportStage.POST_DATA,
					null, evidence.summary(), false);
		}
		SmtpTransportException.FailureCategory category = evidence.authenticationFailure()
				? SmtpTransportException.FailureCategory.AUTHENTICATION_FAILED
				: evidence.tlsFailure() ? SmtpTransportException.FailureCategory.TLS_FAILURE
				: evidence.timeout() ? SmtpTransportException.FailureCategory.CONNECTION_TIMEOUT
				: evidence.dnsFailure() ? SmtpTransportException.FailureCategory.DNS_FAILURE
				: evidence.connectionRejected() ? SmtpTransportException.FailureCategory.CONNECTION_REJECTED
				: evidence.configurationFailure() ? SmtpTransportException.FailureCategory.CONFIGURATION_FAILURE
				: SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE;
		return new SmtpTransportException(
				category, AttemptStatus.PERMANENT_FAILURE, stage,
				null, evidence.summary(), false);
	}

	private FailureEvidence evidence(Throwable root, StageAwareTransport transport) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Throwable> pending = new ArrayDeque<>();
		pending.add(root);
		Integer code = explicitCode(transport.getLastReturnCode());
		String summary = code == null ? null : SmtpTransportException.sanitize(transport.getLastServerResponse());
		boolean authentication = false;
		boolean tls = false;
		boolean timeout = false;
		boolean dns = false;
		boolean refused = false;
		boolean configuration = false;
		while (!pending.isEmpty()) {
			Throwable current = pending.removeFirst();
			if (current == null || !visited.add(current)) continue;
			if (current instanceof AuthenticationFailedException) authentication = true;
			if (current instanceof SSLException || current instanceof CertificateException
					|| (current.getMessage() != null
					&& current.getMessage().toUpperCase(java.util.Locale.ROOT).contains("STARTTLS"))) tls = true;
			if (current instanceof SocketTimeoutException) timeout = true;
			if (current instanceof UnknownHostException) dns = true;
			if (current instanceof ConnectException) refused = true;
			if (current instanceof AddressException || current instanceof HeaderWriteException
					|| current instanceof IllegalArgumentException || current instanceof IllegalStateException) {
				configuration = true;
			}
			Integer nestedCode = current instanceof SMTPSendFailedException send
					? explicitCode(send.getReturnCode())
					: current instanceof SMTPAddressFailedException address
					? explicitCode(address.getReturnCode()) : null;
			if (nestedCode != null) {
				code = nestedCode;
				String nestedSummary = current instanceof SMTPSendFailedException send
						? send.getMessage() : current instanceof SMTPAddressFailedException address
						? address.getMessage() : null;
				summary = SmtpTransportException.sanitize(
						transport.getLastServerResponse() == null ? nestedSummary : transport.getLastServerResponse());
			}
			if (current.getCause() != null) pending.addLast(current.getCause());
			if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
				pending.addLast(messaging.getNextException());
			}
		}
		return new FailureEvidence(code, summary, authentication, tls, timeout, dns, refused, configuration);
	}

	private Integer explicitCode(int code) {
		return code >= 200 && code <= 599 ? code : null;
	}

	private static final class StageAwareTransport extends SMTPTransport {
		private TransportStage stage = TransportStage.CONNECT;
		private boolean dataStarted;
		private boolean authenticationFailure;

		private StageAwareTransport(Session session) {
			super(session, new URLName("smtp", null, -1, null, null, null));
		}

		@Override
		protected synchronized boolean protocolConnect(
				String host, int port, String user, String password
		) throws MessagingException {
			try {
				return super.protocolConnect(host, port, user, password);
			}
			catch (MessagingException exception) {
				int code = getLastReturnCode();
				authenticationFailure = exception instanceof AuthenticationFailedException
						|| code == 530 || code == 534 || code == 535 || code == 538;
				throw exception;
			}
		}

		@Override
		protected boolean ehlo(String domain) throws MessagingException {
			stage = TransportStage.EHLO;
			return super.ehlo(domain);
		}

		@Override
		protected void helo(String domain) throws MessagingException {
			stage = TransportStage.EHLO;
			super.helo(domain);
		}

		@Override
		protected void startTLS() throws MessagingException {
			stage = TransportStage.STARTTLS;
			super.startTLS();
		}

		@Override
		protected void mailFrom() throws MessagingException {
			stage = TransportStage.MAIL_FROM;
			super.mailFrom();
		}

		@Override
		protected void rcptTo() throws MessagingException {
			stage = TransportStage.RCPT_TO;
			super.rcptTo();
		}

		@Override
		protected OutputStream data() throws MessagingException {
			stage = TransportStage.DATA;
			OutputStream output = super.data();
			dataStarted = true;
			return output;
		}

		@Override
		protected void finishData() throws IOException, MessagingException {
			super.finishData();
			stage = TransportStage.POST_DATA;
		}

		TransportStage stage() {
			return stage;
		}

		boolean dataStarted() {
			return dataStarted;
		}

		boolean authenticationFailure() {
			return authenticationFailure;
		}
	}

	private static final class HeaderWriteException extends RuntimeException {
		private HeaderWriteException(Throwable cause) {
			super(cause);
		}
	}

	private static final class StableMimeMessage extends MimeMessage {
		private String stableMessageId;

		private StableMimeMessage(Session session) {
			super(session);
		}

		private void installStableMessageId(String messageId) throws MessagingException {
			stableMessageId = messageId;
			setHeader("Message-ID", messageId);
		}

		@Override
		protected void updateMessageID() throws MessagingException {
			if (stableMessageId == null) super.updateMessageID();
			else setHeader("Message-ID", stableMessageId);
		}
	}

	private record FailureEvidence(
			Integer explicitCode, String summary, boolean authenticationFailure,
			boolean tlsFailure, boolean timeout, boolean dnsFailure,
			boolean connectionRejected, boolean configurationFailure
	) { }

	public record SmtpOutcome(
			AttemptStatus status, TransportStage stage, Integer responseCode, String responseSummary
	) {
		public SmtpOutcome {
			if (status != AttemptStatus.SMTP_ACCEPTED) {
				throw new IllegalArgumentException("A successful SMTP outcome must be SMTP_ACCEPTED");
			}
			stage = stage == null ? TransportStage.POST_DATA : stage;
			responseCode = responseCode != null && responseCode >= 200 && responseCode <= 299
					? responseCode : null;
			responseSummary = SmtpTransportException.sanitize(responseSummary);
		}
	}

	public record OutboundMessage(
			String recipient, String subject, String fromName, String replyTo,
			String html, String text, String correlationId,
			String fromEmail, String rfcMessageId, Map<String, String> headers
	) {
		public OutboundMessage(
				String recipient, String subject, String fromName, String replyTo,
				String html, String text, String correlationId
		) {
			this(recipient, subject, fromName, replyTo, html, text, correlationId,
					null, legacyMessageId(correlationId), Map.of());
		}

		public OutboundMessage(
				String recipient, String subject, String fromName, String replyTo,
				String html, String text, String correlationId,
				String rfcMessageId, Map<String, String> headers
		) {
			this(recipient, subject, fromName, replyTo, html, text, correlationId,
					null, rfcMessageId, headers);
		}

		public OutboundMessage {
			if (recipient == null || recipient.isBlank() || recipient.length() > 320 || hasControl(recipient)
					|| subject == null || subject.isBlank() || subject.length() > 998 || hasControl(subject)
					|| fromName == null || fromName.isBlank() || fromName.length() > 160 || hasControl(fromName)
					|| replyTo == null || replyTo.isBlank() || replyTo.length() > 320 || hasControl(replyTo)
					|| html == null || html.isBlank() || text == null || text.isBlank()) {
				throw new IllegalArgumentException("SMTP message fields are invalid");
			}
			if (correlationId == null || correlationId.isBlank() || correlationId.length() > 255
					|| hasControl(correlationId)) {
				throw new IllegalArgumentException("SMTP correlation ID is invalid");
			}
			if (rfcMessageId == null || !rfcMessageId.matches(MESSAGE_ID_PATTERN)) {
				throw new IllegalArgumentException("RFC Message-ID is invalid");
			}
			if (fromEmail != null && hasControl(fromEmail)) {
				throw new IllegalArgumentException("SMTP from address is invalid");
			}
			Map<String, String> safeHeaders = new LinkedHashMap<>();
			if (headers != null) {
				headers.forEach((name, value) -> {
					String canonical = canonicalHeader(name);
					if (canonical == null || value == null || value.isBlank()
							|| value.length() > 998 || hasControl(value)) {
						throw new IllegalArgumentException("SMTP extension header is invalid");
					}
					if (safeHeaders.putIfAbsent(canonical, value) != null) {
						throw new IllegalArgumentException("SMTP extension header is duplicated");
					}
				});
			}
			headers = Map.copyOf(safeHeaders);
		}

		@Override
		public Map<String, String> headers() {
			return Map.copyOf(headers);
		}

		private static boolean hasControl(String value) {
			return value.codePoints().anyMatch(Character::isISOControl);
		}

		private static String canonicalHeader(String name) {
			if (name == null || hasControl(name)) return null;
			return SAFE_EXTENSION_HEADERS.stream()
					.filter(allowed -> allowed.equalsIgnoreCase(name))
					.findFirst().orElse(null);
		}

		private static String legacyMessageId(String correlationId) {
			String safe = correlationId == null ? "message" : correlationId.replaceAll("[^A-Za-z0-9._-]", "-");
			if (safe.isBlank()) safe = "message";
			return "<" + safe.substring(0, Math.min(safe.length(), 180)) + "@diagnostic.camel-arxiv.invalid>";
		}
	}
}
