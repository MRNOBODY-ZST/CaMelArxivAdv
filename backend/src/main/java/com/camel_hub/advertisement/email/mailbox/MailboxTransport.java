package com.camel_hub.advertisement.email.mailbox;

import com.camel_hub.advertisement.campaign.inbound.InboundMailModels;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.iap.ProtocolException;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public final class MailboxTransport {
	private static final int MAX_SYNC_BATCH = 50;
	private static final int MAX_HEADER_CHARS = 4_096;
	private static final int MAX_DSN_BYTES = 65_536;
	private static final int MAX_DSN_PARTS = 16;

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

	public InboundMailModels.MailboxRead readSince(
			MailboxRepository.MailboxAccountRecord account, String folderName,
			long expectedUidValidity, long lastUid, int limit
	) {
		if (account.protocol() != MailboxModels.Protocol.IMAP
				|| folderName == null || !folderName.equals(account.folderName())
				|| expectedUidValidity < 0 || lastUid < 0 || limit < 1) {
			throw new MailboxTransportException(MailboxTransportException.FailureCategory.PROTOCOL_REJECTED);
		}
		AtomicReference<InboundMailModels.MailboxRead> result = new AtomicReference<>();
		withStore(account, store -> result.set(readSince(
				store, folderName, expectedUidValidity, lastUid, Math.min(limit, MAX_SYNC_BATCH))));
		return result.get();
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
			if (store instanceof IMAPStore imap && imap.hasCapability("ID")) {
				imap.id(Map.of("name", "CaMel Arxiv", "version", "0.1", "vendor", "CaMel"));
			}
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

	private InboundMailModels.MailboxRead readSince(
			Store store, String folderName, long expectedUidValidity, long lastUid, int limit
	) throws MessagingException {
		Folder folder = store.getFolder(folderName);
		try {
			folder.open(Folder.READ_ONLY);
			if (!(folder instanceof UIDFolder uidFolder)) {
				throw new MailboxTransportException(MailboxTransportException.FailureCategory.PROTOCOL_REJECTED);
			}
			long uidValidity = uidFolder.getUIDValidity();
			if (!validRemoteUidValidity(uidValidity)) {
				throw new MailboxTransportException(MailboxTransportException.FailureCategory.PROTOCOL_REJECTED);
			}
			boolean identityChanged = expectedUidValidity == 0 || expectedUidValidity != uidValidity;
			long effectiveLastUid = identityChanged ? 0 : lastUid;
			int count = folder.getMessageCount();
			if (count <= 0) return new InboundMailModels.MailboxRead(uidValidity, 0, List.of());
			int first = identityChanged
					? bootstrapSequence(count, limit)
					: firstSequenceAfter(folder, uidFolder, count, effectiveLastUid);
			if (first > count) return new InboundMailModels.MailboxRead(uidValidity, 0, List.of());
			Message[] messages = folder.getMessages(first, Math.min(count, first + limit - 1));
			FetchProfile profile = new FetchProfile();
			profile.add(UIDFolder.FetchProfileItem.UID);
			profile.add(FetchProfile.Item.SIZE);
			for (String header : List.of(
					"Message-ID", "In-Reply-To", "References", "Auto-Submitted", "Content-Type")) {
				profile.add(header);
			}
			folder.fetch(messages, profile);
			List<InboundMailModels.InboundEnvelope> result = new ArrayList<>(messages.length);
			for (Message message : messages) {
				long uid = uidFolder.getUID(message);
				if (uid > effectiveLastUid) result.add(envelope(uid, message));
			}
			long cursorFloor = identityChanged && !result.isEmpty()
					? Math.max(0, result.getFirst().remoteUid() - 1) : 0;
			return new InboundMailModels.MailboxRead(uidValidity, cursorFloor, result);
		}
		finally {
			if (folder.isOpen()) folder.close(false);
		}
	}

	int bootstrapSequence(int messageCount, int limit) {
		if (messageCount <= 0 || limit <= 0) return 1;
		return Math.max(1, messageCount - Math.min(limit, MAX_SYNC_BATCH) + 1);
	}

	static boolean validRemoteUidValidity(long uidValidity) {
		return uidValidity > 0;
	}

	private int firstSequenceAfter(Folder folder, UIDFolder uidFolder, int count, long lastUid)
			throws MessagingException {
		int low = 1;
		int high = count + 1;
		while (low < high) {
			int middle = low + (high - low) / 2;
			long uid = uidFolder.getUID(folder.getMessage(middle));
			if (uid > lastUid) high = middle;
			else low = middle + 1;
		}
		return low;
	}

	public InboundMailModels.InboundEnvelope envelope(long uid, Message message) {
		try {
			String contentType = boundedHeader(message, "Content-Type");
			String messageId = boundedHeader(message, "Message-ID");
			String inReplyTo = boundedHeader(message, "In-Reply-To");
			String references = boundedHeader(message, "References");
			String autoSubmitted = boundedHeader(message, "Auto-Submitted");
			Instant receivedAt = instant(message.getReceivedDate());
			return new InboundMailModels.InboundEnvelope(
					uid, messageId, inReplyTo, references, autoSubmitted, contentType,
					receivedAt, deliveryStatus(message, contentType), false);
		}
		catch (InboundFormatException rejected) {
			return malformedEnvelope(uid);
		}
		catch (MessagingException failure) {
			if (hasCause(failure, ParseException.class)) return malformedEnvelope(uid);
			throw failure(failure);
		}
		catch (IOException failure) {
			throw failure(failure);
		}
	}

	private InboundMailModels.InboundEnvelope malformedEnvelope(long uid) {
		return new InboundMailModels.InboundEnvelope(
				uid, null, null, null, null, null, null, null, true);
	}

	private InboundMailModels.DsnFields deliveryStatus(Message message, String contentType)
			throws MessagingException, IOException, InboundFormatException {
		if (contentType == null || !baseType(contentType).equals("multipart/report")) return null;
		try {
			return parseDeliveryStatus(message);
		}
		catch (MessagingException failure) {
			if (isMailboxAccessFailure(failure)) throw failure;
			throw new InboundFormatException("Delivery report MIME structure is invalid");
		}
	}

	private InboundMailModels.DsnFields parseDeliveryStatus(Message message)
			throws MessagingException, IOException, InboundFormatException {
		Object content = message.getContent();
		if (!(content instanceof Multipart multipart)) {
			throw new InboundFormatException("Delivery report is not multipart");
		}
		if (multipart.getCount() > MAX_DSN_PARTS) {
			throw new InboundFormatException("Delivery status report has too many MIME parts");
		}
		DsnBudget rawBudget = new DsnBudget(MAX_DSN_BYTES);
		DsnBudget decodedBudget = new DsnBudget(MAX_DSN_BYTES);
		Map<String, String> fields = new LinkedHashMap<>();
		String originalMessageId = null;
		for (int index = 0; index < multipart.getCount(); index++) {
			Part part = multipart.getBodyPart(index);
			String type = baseType(part.getContentType());
			if (type.equals("message/delivery-status")) {
				mergeFields(fields, parseHeaders(
						readPart(part, rawBudget, decodedBudget), false));
			}
			else if (type.equals("text/rfc822-headers")) {
				originalMessageId = mergeField(
						originalMessageId,
						parseHeaders(readAttachedHeaders(part, rawBudget, decodedBudget, false), true)
								.get("message-id"));
			}
			else if (type.equals("message/rfc822")) {
				originalMessageId = mergeField(
						originalMessageId,
						parseHeaders(readAttachedHeaders(part, rawBudget, decodedBudget, true), true)
								.get("message-id"));
			}
		}
		originalMessageId = mergeField(originalMessageId, fields.get("original-message-id"));
		String action = fields.get("action");
		String status = fields.get("status");
		if (action == null || status == null) {
			throw new InboundFormatException("Delivery report is missing required status fields");
		}
		return new InboundMailModels.DsnFields(
				action, status, fields.get("diagnostic-code"), originalMessageId);
	}

	private byte[] readPart(
			Part part, DsnBudget rawBudget, DsnBudget decodedBudget
	) throws MessagingException, IOException, InboundFormatException {
		if (!(part instanceof MimeBodyPart mimePart)) {
			throw new InboundFormatException("Delivery status MIME part has no raw representation");
		}
		int advertisedSize = part.getSize();
		if (advertisedSize > rawBudget.remaining()) {
			throw new InboundFormatException("Raw delivery status metadata exceeds limit");
		}
		byte[] raw = readBounded(mimePart.getRawInputStream(), rawBudget);
		InputStream decoded;
		try {
			String encoding = mimePart.getEncoding();
			InputStream encoded = new java.io.ByteArrayInputStream(raw);
			decoded = encoding == null ? encoded : MimeUtility.decode(encoded, encoding);
		}
		catch (MessagingException malformedEncoding) {
			throw new InboundFormatException("Delivery status transfer encoding is invalid");
		}
		try {
			return readBounded(decoded, decodedBudget);
		}
		catch (InboundFormatException formatFailure) {
			throw formatFailure;
		}
		catch (IOException invalidTransferEncoding) {
			throw new InboundFormatException("Delivery status transfer encoding is invalid");
		}
	}

	private byte[] readAttachedHeaders(
			Part part, DsnBudget rawBudget, DsnBudget decodedBudget, boolean stopAtBlankLine
	) throws MessagingException, IOException, InboundFormatException {
		if (!(part instanceof MimeBodyPart mimePart)) {
			throw new InboundFormatException("Attached original headers have no raw representation");
		}
		InputStream raw = new RawBudgetInputStream(mimePart.getRawInputStream(), rawBudget);
		InputStream decoded;
		try {
			String encoding = mimePart.getEncoding();
			decoded = encoding == null ? raw : MimeUtility.decode(raw, encoding);
			return stopAtBlankLine
					? readHeaderBlock(decoded, decodedBudget)
					: readBounded(decoded, decodedBudget);
		}
		catch (RawTransportIOException transportFailure) {
			throw transportFailure.original();
		}
		catch (RawBudgetIOException rawLimit) {
			throw new InboundFormatException("Raw attached headers exceed limit");
		}
		catch (InboundFormatException formatFailure) {
			throw formatFailure;
		}
		catch (IOException invalidTransferEncoding) {
			throw new InboundFormatException("Attached header transfer encoding is invalid");
		}
	}

	private void mergeFields(Map<String, String> target, Map<String, String> source)
			throws InboundFormatException {
		for (Map.Entry<String, String> entry : source.entrySet()) {
			String existing = target.putIfAbsent(entry.getKey(), entry.getValue());
			if (existing != null && !existing.equals(entry.getValue())) {
				throw new InboundFormatException("Conflicting delivery status metadata");
			}
		}
	}

	private String mergeField(String existing, String candidate) throws InboundFormatException {
		if (candidate == null) return existing;
		if (existing == null || existing.equals(candidate)) return candidate;
		throw new InboundFormatException("Conflicting original message identifiers");
	}

	private Map<String, String> parseHeaders(byte[] bytes, boolean stopAtFirstBlank)
			throws InboundFormatException {
		String text = new String(bytes, StandardCharsets.US_ASCII)
				.replaceAll("\\r?\\n[ \\t]+", " ");
		Map<String, String> values = new LinkedHashMap<>();
		for (String line : text.split("\\r?\\n")) {
			if (line.isEmpty()) {
				if (stopAtFirstBlank && !values.isEmpty()) break;
				continue;
			}
			int separator = line.indexOf(':');
			if (separator <= 0) continue;
			String name = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
			if (!List.of("action", "status", "diagnostic-code", "original-message-id", "message-id")
					.contains(name)) continue;
			String value = line.substring(separator + 1).strip();
			if (value.isEmpty() || value.length() > MAX_HEADER_CHARS
					|| value.codePoints().anyMatch(Character::isISOControl)) {
				if (!name.equals("diagnostic-code")) {
					throw new InboundFormatException("Invalid delivery status identity field");
				}
				continue;
			}
			String existing = values.putIfAbsent(name, value);
			if (existing != null && !existing.equals(value)) {
				throw new InboundFormatException("Conflicting delivery status field");
			}
		}
		return values;
	}

	private byte[] readBounded(InputStream input, DsnBudget budget)
			throws IOException, InboundFormatException {
		try (input) {
			byte[] bytes = input.readNBytes(budget.remaining() + 1);
			if (bytes.length > budget.remaining()) {
				throw new InboundFormatException("Delivery status metadata exceeds limit");
			}
			budget.consume(bytes.length);
			return bytes;
		}
	}

	private byte[] readHeaderBlock(InputStream input, DsnBudget budget)
			throws IOException, InboundFormatException {
		try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			int previous = -1;
			int beforePrevious = -1;
			int threeBack = -1;
			boolean complete = false;
			while (output.size() < budget.remaining()) {
				int current = input.read();
				if (current < 0) break;
				output.write(current);
				if ((output.size() == 1 && current == '\n')
						|| (output.size() == 2 && previous == '\r' && current == '\n')
						|| (previous == '\n' && current == '\n')
						|| (threeBack == '\r' && beforePrevious == '\n'
						&& previous == '\r' && current == '\n')) {
					complete = true;
					break;
				}
				threeBack = beforePrevious;
				beforePrevious = previous;
				previous = current;
			}
			if (!complete || output.size() <= 2) {
				throw new InboundFormatException("Attached original headers exceed limit");
			}
			byte[] bytes = output.toByteArray();
			budget.consume(bytes.length);
			return bytes;
		}
	}

	private String boundedHeader(Message message, String name)
			throws MessagingException, InboundFormatException {
		String[] values = message.getHeader(name);
		if (values == null || values.length == 0) return null;
		long length = Math.max(0, values.length - 1L);
		for (String value : values) {
			length += value == null ? 0 : value.length();
			if (length > MAX_HEADER_CHARS) {
				throw new InboundFormatException("Inbound header exceeds policy");
			}
		}
		String value = String.join(" ", values).replaceAll("\\r?\\n[ \\t]+", " ");
		if (value.codePoints().anyMatch(Character::isISOControl)) {
			throw new InboundFormatException("Inbound header exceeds policy");
		}
		return value;
	}

	private String baseType(String value) {
		return value == null ? "" : value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
	}

	private Instant instant(Date value) {
		return value == null ? null : value.toInstant();
	}

	private static final class DsnBudget {
		private int remaining;

		private DsnBudget(int maximum) {
			this.remaining = maximum;
		}

		private int remaining() {
			return remaining;
		}

		private void consume(int bytes) throws InboundFormatException {
			if (bytes < 0 || bytes > remaining) {
				throw new InboundFormatException("Delivery status metadata exceeds limit");
			}
			remaining -= bytes;
		}

		private boolean tryConsume(int bytes) {
			if (bytes < 0 || bytes > remaining) return false;
			remaining -= bytes;
			return true;
		}
	}

	private static final class RawBudgetInputStream extends InputStream {
		private final InputStream delegate;
		private final DsnBudget budget;

		private RawBudgetInputStream(InputStream delegate, DsnBudget budget) {
			this.delegate = delegate;
			this.budget = budget;
		}

		@Override public int read() throws IOException {
			if (budget.remaining() == 0) throw new RawBudgetIOException();
			try {
				int value = delegate.read();
				if (value >= 0 && !budget.tryConsume(1)) throw new RawBudgetIOException();
				return value;
			}
			catch (RawBudgetIOException failure) {
				throw failure;
			}
			catch (IOException failure) {
				throw new RawTransportIOException(failure);
			}
		}

		@Override public int read(byte[] bytes, int offset, int length) throws IOException {
			if (budget.remaining() == 0) throw new RawBudgetIOException();
			int bounded = Math.min(length, budget.remaining());
			try {
				int read = delegate.read(bytes, offset, bounded);
				if (read > 0 && !budget.tryConsume(read)) throw new RawBudgetIOException();
				return read;
			}
			catch (RawBudgetIOException failure) {
				throw failure;
			}
			catch (IOException failure) {
				throw new RawTransportIOException(failure);
			}
		}

		@Override public void close() throws IOException {
			try {
				delegate.close();
			}
			catch (IOException failure) {
				throw new RawTransportIOException(failure);
			}
		}
	}

	private static final class RawBudgetIOException extends IOException { }

	private static final class RawTransportIOException extends IOException {
		private final IOException original;

		private RawTransportIOException(IOException original) {
			super(original);
			this.original = original;
		}

		private IOException original() {
			return original;
		}
	}

	private static final class InboundFormatException extends Exception {
		private InboundFormatException(String message) {
			super(message);
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

	private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) return true;
			current = current.getCause();
		}
		return false;
	}

	private boolean isMailboxAccessFailure(Throwable error) {
		return hasCause(error, FolderClosedException.class)
				|| hasCause(error, StoreClosedException.class)
				|| hasCause(error, ProtocolException.class)
				|| hasCause(error, SocketTimeoutException.class)
				|| hasCause(error, java.net.SocketException.class)
				|| hasCause(error, SSLException.class)
				|| hasCause(error, ConnectException.class)
				|| hasCause(error, UnknownHostException.class);
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
