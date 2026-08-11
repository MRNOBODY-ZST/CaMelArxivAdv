package com.camel_hub.advertisement.email.mailbox;

import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class MailboxTransport {
	private final SmtpSecretCrypto crypto;
	private final MailboxPolicy policy;
	private final MailboxProperties properties;

	public MailboxTransport(SmtpSecretCrypto crypto, MailboxPolicy policy, MailboxProperties properties) {
		this.crypto = crypto;
		this.policy = policy;
		this.properties = properties;
	}

	public void testConnection(MailboxRepository.MailboxAccountRecord account) {
		withStore(account, store -> { });
	}

	public List<MessageHeader> preview(MailboxRepository.MailboxAccountRecord account, int limit) {
		List<MessageHeader> result = new ArrayList<>();
		withStore(account, store -> readHeaders(store, account, Math.min(limit, properties.maxPreviewMessages()), result));
		Collections.reverse(result);
		return List.copyOf(result);
	}

	Properties configurationFor(MailboxRepository.MailboxAccountRecord account) {
		String protocol = account.protocol().name().toLowerCase(Locale.ROOT);
		Properties values = new Properties();
		values.setProperty("mail." + protocol + ".connectiontimeout",
				Long.toString(properties.connectTimeout().toMillis()));
		values.setProperty("mail." + protocol + ".timeout", Long.toString(properties.readTimeout().toMillis()));
		values.setProperty("mail." + protocol + ".writetimeout", Long.toString(properties.readTimeout().toMillis()));
		values.setProperty("mail." + protocol + ".starttls.enable", Boolean.toString(
				account.tlsMode() == MailboxModels.TlsMode.STARTTLS_REQUIRED));
		values.setProperty("mail." + protocol + ".starttls.required", Boolean.toString(
				account.tlsMode() == MailboxModels.TlsMode.STARTTLS_REQUIRED));
		values.setProperty("mail." + protocol + ".ssl.enable", Boolean.toString(
				account.tlsMode() == MailboxModels.TlsMode.TLS_IMPLICIT));
		values.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
		values.setProperty("mail.debug", "false");
		return values;
	}

	private void withStore(MailboxRepository.MailboxAccountRecord account, StoreAction action) {
		policy.validateDestination(account.host(), account.port(), account.tlsMode());
		String protocol = account.protocol().name().toLowerCase(Locale.ROOT);
		Session session = Session.getInstance(configurationFor(account));
		char[] password = null;
		String passwordString = null;
		Store store = null;
		try {
			password = crypto.decrypt(new SmtpSecretCrypto.EncryptedSecret(
					account.passwordCiphertext(), account.passwordNonce()));
			passwordString = new String(password);
			store = session.getStore(protocol);
			store.connect(account.host(), account.port(), account.username(), passwordString);
			action.execute(store);
		}
		catch (MailboxTransportException exception) {
			throw exception;
		}
		catch (MessagingException exception) {
			throw failure(exception);
		}
		finally {
			if (store != null && store.isConnected()) {
				try {
					store.close();
				}
				catch (MessagingException ignored) {
					// The requested operation has already completed; do not replace its result with a close failure.
				}
			}
			if (password != null) Arrays.fill(password, '\0');
			passwordString = null;
		}
	}

	private void readHeaders(
			Store store, MailboxRepository.MailboxAccountRecord account, int limit, List<MessageHeader> output
	) throws MessagingException {
		Folder folder = store.getFolder(account.folderName());
		try {
			folder.open(Folder.READ_ONLY);
			int count = folder.getMessageCount();
			if (count <= 0) return;
			int start = Math.max(1, count - limit + 1);
			Message[] messages = folder.getMessages(start, count);
			FetchProfile profile = new FetchProfile();
			profile.add(FetchProfile.Item.ENVELOPE);
			profile.add(FetchProfile.Item.SIZE);
			folder.fetch(messages, profile);
			for (Message message : messages) {
				output.add(header(account.protocol(), message));
			}
		}
		finally {
			if (folder.isOpen()) folder.close(false);
		}
	}

	private MessageHeader header(MailboxModels.Protocol protocol, Message message) throws MessagingException {
		Date received = message.getReceivedDate();
		Date sent = message.getSentDate();
		String contentType = message.getContentType();
		boolean attachments = message.getFileName() != null
				|| (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/mixed"));
		return new MessageHeader(
				protocol.name().toLowerCase(Locale.ROOT) + ":" + message.getMessageNumber(),
				safeSubject(message.getSubject()), maskedSender(message.getFrom()),
				received == null ? null : received.toInstant(), sent == null ? null : sent.toInstant(),
				Math.max(message.getSize(), 0), attachments);
	}

	private String safeSubject(String value) {
		String normalized = value == null ? "(no subject)" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		StringBuilder safe = new StringBuilder(Math.min(normalized.length(), 300));
		normalized.codePoints().filter(point -> !Character.isISOControl(point)).limit(300).forEach(safe::appendCodePoint);
		return safe.isEmpty() ? "(no subject)" : safe.toString();
	}

	private String maskedSender(Address[] addresses) {
		if (addresses == null || addresses.length == 0) return "unknown";
		String raw = addresses[0] instanceof InternetAddress internet
				? internet.getAddress() : addresses[0].toString();
		if (raw == null) return "unknown";
		int at = raw.lastIndexOf('@');
		if (at <= 0 || at == raw.length() - 1) return "masked";
		String local = raw.substring(0, at);
		String visible = local.substring(0, Math.min(2, local.length()));
		return visible + "***@" + raw.substring(at + 1).toLowerCase(Locale.ROOT);
	}

	private MailboxTransportException failure(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof AuthenticationFailedException) {
				return new MailboxTransportException(MailboxTransportException.FailureCategory.AUTHENTICATION_FAILED);
			}
			if (current instanceof FolderNotFoundException) {
				return new MailboxTransportException(MailboxTransportException.FailureCategory.FOLDER_NOT_FOUND);
			}
			if (current instanceof SocketTimeoutException) {
				return new MailboxTransportException(MailboxTransportException.FailureCategory.CONNECTION_TIMEOUT);
			}
			if (current instanceof UnknownHostException) {
				return new MailboxTransportException(MailboxTransportException.FailureCategory.DNS_FAILURE);
			}
			if (current instanceof SSLException || current instanceof CertificateException) {
				return new MailboxTransportException(MailboxTransportException.FailureCategory.TLS_FAILURE);
			}
			if (current instanceof ConnectException) {
				return new MailboxTransportException(MailboxTransportException.FailureCategory.CONNECTION_REJECTED);
			}
			current = current.getCause();
		}
		return new MailboxTransportException(error instanceof MessagingException
				? MailboxTransportException.FailureCategory.PROTOCOL_REJECTED
				: MailboxTransportException.FailureCategory.UNEXPECTED_FAILURE);
	}

	@FunctionalInterface
	private interface StoreAction {
		void execute(Store store) throws MessagingException;
	}

	public record MessageHeader(
			String remoteId, String subject, String fromMasked, Instant receivedAt,
			Instant sentAt, int sizeBytes, boolean hasAttachments
	) { }
}
