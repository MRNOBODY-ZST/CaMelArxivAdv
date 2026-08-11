package com.camel_hub.advertisement.email.mailbox;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class MailboxRepository {
	private final DatabaseClient databaseClient;

	public MailboxRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<MailboxAccountRecord> list(int offset, int limit) {
		return databaseClient.sql(selectSql() + " ORDER BY updated_at DESC, id OFFSET :offset LIMIT :limit")
				.bind("offset", offset).bind("limit", limit).map(this::map).all();
	}

	public Mono<Long> count() {
		return databaseClient.sql("SELECT count(*) AS total FROM mailbox_accounts")
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<MailboxAccountRecord> find(UUID id) {
		return databaseClient.sql(selectSql() + " WHERE id = :id")
				.bind("id", id).map(this::map).one();
	}

	public Mono<MailboxAccountRecord> create(MailboxWrite value, UUID actorId) {
		return databaseClient.sql("""
				INSERT INTO mailbox_accounts (
				    name, protocol, host, port, tls_mode, username, password_ciphertext,
				    password_nonce, folder_name, enabled, created_by, updated_by
				)
				VALUES (
				    :name, :protocol, :host, :port, :tlsMode, :username, :ciphertext,
				    :nonce, :folderName, :enabled, :actorId, :actorId
				)
				RETURNING *
				""").bind("name", value.name()).bind("protocol", value.protocol().name())
				.bind("host", value.host()).bind("port", value.port()).bind("tlsMode", value.tlsMode().name())
				.bind("username", value.username()).bind("ciphertext", value.passwordCiphertext())
				.bind("nonce", value.passwordNonce()).bind("folderName", value.folderName())
				.bind("enabled", value.enabled()).bind("actorId", actorId).map(this::map).one();
	}

	public Mono<MailboxAccountRecord> update(
			UUID id, long expectedLockVersion, MailboxWrite value, UUID actorId
	) {
		return databaseClient.sql("""
				UPDATE mailbox_accounts
				SET name = :name, protocol = :protocol, host = :host, port = :port,
				    tls_mode = :tlsMode, username = :username, password_ciphertext = :ciphertext,
				    password_nonce = :nonce, folder_name = :folderName, enabled = :enabled,
				    updated_by = :actorId, updated_at = now(), lock_version = lock_version + 1
				WHERE id = :id AND lock_version = :expectedLockVersion
				RETURNING *
				""").bind("name", value.name()).bind("protocol", value.protocol().name())
				.bind("host", value.host()).bind("port", value.port()).bind("tlsMode", value.tlsMode().name())
				.bind("username", value.username()).bind("ciphertext", value.passwordCiphertext())
				.bind("nonce", value.passwordNonce()).bind("folderName", value.folderName())
				.bind("enabled", value.enabled()).bind("actorId", actorId).bind("id", id)
				.bind("expectedLockVersion", expectedLockVersion).map(this::map).one();
	}

	public Mono<Long> delete(UUID id, long expectedLockVersion) {
		return databaseClient.sql("DELETE FROM mailbox_accounts WHERE id = :id AND lock_version = :version")
				.bind("id", id).bind("version", expectedLockVersion).fetch().rowsUpdated();
	}

	public Mono<Void> recordTest(UUID id, boolean succeeded, String errorCategory) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				UPDATE mailbox_accounts
				SET last_tested_at = now(), last_test_status = :status, last_test_error = :error,
				    updated_at = now()
				WHERE id = :id
				""").bind("status", succeeded ? "SUCCEEDED" : "FAILED").bind("id", id);
		statement = errorCategory == null
				? statement.bindNull("error", String.class) : statement.bind("error", errorCategory);
		return statement.fetch().rowsUpdated().then();
	}

	private String selectSql() {
		return """
				SELECT id, name, protocol, host, port, tls_mode, username, password_ciphertext,
				       password_nonce, folder_name, enabled, last_tested_at, last_test_status,
				       last_test_error, lock_version, created_by, updated_by, created_at, updated_at
				FROM mailbox_accounts
				""";
	}

	private MailboxAccountRecord map(Row row, RowMetadata metadata) {
		return new MailboxAccountRecord(
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

	private int requiredInt(Row row, String name) {
		Number value = row.get(name, Number.class);
		if (value == null) throw new IllegalStateException("Missing mailbox numeric field: " + name);
		return value.intValue();
	}

	private long requiredLong(Row row, String name) {
		Number value = row.get(name, Number.class);
		if (value == null) throw new IllegalStateException("Missing mailbox numeric field: " + name);
		return value.longValue();
	}

	public record MailboxWrite(
			String name, MailboxModels.Protocol protocol, String host, int port,
			MailboxModels.TlsMode tlsMode, String username, byte[] passwordCiphertext,
			byte[] passwordNonce, String folderName, boolean enabled
	) { }

	public record MailboxAccountRecord(
			UUID id, String name, MailboxModels.Protocol protocol, String host, int port,
			MailboxModels.TlsMode tlsMode, String username, byte[] passwordCiphertext,
			byte[] passwordNonce, String folderName, boolean enabled, Instant lastTestedAt,
			String lastTestStatus, String lastTestError, long lockVersion, UUID createdBy,
			UUID updatedBy, Instant createdAt, Instant updatedAt
	) { }
}
