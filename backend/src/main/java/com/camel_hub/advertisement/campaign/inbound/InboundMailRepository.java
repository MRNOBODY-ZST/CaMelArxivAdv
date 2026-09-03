package com.camel_hub.advertisement.campaign.inbound;

import com.camel_hub.advertisement.email.mailbox.MailboxModels;
import com.camel_hub.advertisement.email.mailbox.MailboxRepository;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Leased UID cursor and isolated production/safety inbound persistence. */
public final class InboundMailRepository {
	private static final Instant COOLDOWN_SENTINEL = Instant.parse("1900-01-01T00:00:00Z");
	private static final SecureRandom RANDOM = new SecureRandom();
	private final DatabaseClient database;
	private final TransactionalOperator transactions;

	public InboundMailRepository(DatabaseClient database, TransactionalOperator transactions) {
		this.database = database;
		this.transactions = transactions;
	}

	public Flux<UUID> dueMailboxIds(Instant now, int limit) {
		return database.sql("""
				SELECT mailbox.id
				FROM mailbox_accounts mailbox
				LEFT JOIN mailbox_sync_cursors cursor
				  ON cursor.mailbox_account_id = mailbox.id AND cursor.folder_name = mailbox.folder_name
				WHERE mailbox.protocol = 'IMAP' AND mailbox.enabled
				  AND mailbox.last_test_status = 'SUCCEEDED' AND mailbox.last_tested_at IS NOT NULL
				  AND mailbox.last_tested_at >= mailbox.updated_at
				  AND (cursor.lease_hash IS NULL OR cursor.lease_expires_at <= :now)
				  AND (
				      EXISTS (SELECT 1 FROM campaigns campaign
				              JOIN campaign_recipients recipient ON recipient.campaign_id = campaign.id
				              WHERE campaign.mailbox_account_id = mailbox.id
				                AND recipient.rfc_message_id IS NOT NULL)
				      OR EXISTS (SELECT 1 FROM campaign_safety_runs run
				                 JOIN campaign_safety_messages message ON message.run_id = run.id
				                 WHERE run.mailbox_account_id = mailbox.id
				                   AND message.rfc_message_id IS NOT NULL))
				ORDER BY COALESCE(cursor.updated_at, mailbox.updated_at), mailbox.id
				LIMIT :limit
				""").bind("now", now).bind("limit", Math.max(1, Math.min(limit, 100)))
				.map((row, metadata) -> row.get("id", UUID.class)).all();
	}

	public Mono<CursorLease> claim(UUID mailboxId, Instant now, Duration leaseDuration) {
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			return Mono.error(new IllegalArgumentException("Inbound mailbox lease duration must be positive"));
		}
		byte[] token = new byte[32];
		RANDOM.nextBytes(token);
		byte[] leaseHash = sha256(token);
		Mono<CursorLease> claimed = eligibleAccount(mailboxId)
				.flatMap(account -> database.sql("""
						INSERT INTO mailbox_sync_cursors (
						    mailbox_account_id, folder_name, uid_validity, last_remote_uid, updated_at
						) VALUES (:mailbox, :folder, 0, 0, :now)
						ON CONFLICT (mailbox_account_id, folder_name) DO NOTHING
						""").bind("mailbox", mailboxId).bind("folder", account.folderName()).bind("now", now)
						.fetch().rowsUpdated().then(database.sql("""
						UPDATE mailbox_sync_cursors
						SET lease_hash = :lease, lease_expires_at = :expires, updated_at = :now
						WHERE mailbox_account_id = :mailbox AND folder_name = :folder
						  AND (lease_hash IS NULL OR lease_expires_at <= :now)
						RETURNING uid_validity, last_remote_uid
						""").bind("lease", leaseHash).bind("expires", now.plus(leaseDuration))
						.bind("now", now).bind("mailbox", mailboxId).bind("folder", account.folderName())
						.map((row, metadata) -> new CursorLease(
								account, account.folderName(), requiredLong(row, "uid_validity"),
								requiredLong(row, "last_remote_uid"), token)).one()));
		return transactions.transactional(claimed);
	}

	public Mono<Long> alignUidValidity(
			CursorLease lease, long uidValidity, long cursorFloor, Instant now
	) {
		if (uidValidity < 0 || cursorFloor < 0) {
			return Mono.error(new IllegalArgumentException("Mailbox UID identity values must be nonnegative"));
		}
		return database.sql("""
				UPDATE mailbox_sync_cursors
				SET last_remote_uid = CASE
				        WHEN uid_validity = :uidValidity THEN last_remote_uid ELSE :cursorFloor END,
				    uid_validity = :uidValidity, updated_at = :now
				WHERE mailbox_account_id = :mailbox AND folder_name = :folder
				  AND lease_hash = :lease AND lease_expires_at > :now
				RETURNING last_remote_uid
				""").bind("uidValidity", uidValidity).bind("cursorFloor", cursorFloor).bind("now", now)
				.bind("mailbox", lease.account().id()).bind("folder", lease.folderName())
				.bind("lease", sha256(lease.leaseToken()))
				.map((row, metadata) -> requiredLong(row, "last_remote_uid")).one()
				.switchIfEmpty(Mono.error(new IllegalStateException("Inbound mailbox lease is no longer active")));
	}

	public Mono<Void> renew(CursorLease lease, Instant now, Duration leaseDuration) {
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			return Mono.error(new IllegalArgumentException("Inbound mailbox lease duration must be positive"));
		}
		return database.sql("""
				UPDATE mailbox_sync_cursors
				SET lease_expires_at = :expires, updated_at = :now
				WHERE mailbox_account_id = :mailbox AND folder_name = :folder AND lease_hash = :lease
				  AND lease_expires_at > :now
				""").bind("expires", now.plus(leaseDuration)).bind("now", now)
				.bind("mailbox", lease.account().id()).bind("folder", lease.folderName())
				.bind("lease", sha256(lease.leaseToken())).fetch().rowsUpdated()
				.flatMap(updated -> updated.longValue() == 1L ? Mono.empty()
						: Mono.error(new IllegalStateException("Inbound mailbox lease is no longer owned")));
	}

	public Mono<Boolean> persist(
			CursorLease lease, long uidValidity, long remoteUid,
			InboundMailModels.ParsedInbound parsed, Instant receivedAt, Instant now
	) {
		Mono<Boolean> operation = lockCursor(lease, uidValidity, now).flatMap(lastUid -> {
			if (remoteUid <= lastUid) return Mono.just(false);
			return findMatch(lease.account().id(), parsed.referencedMessageIds())
					.defaultIfEmpty(Match.unmatched())
					.flatMap(match -> insertInbound(
							lease, uidValidity, remoteUid, parsed, match, receivedAt, now)
							.flatMap(inserted -> {
								Mono<Void> mutation = inserted
										? applyMatch(match, parsed, receivedAt, now) : Mono.empty();
								return mutation.then(advanceCursor(lease, uidValidity, remoteUid, now))
										.thenReturn(inserted);
							}));
		});
		return transactions.transactional(operation);
	}

	public Mono<Void> complete(CursorLease lease, Instant now) {
		return release(lease, null, true, now).flatMap(released -> released ? Mono.empty()
				: Mono.error(new IllegalStateException("Inbound mailbox lease is no longer active")));
	}

	public Mono<Void> fail(CursorLease lease, String category, Instant now) {
		String safe = category != null && category.matches("[A-Z0-9_]{1,80}")
				? category : "UNEXPECTED_FAILURE";
		Mono<Void> operation = release(lease, safe, false, now).flatMap(released -> {
			if (!released) {
				return Mono.error(new IllegalStateException("Inbound mailbox lease is no longer active"));
			}
			return database.sql("""
						INSERT INTO audit_logs (
						    actor_user_id, action, resource_type, resource_id, trace_id,
						    before_summary, after_summary, result, error_type
						) VALUES (
						    NULL, 'MAILBOX_SYNC_FAILED', 'MAILBOX_ACCOUNT', :resourceId, :traceId,
						    '{}'::jsonb, jsonb_build_object('failureCategory', :category),
						    'FAILURE', :category
						)
						""").bind("resourceId", lease.account().id().toString())
						.bind("traceId", "mailbox-sync-" + UUID.randomUUID())
						.bind("category", safe).fetch().rowsUpdated().then();
		});
		return transactions.transactional(operation);
	}

	private Mono<MailboxRepository.MailboxAccountRecord> eligibleAccount(UUID mailboxId) {
		return database.sql("""
				SELECT id, name, protocol, host, port, tls_mode, username, password_ciphertext,
				       password_nonce, folder_name, enabled, last_tested_at, last_test_status,
				       last_test_error, lock_version, created_by, updated_by, created_at, updated_at
				FROM mailbox_accounts
				WHERE id = :id AND protocol = 'IMAP' AND enabled
				  AND last_test_status = 'SUCCEEDED' AND last_tested_at IS NOT NULL
				  AND last_tested_at >= updated_at
				FOR UPDATE
				""").bind("id", mailboxId).map((row, metadata) -> mailboxAccount(row)).one();
	}

	private MailboxRepository.MailboxAccountRecord mailboxAccount(Row row) {
		return new MailboxRepository.MailboxAccountRecord(
				row.get("id", UUID.class), row.get("name", String.class),
				MailboxModels.Protocol.valueOf(row.get("protocol", String.class)), row.get("host", String.class),
				requiredInt(row, "port"), MailboxModels.TlsMode.valueOf(row.get("tls_mode", String.class)),
				row.get("username", String.class), row.get("password_ciphertext", byte[].class),
				row.get("password_nonce", byte[].class), row.get("folder_name", String.class),
				Boolean.TRUE.equals(row.get("enabled", Boolean.class)), row.get("last_tested_at", Instant.class),
				row.get("last_test_status", String.class), row.get("last_test_error", String.class),
				requiredLong(row, "lock_version"), row.get("created_by", UUID.class),
				row.get("updated_by", UUID.class), row.get("created_at", Instant.class),
				row.get("updated_at", Instant.class));
	}

	private Mono<Long> lockCursor(CursorLease lease, long uidValidity, Instant now) {
		return database.sql("""
				SELECT last_remote_uid
				FROM mailbox_sync_cursors
				WHERE mailbox_account_id = :mailbox AND folder_name = :folder
				  AND uid_validity = :uidValidity AND lease_hash = :lease AND lease_expires_at > :now
				FOR UPDATE
				""").bind("mailbox", lease.account().id()).bind("folder", lease.folderName())
				.bind("uidValidity", uidValidity).bind("lease", sha256(lease.leaseToken())).bind("now", now)
				.map((row, metadata) -> requiredLong(row, "last_remote_uid")).one()
				.switchIfEmpty(Mono.error(new IllegalStateException("Inbound mailbox lease is no longer active")));
	}

	private Mono<Match> findMatch(UUID mailboxId, List<String> references) {
		return Flux.fromIterable(references == null ? List.<String>of() : references)
				.concatMap(reference -> reference.startsWith("<safety-")
						? findSafety(mailboxId, reference) : findProduction(mailboxId, reference))
				.collectList()
				.map(matches -> matches.size() == 1 ? matches.getFirst() : Match.unmatched());
	}

	private Mono<Match> findProduction(UUID mailboxId, String reference) {
		return database.sql("""
				SELECT recipient.id
				FROM campaign_recipients recipient
				JOIN campaigns campaign ON campaign.id = recipient.campaign_id
				WHERE campaign.mailbox_account_id = :mailbox AND recipient.rfc_message_id = :messageId
				""").bind("mailbox", mailboxId).bind("messageId", reference)
				.map((row, metadata) -> new Match(
						row.get("id", UUID.class), null, null, reference)).one();
	}

	private Mono<Match> findSafety(UUID mailboxId, String reference) {
		return database.sql("""
				SELECT message.id, message.run_id
				FROM campaign_safety_messages message
				JOIN campaign_safety_runs run ON run.id = message.run_id
				WHERE run.mailbox_account_id = :mailbox AND message.rfc_message_id = :messageId
				""").bind("mailbox", mailboxId).bind("messageId", reference)
				.map((row, metadata) -> new Match(
						null, row.get("id", UUID.class), row.get("run_id", UUID.class), reference)).one();
	}

	private Mono<Boolean> insertInbound(
			CursorLease lease, long uidValidity, long remoteUid,
			InboundMailModels.ParsedInbound parsed, Match match, Instant receivedAt, Instant now
	) {
		boolean matched = match.matched();
		String type = matched ? parsed.type().name() : InboundMailModels.InboundType.UNMATCHED.name();
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				INSERT INTO mailbox_inbound_events (
				    mailbox_account_id, folder_name, uid_validity, remote_uid, inbound_type,
				    referenced_message_id, campaign_recipient_id, safety_message_id,
				    diagnostic_code, permanent, received_at, created_at
				) VALUES (
				    :mailbox, :folder, :uidValidity, :remoteUid, :type,
				    :messageId, :recipient, :safetyMessage, :diagnostic, :permanent, :receivedAt, :now
				)
				ON CONFLICT (mailbox_account_id, folder_name, uid_validity, remote_uid) DO NOTHING
				""").bind("mailbox", lease.account().id()).bind("folder", lease.folderName())
				.bind("uidValidity", uidValidity).bind("remoteUid", remoteUid).bind("type", type);
		statement = bindNullable(statement, "messageId", matched ? match.messageId() : null, String.class);
		statement = bindNullable(statement, "recipient", match.recipientId(), UUID.class);
		statement = bindNullable(statement, "safetyMessage", match.safetyMessageId(), UUID.class);
		statement = bindNullable(statement, "diagnostic",
				matched ? parsed.diagnosticCode() : null, String.class);
		statement = bindNullable(statement, "permanent", matched ? parsed.permanent() : null, Boolean.class);
		statement = bindNullable(statement, "receivedAt", receivedAt, Instant.class);
		return statement.bind("now", now).fetch().rowsUpdated().map(rows -> rows.longValue() == 1L);
	}

	private Mono<Void> applyMatch(
			Match match, InboundMailModels.ParsedInbound parsed, Instant receivedAt, Instant now
	) {
		if (match.safetyMessageId() != null) return insertSafetyEvent(match, parsed, receivedAt, now);
		if (match.recipientId() == null) return Mono.empty();
		if (parsed.type() == InboundMailModels.InboundType.REPLY) {
			return database.sql("""
					UPDATE campaign_recipients
					SET replied_at = COALESCE(replied_at, :receivedAt)
					WHERE id = :recipient
					""").bind("receivedAt", receivedAt == null ? now : receivedAt)
					.bind("recipient", match.recipientId()).fetch().rowsUpdated().then();
		}
		if (parsed.type() == InboundMailModels.InboundType.BOUNCE && Boolean.TRUE.equals(parsed.permanent())) {
			return applyPermanentBounce(match.recipientId(), now);
		}
		return Mono.empty();
	}

	private Mono<Void> applyPermanentBounce(UUID recipientId, Instant now) {
		return database.sql("""
				SELECT status, email_hmac FROM campaign_recipients WHERE id = :recipient FOR UPDATE
				""").bind("recipient", recipientId)
				.map((row, metadata) -> new BounceTarget(
						row.get("status", String.class), row.get("email_hmac", byte[].class))).one()
				.flatMap(target -> lockBounceCooldown(target.emailHmac(), now).then(Mono.defer(() -> {
					if ("CONNECTING".equals(target.status())) {
						return Mono.error(new IllegalStateException("Recipient delivery settlement is pending"));
					}
					if ("BOUNCED".equals(target.status())) return Mono.empty();
					if (!List.of("SMTP_ACCEPTED", "OUTCOME_UNKNOWN").contains(target.status())) {
						return Mono.empty();
					}
					return database.sql("""
					UPDATE campaign_recipients
					SET status = 'BOUNCED', final_failure_at = COALESCE(final_failure_at, :now),
					    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL
					WHERE id = :recipient AND status IN ('SMTP_ACCEPTED', 'OUTCOME_UNKNOWN')
					""").bind("now", now).bind("recipient", recipientId).fetch().rowsUpdated()
					.flatMap(updated -> updated.longValue() == 1L
							? upsertBounceSuppression(recipientId, now)
									.flatMap(changed -> insertBounceAudit(recipientId, changed, now))
							: Mono.error(new IllegalStateException("Recipient bounce transition lost its lock")));
				})));
	}

	private Mono<Void> lockBounceCooldown(byte[] emailHmac, Instant now) {
		if (emailHmac == null || emailHmac.length != 32) {
			return Mono.error(new IllegalStateException("Recipient delivery identity is invalid"));
		}
		return database.sql("""
				INSERT INTO recipient_delivery_cooldowns (email_hmac, last_smtp_accepted_at, updated_at)
				VALUES (:hmac, :sentinel, :now)
				ON CONFLICT (email_hmac) DO NOTHING
				""").bind("hmac", emailHmac).bind("sentinel", COOLDOWN_SENTINEL).bind("now", now)
				.fetch().rowsUpdated().then(database.sql("""
						SELECT email_hmac FROM recipient_delivery_cooldowns
						WHERE email_hmac = :hmac FOR UPDATE
						""").bind("hmac", emailHmac).map((row, metadata) -> row.get(0, byte[].class)).one())
				.switchIfEmpty(Mono.error(new IllegalStateException("Recipient delivery fence is unavailable")))
				.then();
	}

	private Mono<Void> insertBounceAudit(UUID recipientId, boolean suppressionChanged, Instant now) {
		return database.sql("""
				INSERT INTO audit_logs (
				    actor_user_id, action, resource_type, resource_id, trace_id,
				    before_summary, after_summary, result, error_type
				)
				SELECT NULL, 'CAMPAIGN_RECIPIENT_BOUNCED', 'CAMPAIGN_RECIPIENT', :resourceId, :traceId,
				    '{}'::jsonb,
				    jsonb_build_object(
				        'status', 'BOUNCED',
				        'suppressionReason', suppression.reason,
				        'source', suppression.source,
				        'suppressionChanged', :suppressionChanged
				    ),
				    'SUCCESS', NULL
				FROM campaign_recipients recipient
				JOIN suppression_entries suppression ON suppression.email_hmac = recipient.email_hmac
				WHERE recipient.id = :recipient
				  AND (suppression.expires_at IS NULL OR suppression.expires_at > :now)
				""").bind("resourceId", recipientId.toString())
				.bind("traceId", "inbound-bounce-" + UUID.randomUUID())
				.bind("suppressionChanged", suppressionChanged).bind("recipient", recipientId).bind("now", now)
				.fetch().rowsUpdated().flatMap(updated -> updated.longValue() == 1L ? Mono.empty()
						: Mono.error(new IllegalStateException("Recipient bounce suppression audit is unavailable")));
	}

	private Mono<Boolean> upsertBounceSuppression(UUID recipientId, Instant now) {
		return database.sql("""
						INSERT INTO suppression_entries (
						    id, email_hmac, email_domain, reason, source, notes, created_by, created_at, expires_at
						)
						SELECT :id, email_hmac, email_domain, 'BOUNCED', 'IMAP_DSN', NULL, NULL, :now, NULL
						FROM campaign_recipients WHERE id = :recipient
						ON CONFLICT (email_hmac) DO UPDATE SET
						    email_domain = EXCLUDED.email_domain,
						    reason = 'BOUNCED',
						    source = 'IMAP_DSN',
						    notes = NULL,
						    created_by = NULL,
						    created_at = :now,
						    expires_at = NULL
						WHERE suppression_entries.expires_at IS NOT NULL
						  AND suppression_entries.expires_at <= :now
						""").bind("id", UUID.randomUUID()).bind("now", now)
				.bind("recipient", recipientId).fetch().rowsUpdated()
				.map(updated -> updated.longValue() == 1L);
	}

	private Mono<Void> insertSafetyEvent(
			Match match, InboundMailModels.ParsedInbound parsed, Instant receivedAt, Instant now
	) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				INSERT INTO campaign_safety_events (
				    run_id, safety_message_id, safety_link_id, event_type,
				    fingerprint_hash, minute_bucket, occurred_at, classification,
				    classification_reason, diagnostic_code
				) VALUES (
				    :run, :message, NULL, :type, NULL, NULL, :occurredAt, NULL, NULL, :diagnostic
				)
				""").bind("run", match.safetyRunId()).bind("message", match.safetyMessageId())
				.bind("type", parsed.type().name()).bind("occurredAt", receivedAt == null ? now : receivedAt);
		statement = bindNullable(statement, "diagnostic", parsed.diagnosticCode(), String.class);
		return statement.fetch().rowsUpdated().then();
	}

	private Mono<Void> advanceCursor(CursorLease lease, long uidValidity, long remoteUid, Instant now) {
		return database.sql("""
				UPDATE mailbox_sync_cursors
				SET last_remote_uid = :remoteUid, updated_at = :now
				WHERE mailbox_account_id = :mailbox AND folder_name = :folder
				  AND uid_validity = :uidValidity AND lease_hash = :lease
				  AND lease_expires_at > :now AND last_remote_uid < :remoteUid
				""").bind("remoteUid", remoteUid).bind("now", now)
				.bind("mailbox", lease.account().id()).bind("folder", lease.folderName())
				.bind("uidValidity", uidValidity).bind("lease", sha256(lease.leaseToken()))
				.fetch().rowsUpdated().flatMap(updated -> updated.longValue() == 1L ? Mono.empty()
						: Mono.error(new IllegalStateException("Inbound cursor advance lost its lease")));
	}

	private Mono<Boolean> release(CursorLease lease, String errorCategory, boolean succeeded, Instant now) {
		DatabaseClient.GenericExecuteSpec statement = database.sql("""
				UPDATE mailbox_sync_cursors
				SET lease_hash = NULL, lease_expires_at = NULL,
				    last_synced_at = CASE WHEN :succeeded THEN :now ELSE last_synced_at END,
				    last_error_category = :error, updated_at = :now
				WHERE mailbox_account_id = :mailbox AND folder_name = :folder AND lease_hash = :lease
				  AND lease_expires_at > :now
				""").bind("mailbox", lease.account().id()).bind("folder", lease.folderName())
				.bind("lease", sha256(lease.leaseToken())).bind("now", now);
		statement = statement.bind("succeeded", succeeded);
		statement = bindNullable(statement, "error", errorCategory, String.class);
		return statement.fetch().rowsUpdated().map(updated -> updated.longValue() == 1L);
	}

	private <T> DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, T value, Class<T> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	private byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static int requiredInt(Row row, String name) {
		Number value = row.get(name, Number.class);
		if (value == null) throw new IllegalStateException("Missing inbound numeric field: " + name);
		return value.intValue();
	}

	private static long requiredLong(Row row, String name) {
		Number value = row.get(name, Number.class);
		if (value == null) throw new IllegalStateException("Missing inbound numeric field: " + name);
		return value.longValue();
	}

	public record CursorLease(
			MailboxRepository.MailboxAccountRecord account, String folderName,
			long uidValidity, long lastRemoteUid, byte[] leaseToken
	) {
		public CursorLease {
			leaseToken = Arrays.copyOf(leaseToken, leaseToken.length);
		}

		@Override public byte[] leaseToken() {
			return Arrays.copyOf(leaseToken, leaseToken.length);
		}
	}

	private record Match(
			UUID recipientId, UUID safetyMessageId, UUID safetyRunId, String messageId
	) {
		static Match unmatched() { return new Match(null, null, null, null); }
		boolean matched() { return recipientId != null || safetyMessageId != null; }
	}

	private record BounceTarget(String status, byte[] emailHmac) {
		private BounceTarget {
			emailHmac = emailHmac == null ? null : Arrays.copyOf(emailHmac, emailHmac.length);
		}

		@Override public byte[] emailHmac() {
			return emailHmac == null ? null : Arrays.copyOf(emailHmac, emailHmac.length);
		}
	}
}
