package com.camel_hub.advertisement.identity.security;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.Locale;

public final class LoginRateLimiter {

	private final DatabaseClient databaseClient;
	private final SensitiveValueHasher hasher;
	private final AuthProperties properties;

	public LoginRateLimiter(
			DatabaseClient databaseClient,
			SensitiveValueHasher hasher,
			AuthProperties properties
	) {
		this.databaseClient = databaseClient;
		this.hasher = hasher;
		this.properties = properties;
	}

	public Mono<Boolean> isBlocked(String principal, String ipAddress) {
		byte[] principalHash = principalHash(principal);
		byte[] ipHash = ipHash(ipAddress);
		return databaseClient.sql("""
				SELECT
				  (SELECT count(*)
				   FROM login_attempts attempts
				   WHERE attempts.principal_hash = :principalHash
				     AND attempts.succeeded = false
				     AND attempts.attempted_at >= now() - (:windowSeconds * interval '1 second')
				     AND attempts.attempted_at > COALESCE((
				       SELECT max(successes.attempted_at)
				       FROM login_attempts successes
				       WHERE successes.principal_hash = :principalHash AND successes.succeeded = true
				     ), '-infinity'::timestamptz)) AS principal_failures,
				  (SELECT count(*)
				   FROM login_attempts attempts
				   WHERE attempts.ip_hash = :ipHash
				     AND attempts.succeeded = false
				     AND attempts.attempted_at >= now() - (:windowSeconds * interval '1 second')
				     AND attempts.attempted_at > COALESCE((
				       SELECT max(successes.attempted_at)
				       FROM login_attempts successes
				       WHERE successes.ip_hash = :ipHash AND successes.succeeded = true
				     ), '-infinity'::timestamptz)) AS ip_failures
				""")
				.bind("principalHash", principalHash)
				.bind("ipHash", ipHash)
				.bind("windowSeconds", properties.loginFailureWindow().toSeconds())
				.map((row, metadata) -> {
					Long principalFailures = row.get("principal_failures", Long.class);
					Long ipFailures = row.get("ip_failures", Long.class);
					return (principalFailures != null && principalFailures >= properties.maxLoginFailures())
							|| (ipFailures != null && ipFailures >= properties.maxLoginFailures());
				})
				.one();
	}

	public Mono<Void> record(
			String principal,
			String ipAddress,
			boolean succeeded,
			String failureReason
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO login_attempts (
				    principal_hash, ip_hash, succeeded, failure_reason
				)
				VALUES (:principalHash, :ipHash, :succeeded, :failureReason)
				""")
				.bind("principalHash", principalHash(principal))
				.bind("ipHash", ipHash(ipAddress))
				.bind("succeeded", succeeded);
		statement = failureReason == null
				? statement.bindNull("failureReason", String.class)
				: statement.bind("failureReason", failureReason);
		return statement.fetch().rowsUpdated().then();
	}

	public byte[] principalHash(String principal) {
		return hasher.hash(principal.strip().toLowerCase(Locale.ROOT));
	}

	public byte[] ipHash(String ipAddress) {
		return hasher.hash(ipAddress == null ? "unknown" : ipAddress.strip());
	}
}
