package com.camel_hub.advertisement.email.smtp;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class SmtpRepository {

	private final DatabaseClient databaseClient;

	public SmtpRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<SmtpAccountRecord> list(int offset, int limit) {
		return databaseClient.sql(selectSql() + " ORDER BY updated_at DESC, id OFFSET :offset LIMIT :limit")
				.bind("offset", offset).bind("limit", limit).map(this::map).all();
	}

	public Mono<Long> count() {
		return databaseClient.sql("SELECT count(*) AS total FROM smtp_accounts")
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<SmtpAccountRecord> find(UUID id) {
		return databaseClient.sql(selectSql() + " WHERE id = :id")
				.bind("id", id).map(this::map).one();
	}

	public Mono<SmtpAccountRecord> create(SmtpWrite value, UUID actorId) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO smtp_accounts (
				    name, host, port, tls_mode, username, password_ciphertext, password_nonce,
				    from_email, default_from_name, reply_to, per_minute_limit, per_hour_limit,
				    per_day_limit, per_domain_hour_limit, enabled, created_by
				)
				VALUES (
				    :name, :host, :port, :tlsMode, :username, :ciphertext, :nonce,
				    :fromEmail, :fromName, :replyTo, :perMinute, :perHour,
				    :perDay, :perDomainHour, :enabled, :actorId
				)
				RETURNING *
				""").bind("name", value.name()).bind("host", value.host()).bind("port", value.port())
				.bind("tlsMode", value.tlsMode().name()).bind("fromEmail", value.fromEmail())
				.bind("fromName", value.defaultFromName()).bind("replyTo", value.replyTo())
				.bind("perMinute", value.perMinuteLimit()).bind("perHour", value.perHourLimit())
				.bind("perDay", value.perDayLimit()).bind("perDomainHour", value.perDomainHourLimit())
				.bind("enabled", value.enabled()).bind("actorId", actorId);
		statement = bindNullable(statement, "username", value.username(), String.class);
		statement = bindNullable(statement, "ciphertext", value.passwordCiphertext(), byte[].class);
		statement = bindNullable(statement, "nonce", value.passwordNonce(), byte[].class);
		return statement.map(this::map).one();
	}

	public Mono<SmtpAccountRecord> update(
			UUID id, long expectedLockVersion, SmtpWrite value
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				UPDATE smtp_accounts
				SET name = :name, host = :host, port = :port, tls_mode = :tlsMode,
				    username = :username, password_ciphertext = :ciphertext, password_nonce = :nonce,
				    from_email = :fromEmail, default_from_name = :fromName, reply_to = :replyTo,
				    per_minute_limit = :perMinute, per_hour_limit = :perHour,
				    per_day_limit = :perDay, per_domain_hour_limit = :perDomainHour,
				    enabled = :enabled, updated_at = now(), lock_version = lock_version + 1
				WHERE id = :id AND lock_version = :expectedLockVersion
				RETURNING *
				""").bind("name", value.name()).bind("host", value.host()).bind("port", value.port())
				.bind("tlsMode", value.tlsMode().name()).bind("fromEmail", value.fromEmail())
				.bind("fromName", value.defaultFromName()).bind("replyTo", value.replyTo())
				.bind("perMinute", value.perMinuteLimit()).bind("perHour", value.perHourLimit())
				.bind("perDay", value.perDayLimit()).bind("perDomainHour", value.perDomainHourLimit())
				.bind("enabled", value.enabled()).bind("id", id)
				.bind("expectedLockVersion", expectedLockVersion);
		statement = bindNullable(statement, "username", value.username(), String.class);
		statement = bindNullable(statement, "ciphertext", value.passwordCiphertext(), byte[].class);
		statement = bindNullable(statement, "nonce", value.passwordNonce(), byte[].class);
		return statement.map(this::map).one();
	}

	public Mono<Long> referencedCampaigns(UUID id) {
		return databaseClient.sql("SELECT count(*) AS total FROM campaigns WHERE smtp_account_id = :id")
				.bind("id", id).map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<Long> delete(UUID id, long expectedLockVersion) {
		return databaseClient.sql("DELETE FROM smtp_accounts WHERE id = :id AND lock_version = :expectedLockVersion")
				.bind("id", id).bind("expectedLockVersion", expectedLockVersion).fetch().rowsUpdated();
	}

	public Mono<Void> recordTest(UUID id, boolean succeeded, String errorCategory) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				UPDATE smtp_accounts
				SET last_tested_at = now(), last_test_status = :status, last_test_error = :error,
				    updated_at = now()
				WHERE id = :id
				""").bind("status", succeeded ? "SUCCEEDED" : "FAILED").bind("id", id);
		statement = bindNullable(statement, "error", errorCategory, String.class);
		return statement.fetch().rowsUpdated().then();
	}

	private String selectSql() {
		return """
				SELECT id, name, host, port, tls_mode, username, password_ciphertext, password_nonce,
				       from_email, default_from_name, reply_to, per_minute_limit, per_hour_limit,
				       per_day_limit, per_domain_hour_limit, enabled, last_tested_at,
				       last_test_status, last_test_error, lock_version, created_by, created_at, updated_at
				FROM smtp_accounts
				""";
	}

	private SmtpAccountRecord map(Row row, RowMetadata metadata) {
		return new SmtpAccountRecord(
				row.get("id", UUID.class), row.get("name", String.class), row.get("host", String.class),
				requiredInt(row, "port"), SmtpModels.TlsMode.valueOf(row.get("tls_mode", String.class)),
				row.get("username", String.class), row.get("password_ciphertext", byte[].class),
				row.get("password_nonce", byte[].class), row.get("from_email", String.class),
				row.get("default_from_name", String.class), row.get("reply_to", String.class),
				requiredInt(row, "per_minute_limit"), requiredInt(row, "per_hour_limit"),
				requiredInt(row, "per_day_limit"), requiredInt(row, "per_domain_hour_limit"),
				Boolean.TRUE.equals(row.get("enabled", Boolean.class)), row.get("last_tested_at", Instant.class),
				row.get("last_test_status", String.class), row.get("last_test_error", String.class),
				requiredLong(row, "lock_version"), row.get("created_by", UUID.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}

	private int requiredInt(Row row, String name) {
		Number number = row.get(name, Number.class);
		if (number == null) throw new IllegalStateException("Missing SMTP numeric field: " + name);
		return number.intValue();
	}

	private long requiredLong(Row row, String name) {
		Number number = row.get(name, Number.class);
		if (number == null) throw new IllegalStateException("Missing SMTP numeric field: " + name);
		return number.longValue();
	}

	private DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, Object value, Class<?> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	public record SmtpWrite(
			String name, String host, int port, SmtpModels.TlsMode tlsMode, String username,
			byte[] passwordCiphertext, byte[] passwordNonce, String fromEmail, String defaultFromName,
			String replyTo, int perMinuteLimit, int perHourLimit, int perDayLimit,
			int perDomainHourLimit, boolean enabled
	) { }

	public record SmtpAccountRecord(
			UUID id, String name, String host, int port, SmtpModels.TlsMode tlsMode, String username,
			byte[] passwordCiphertext, byte[] passwordNonce, String fromEmail, String defaultFromName,
			String replyTo, int perMinuteLimit, int perHourLimit, int perDayLimit,
			int perDomainHourLimit, boolean enabled, Instant lastTestedAt, String lastTestStatus,
			String lastTestError, long lockVersion, UUID createdBy, Instant createdAt, Instant updatedAt
	) { }
}
