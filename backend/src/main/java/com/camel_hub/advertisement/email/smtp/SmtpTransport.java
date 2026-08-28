package com.camel_hub.advertisement.email.smtp;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

import javax.net.ssl.SSLException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

public final class SmtpTransport {

	private final SmtpSecretCrypto crypto;
	private final SmtpPolicy policy;
	private final SmtpProperties properties;

	public SmtpTransport(SmtpSecretCrypto crypto, SmtpPolicy policy, SmtpProperties properties) {
		this.crypto = crypto;
		this.policy = policy;
		this.properties = properties;
	}

	public void testConnection(SmtpRepository.SmtpAccountRecord account) {
		withTransport(account, (transport, session) -> { });
	}

	public void send(SmtpRepository.SmtpAccountRecord account, OutboundMessage message) {
		withTransport(account, (transport, session) -> {
			try {
				MimeMessage mime = mimeMessage(session, account, message);
				transport.sendMessage(mime, mime.getAllRecipients());
			}
			catch (MessagingException | UnsupportedEncodingException exception) {
				throw failure(exception, true);
			}
		});
	}

	private void withTransport(SmtpRepository.SmtpAccountRecord account, TransportAction action) {
		policy.validateDestination(account.host(), account.port(), account.tlsMode());
		Properties configuration = configuration(account);
		Session session = Session.getInstance(configuration);
		char[] password = null;
		String passwordString = null;
		boolean actionCompleted = false;
		try (Transport transport = session.getTransport("smtp")) {
			if (account.passwordCiphertext() != null) {
				password = crypto.decrypt(new SmtpSecretCrypto.EncryptedSecret(
						account.passwordCiphertext(), account.passwordNonce()));
				passwordString = new String(password);
			}
			transport.connect(account.host(), account.port(), account.username(), passwordString);
			action.execute(transport, session);
			actionCompleted = true;
		}
		catch (SmtpTransportException exception) {
			throw exception;
		}
		catch (MessagingException exception) {
			// A failed QUIT/close cannot undo an already successful DATA or connection operation.
			if (!actionCompleted) throw failure(exception, false);
		}
		finally {
			if (password != null) Arrays.fill(password, '\0');
			passwordString = null;
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
		values.setProperty("mail.debug", "false");
		return values;
	}

	private MimeMessage mimeMessage(
			Session session, SmtpRepository.SmtpAccountRecord account, OutboundMessage value
	) throws MessagingException, UnsupportedEncodingException {
		MimeMessage message = new MimeMessage(session);
		message.setFrom(new InternetAddress(account.fromEmail(), value.fromName(), "UTF-8"));
		message.setReplyTo(new InternetAddress[] {new InternetAddress(value.replyTo(), true)});
		message.setRecipient(Message.RecipientType.TO, new InternetAddress(value.recipient(), true));
		message.setSubject(value.subject(), "UTF-8");
		message.setSentDate(new Date());
		message.setHeader("X-CaMel-Correlation-Id", value.correlationId());
		MimeBodyPart text = new MimeBodyPart();
		text.setText(value.text(), "UTF-8");
		MimeBodyPart html = new MimeBodyPart();
		html.setContent(value.html(), "text/html; charset=UTF-8");
		MimeMultipart alternatives = new MimeMultipart("alternative");
		alternatives.addBodyPart(text);
		alternatives.addBodyPart(html);
		message.setContent(alternatives);
		message.saveChanges();
		return message;
	}

	private SmtpTransportException failure(Throwable error, boolean duringSend) {
		Throwable current = error;
		boolean explicitRejection = false;
		while (current != null) {
			if (current instanceof AuthenticationFailedException) {
				return new SmtpTransportException(SmtpTransportException.FailureCategory.AUTHENTICATION_FAILED);
			}
			if (current instanceof SocketTimeoutException) {
				return new SmtpTransportException(SmtpTransportException.FailureCategory.CONNECTION_TIMEOUT);
			}
			if (current instanceof UnknownHostException) {
				return new SmtpTransportException(SmtpTransportException.FailureCategory.DNS_FAILURE);
			}
			if (current instanceof SSLException || current instanceof CertificateException) {
				return new SmtpTransportException(duringSend ? SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE
						: SmtpTransportException.FailureCategory.TLS_FAILURE);
			}
			if (current instanceof ConnectException) {
				return new SmtpTransportException(SmtpTransportException.FailureCategory.CONNECTION_REJECTED);
			}
			int responseCode = current instanceof SMTPSendFailedException send ? send.getReturnCode()
					: current instanceof SMTPAddressFailedException address ? address.getReturnCode() : 0;
			if (responseCode >= 400 && responseCode <= 599) explicitRejection = true;
			current = current.getCause();
		}
		return new SmtpTransportException(explicitRejection
				? SmtpTransportException.FailureCategory.SMTP_REJECTED
				: SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE);
	}

	@FunctionalInterface
	private interface TransportAction {
		void execute(Transport transport, Session session);
	}

	public record OutboundMessage(
			String recipient, String subject, String fromName, String replyTo,
			String html, String text, String correlationId
	) { }
}
