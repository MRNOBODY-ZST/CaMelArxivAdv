package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.email.tracking.MailTrackingModels;
import com.camel_hub.advertisement.email.tracking.MailTrackingService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

public final class SmtpService {
	private static final Logger LOGGER = LoggerFactory.getLogger(SmtpService.class);

	private final SmtpRepository repository;
	private final SmtpSecretCrypto crypto;
	private final SmtpPolicy policy;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;
	private final SmtpTransport transport;
	private final MailTrackingService tracking;

	public SmtpService(
			SmtpRepository repository, SmtpSecretCrypto crypto, SmtpPolicy policy,
			AuditService auditService, SensitiveValueHasher hasher, TransactionalOperator transactions,
			SmtpTransport transport, MailTrackingService tracking
	) {
		this.repository = repository;
		this.crypto = crypto;
		this.policy = policy;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
		this.transport = transport;
		this.tracking = tracking;
	}

	public Mono<PageResponse<SmtpAccountView>> list(int page, int pageSize) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			return Mono.error(new SmtpValidationException("SMTP account page is invalid"));
		}
		return Mono.zip(repository.list(Math.multiplyExact(page - 1, pageSize), pageSize).map(this::view).collectList(),
				repository.count()).map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<SmtpAccountView> get(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new SmtpNotFoundException())).map(this::view);
	}

	public Mono<SmtpAccountView> create(
			UUID actorId, SmtpCommand command, AuthenticationRequestContext context
	) {
		SmtpRepository.SmtpWrite write = normalize(command, null);
		return repository.create(write, actorId)
				.flatMap(created -> audit("SMTP_ACCOUNT_CREATED", created, actorId, context).thenReturn(created))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new SmtpConflictException("An SMTP account with this name already exists"));
	}

	public Mono<SmtpAccountView> update(
			UUID actorId, UUID id, long expectedLockVersion, SmtpCommand command,
			AuthenticationRequestContext context
	) {
		return repository.find(id).switchIfEmpty(Mono.error(new SmtpNotFoundException()))
				.flatMap(existing -> repository.update(id, expectedLockVersion, normalize(command, existing))
						.switchIfEmpty(Mono.error(new SmtpConflictException("SMTP account changed; refresh before saving"))))
				.flatMap(updated -> audit("SMTP_ACCOUNT_UPDATED", updated, actorId, context).thenReturn(updated))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new SmtpConflictException("An SMTP account with this name already exists"));
	}

	public Mono<Void> delete(
			UUID actorId, UUID id, long expectedLockVersion, AuthenticationRequestContext context
	) {
		return repository.find(id).switchIfEmpty(Mono.error(new SmtpNotFoundException()))
				.flatMap(existing -> repository.referencedCampaigns(id).flatMap(references -> {
					if (references > 0) return Mono.error(new SmtpConflictException("SMTP account is referenced by a campaign"));
					return repository.delete(id, expectedLockVersion).flatMap(rows -> rows == 1
							? audit("SMTP_ACCOUNT_DELETED", existing, actorId, context)
							: Mono.error(new SmtpConflictException("SMTP account changed; refresh before deleting")));
				})).as(transactions::transactional);
	}

	public Mono<TestResult> testConnection(
			UUID actorId, UUID id, AuthenticationRequestContext context
	) {
		return repository.find(id).switchIfEmpty(Mono.error(new SmtpNotFoundException()))
				.flatMap(account -> Mono.fromRunnable(() -> transport.testConnection(account))
						.subscribeOn(Schedulers.boundedElastic())
						.then(repository.recordTest(id, true, null))
						.then(audit("SMTP_CONNECTION_TEST_SUCCEEDED", account, actorId, context))
						.thenReturn(new TestResult("CONNECTION_SUCCEEDED", null, UUID.randomUUID().toString()))
						.onErrorResume(SmtpTransportException.class, failure -> repository
								.recordTest(id, false, failure.category().name())
								.then(auditFailure("SMTP_CONNECTION_TEST_FAILED", account, actorId, context,
										failure.category().name()))
								.then(Mono.error(failure))));
	}

	public Mono<TestResult> sendDiagnostic(
			UUID actorId, UUID id, String recipient, String subject, String body,
			boolean trackOpens, AuthenticationRequestContext context
	) {
		String safeRecipient = email(recipient, "Test recipient");
		String safeSubject = safeText(subject, 200, "Test subject");
		String safeBody = body == null || body.isBlank() ? "CaMel arXiv SMTP diagnostic" : body.strip();
		if (safeBody.length() > 5_000) throw new SmtpValidationException("Test body is too long");
		return repository.find(id).switchIfEmpty(Mono.error(new SmtpNotFoundException()))
				.flatMap(account -> send(account, new SmtpTransport.OutboundMessage(
						safeRecipient, safeSubject, account.defaultFromName(), account.replyTo(),
						"<p>" + org.jsoup.nodes.Entities.escape(safeBody) + "</p>", safeBody,
						UUID.randomUUID().toString()), actorId, context, MailTrackingModels.Source.SMTP_DIAGNOSTIC, trackOpens));
	}

	public Mono<TestResult> send(
			SmtpRepository.SmtpAccountRecord account, SmtpTransport.OutboundMessage message,
			UUID actorId, AuthenticationRequestContext context, MailTrackingModels.Source source, boolean trackOpens
	) {
		policy.validateDestination(account.host(), account.port(), account.tlsMode());
		String auditPrefix = source == MailTrackingModels.Source.SMTP_DIAGNOSTIC ? "SMTP_TEST_EMAIL" : "TEMPLATE_TEST_SEND";
		return tracking.send(actorId, account.id(), source, message, trackOpens, outbound -> transport.send(account, outbound))
				.then(Mono.defer(() -> repository.recordTest(account.id(), true, null)
						.then(audit(auditPrefix + "_ACCEPTED", account, actorId, context)))
						.onErrorResume(error -> postSendMetadataUnavailable(message.correlationId()))
						.thenReturn(new TestResult("SMTP_ACCEPTED", null, message.correlationId())))
				.onErrorResume(SmtpTransportException.class, failure -> Mono.defer(() -> repository
						.recordTest(account.id(), false, failure.category().name())
						.then(auditFailure(auditPrefix + "_FAILED", account, actorId, context, failure.category().name())))
						.onErrorResume(error -> postSendMetadataUnavailable(message.correlationId()))
						.then(Mono.error(failure)));
	}

	private Mono<Void> postSendMetadataUnavailable(String correlationId) {
		LOGGER.warn("Mail send account/audit metadata unavailable recordId={}", correlationId);
		return Mono.empty();
	}

	public Mono<SmtpRepository.SmtpAccountRecord> account(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new SmtpNotFoundException()));
	}

	private SmtpRepository.SmtpWrite normalize(
			SmtpCommand command, SmtpRepository.SmtpAccountRecord existing
	) {
		if (command == null) throw new SmtpValidationException("SMTP account command is required");
		String name = safeText(command.name(), 120, "SMTP account name");
		String host = safeText(command.host(), 255, "SMTP host").toLowerCase(java.util.Locale.ROOT);
		SmtpModels.TlsMode tlsMode;
		try {
			tlsMode = SmtpModels.TlsMode.valueOf(command.tlsMode());
		}
		catch (RuntimeException exception) {
			throw new SmtpValidationException("SMTP TLS mode is invalid");
		}
		policy.validateDestination(host, command.port(), tlsMode);
		String username = blankToNull(command.username());
		if (username != null && username.length() > 255) throw new SmtpValidationException("SMTP username is too long");
		String fromEmail = email(command.fromEmail(), "From email");
		String replyTo = email(command.replyTo(), "Reply-To");
		String fromName = safeText(command.defaultFromName(), 160, "Default sender name");
		validateLimits(command);

		byte[] ciphertext = existing == null ? null : existing.passwordCiphertext();
		byte[] nonce = existing == null ? null : existing.passwordNonce();
		if (command.password() != null && !command.password().isEmpty()) {
			char[] password = command.password().toCharArray();
			try {
				SmtpSecretCrypto.EncryptedSecret encrypted = crypto.encrypt(password);
				ciphertext = encrypted.ciphertext();
				nonce = encrypted.nonce();
			}
			finally {
				Arrays.fill(password, '\0');
			}
		}
		if (username != null && ciphertext == null) {
			throw new SmtpValidationException("SMTP password is required when a username is configured");
		}
		return new SmtpRepository.SmtpWrite(
				name, host, command.port(), tlsMode, username, ciphertext, nonce, fromEmail, fromName, replyTo,
				command.perMinuteLimit(), command.perHourLimit(), command.perDayLimit(),
				command.perDomainHourLimit(), command.enabled());
	}

	private void validateLimits(SmtpCommand command) {
		if (command.perMinuteLimit() < 1 || command.perHourLimit() < command.perMinuteLimit()
				|| command.perDayLimit() < command.perHourLimit()
				|| command.perDomainHourLimit() < 1
				|| command.perDomainHourLimit() > command.perHourLimit()) {
			throw new SmtpValidationException("SMTP rate limits are invalid");
		}
	}

	private String safeText(String value, int max, String label) {
		String normalized = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		if (normalized.isEmpty() || normalized.length() > max
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new SmtpValidationException(label + " is invalid");
		}
		return normalized;
	}

	private String blankToNull(String value) {
		if (value == null || value.isBlank()) return null;
		return Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
	}

	private String email(String value, String label) {
		String normalized = value == null ? "" : value.strip();
		try {
			InternetAddress address = new InternetAddress(normalized, true);
			if (!address.getAddress().equals(normalized) || normalized.contains("\r") || normalized.contains("\n")) {
				throw new AddressException();
			}
			return normalized;
		}
		catch (AddressException exception) {
			throw new SmtpValidationException(label + " is invalid");
		}
	}

	private Mono<Void> audit(
			String action, SmtpRepository.SmtpAccountRecord account, UUID actorId,
			AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "SMTP_ACCOUNT", account.id().toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(), Map.of(
						"name", account.name(), "tlsMode", account.tlsMode().name(), "enabled", account.enabled(),
						"passwordConfigured", account.passwordCiphertext() != null), AuditResult.SUCCESS, null));
	}

	private Mono<Void> auditFailure(
			String action, SmtpRepository.SmtpAccountRecord account, UUID actorId,
			AuthenticationRequestContext context, String category
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "SMTP_ACCOUNT", account.id().toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(),
				Map.of("name", account.name(), "category", category), AuditResult.FAILURE, category));
	}

	private SmtpAccountView view(SmtpRepository.SmtpAccountRecord value) {
		return new SmtpAccountView(
				value.id(), value.name(), value.host(), value.port(), value.tlsMode(), value.username(),
				value.passwordCiphertext() != null, value.fromEmail(), value.defaultFromName(), value.replyTo(),
				value.perMinuteLimit(), value.perHourLimit(), value.perDayLimit(), value.perDomainHourLimit(),
				value.enabled(), value.lastTestedAt(), value.lastTestStatus(), value.lastTestError(),
				value.lockVersion(), value.createdAt(), value.updatedAt());
	}

	public record SmtpCommand(
			String name, String host, int port, String tlsMode, String username, String password,
			String fromEmail, String defaultFromName, String replyTo, int perMinuteLimit,
			int perHourLimit, int perDayLimit, int perDomainHourLimit, boolean enabled
	) { }

	public record SmtpAccountView(
			UUID id, String name, String host, int port, SmtpModels.TlsMode tlsMode, String username,
			boolean passwordConfigured, String fromEmail, String defaultFromName, String replyTo,
			int perMinuteLimit, int perHourLimit, int perDayLimit, int perDomainHourLimit,
			boolean enabled, Instant lastTestedAt, String lastTestStatus, String lastTestError,
			long lockVersion, Instant createdAt, Instant updatedAt
	) { }

	public record TestResult(String status, String errorCategory, String correlationId) { }
}
