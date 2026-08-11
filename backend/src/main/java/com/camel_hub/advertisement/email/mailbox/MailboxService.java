package com.camel_hub.advertisement.email.mailbox;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MailboxService {
	private final MailboxRepository repository;
	private final SmtpSecretCrypto crypto;
	private final MailboxPolicy policy;
	private final MailboxTransport transport;
	private final MailboxProperties properties;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public MailboxService(
			MailboxRepository repository, SmtpSecretCrypto crypto, MailboxPolicy policy,
			MailboxTransport transport, MailboxProperties properties, AuditService auditService,
			SensitiveValueHasher hasher, TransactionalOperator transactions
	) {
		this.repository = repository;
		this.crypto = crypto;
		this.policy = policy;
		this.transport = transport;
		this.properties = properties;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<PageResponse<MailboxAccountView>> list(int page, int pageSize) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			return Mono.error(new MailboxValidationException("Mailbox account page is invalid"));
		}
		return Mono.zip(repository.list(Math.multiplyExact(page - 1, pageSize), pageSize)
				.map(this::view).collectList(), repository.count())
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<MailboxAccountView> get(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new MailboxNotFoundException())).map(this::view);
	}

	public Mono<MailboxAccountView> create(
			UUID actorId, MailboxCommand command, AuthenticationRequestContext context
	) {
		MailboxRepository.MailboxWrite write = normalize(command, null);
		return repository.create(write, actorId)
				.flatMap(created -> audit("MAILBOX_ACCOUNT_CREATED", created, actorId, context).thenReturn(created))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new MailboxConflictException("A mailbox account with this name already exists"));
	}

	public Mono<MailboxAccountView> update(
			UUID actorId, UUID id, long expectedLockVersion, MailboxCommand command,
			AuthenticationRequestContext context
	) {
		return repository.find(id).switchIfEmpty(Mono.error(new MailboxNotFoundException()))
				.flatMap(existing -> repository.update(id, expectedLockVersion, normalize(command, existing), actorId)
						.switchIfEmpty(Mono.error(new MailboxConflictException(
								"Mailbox account changed; refresh before saving"))))
				.flatMap(updated -> audit("MAILBOX_ACCOUNT_UPDATED", updated, actorId, context).thenReturn(updated))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new MailboxConflictException("A mailbox account with this name already exists"));
	}

	public Mono<Void> delete(
			UUID actorId, UUID id, long expectedLockVersion, AuthenticationRequestContext context
	) {
		return repository.find(id).switchIfEmpty(Mono.error(new MailboxNotFoundException()))
				.flatMap(existing -> repository.delete(id, expectedLockVersion).flatMap(rows -> rows == 1
						? audit("MAILBOX_ACCOUNT_DELETED", existing, actorId, context)
						: Mono.error(new MailboxConflictException(
								"Mailbox account changed; refresh before deleting"))))
				.as(transactions::transactional);
	}

	public Mono<ConnectionTestResult> testConnection(
			UUID actorId, UUID id, AuthenticationRequestContext context
	) {
		return repository.find(id).switchIfEmpty(Mono.error(new MailboxNotFoundException()))
				.flatMap(account -> Mono.fromRunnable(() -> transport.testConnection(account))
						.subscribeOn(Schedulers.boundedElastic())
						.then(repository.recordTest(id, true, null))
						.then(audit("MAILBOX_CONNECTION_TEST_SUCCEEDED", account, actorId, context))
						.thenReturn(new ConnectionTestResult(
								"CONNECTION_SUCCEEDED", null, UUID.randomUUID().toString()))
						.onErrorResume(MailboxTransportException.class, failure -> repository
								.recordTest(id, false, failure.category().name())
								.then(auditFailure("MAILBOX_CONNECTION_TEST_FAILED", account, actorId,
										context, failure.category().name()))
								.then(Mono.error(failure))));
	}

	public Mono<List<MailboxTransport.MessageHeader>> preview(UUID id, int limit) {
		if (limit < 1 || limit > properties.maxPreviewMessages()) {
			return Mono.error(new MailboxValidationException("Mailbox preview limit is invalid"));
		}
		return repository.find(id).switchIfEmpty(Mono.error(new MailboxNotFoundException()))
				.flatMap(account -> {
					if (!account.enabled()) {
						return Mono.error(new MailboxValidationException("Mailbox account is disabled"));
					}
					return Mono.fromCallable(() -> transport.preview(account, limit))
							.subscribeOn(Schedulers.boundedElastic());
				});
	}

	private MailboxRepository.MailboxWrite normalize(
			MailboxCommand command, MailboxRepository.MailboxAccountRecord existing
	) {
		if (command == null) throw new MailboxValidationException("Mailbox account command is required");
		String name = safeText(command.name(), 120, "Mailbox account name");
		String host = safeText(command.host(), 255, "Mailbox host").toLowerCase(Locale.ROOT);
		MailboxModels.Protocol protocol;
		MailboxModels.TlsMode tlsMode;
		try {
			protocol = MailboxModels.Protocol.valueOf(command.protocol());
			tlsMode = MailboxModels.TlsMode.valueOf(command.tlsMode());
		}
		catch (RuntimeException exception) {
			throw new MailboxValidationException("Mailbox protocol or TLS mode is invalid");
		}
		policy.validateDestination(host, command.port(), tlsMode);
		String username = safeText(command.username(), 255, "Mailbox username");
		String folder = safeText(command.folderName(), 255, "Mailbox folder");
		if (protocol == MailboxModels.Protocol.POP3 && !"INBOX".equalsIgnoreCase(folder)) {
			throw new MailboxValidationException("POP3 mailbox folder must be INBOX");
		}
		if (protocol == MailboxModels.Protocol.POP3) folder = "INBOX";

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
		if (ciphertext == null || nonce == null) {
			throw new MailboxValidationException("Mailbox password is required");
		}
		return new MailboxRepository.MailboxWrite(
				name, protocol, host, command.port(), tlsMode, username, ciphertext, nonce, folder,
				command.enabled());
	}

	private String safeText(String value, int max, String label) {
		String normalized = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		if (normalized.isEmpty() || normalized.length() > max
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new MailboxValidationException(label + " is invalid");
		}
		return normalized;
	}

	private Mono<Void> audit(
			String action, MailboxRepository.MailboxAccountRecord account, UUID actorId,
			AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "MAILBOX_ACCOUNT", account.id().toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(), Map.of(
						"name", account.name(), "protocol", account.protocol().name(),
						"tlsMode", account.tlsMode().name(), "enabled", account.enabled(),
						"passwordConfigured", true), AuditResult.SUCCESS, null));
	}

	private Mono<Void> auditFailure(
			String action, MailboxRepository.MailboxAccountRecord account, UUID actorId,
			AuthenticationRequestContext context, String category
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "MAILBOX_ACCOUNT", account.id().toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(),
				Map.of("name", account.name(), "protocol", account.protocol().name(), "category", category),
				AuditResult.FAILURE, category));
	}

	private MailboxAccountView view(MailboxRepository.MailboxAccountRecord value) {
		return new MailboxAccountView(
				value.id(), value.name(), value.protocol(), value.host(), value.port(), value.tlsMode(),
				value.username(), true, value.folderName(), value.enabled(), value.lastTestedAt(),
				value.lastTestStatus(), value.lastTestError(), value.lockVersion(), value.createdAt(), value.updatedAt());
	}

	public record MailboxCommand(
			String name, String protocol, String host, int port, String tlsMode,
			String username, String password, String folderName, boolean enabled
	) { }

	public record MailboxAccountView(
			UUID id, String name, MailboxModels.Protocol protocol, String host, int port,
			MailboxModels.TlsMode tlsMode, String username, boolean passwordConfigured,
			String folderName, boolean enabled, Instant lastTestedAt, String lastTestStatus,
			String lastTestError, long lockVersion, Instant createdAt, Instant updatedAt
	) { }

	public record ConnectionTestResult(String status, String errorCategory, String correlationId) { }
}
