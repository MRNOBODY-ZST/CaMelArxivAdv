package com.camel_hub.advertisement.identity.persistence;

import com.camel_hub.advertisement.identity.domain.RefreshSession;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class RefreshTokenRepository {

	private final DatabaseClient databaseClient;

	public RefreshTokenRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Mono<UUID> create(
			UUID userId,
			UUID familyId,
			byte[] tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			byte[] createdIpHash,
			String userAgentSummary
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO refresh_tokens (
				    user_id, family_id, token_hash, issued_at, expires_at, created_ip_hash, user_agent_summary
				)
				VALUES (
				    :userId, :familyId, :tokenHash, :issuedAt, :expiresAt, :createdIpHash, :userAgentSummary
				)
				RETURNING id
				""")
				.bind("userId", userId)
				.bind("familyId", familyId)
				.bind("tokenHash", tokenHash)
				.bind("issuedAt", issuedAt)
				.bind("expiresAt", expiresAt);
		statement = createdIpHash == null
				? statement.bindNull("createdIpHash", byte[].class)
				: statement.bind("createdIpHash", createdIpHash);
		statement = userAgentSummary == null
				? statement.bindNull("userAgentSummary", String.class)
				: statement.bind("userAgentSummary", userAgentSummary);
		return statement.map((row, metadata) -> row.get("id", UUID.class)).one();
	}

	public Mono<RefreshSession> lockByTokenHash(byte[] tokenHash) {
		return databaseClient.sql("""
				SELECT id, user_id, family_id, token_hash, issued_at, expires_at, rotated_at, revoked_at,
				       replaced_by, created_ip_hash, user_agent_summary
				FROM refresh_tokens
				WHERE token_hash = :tokenHash
				FOR UPDATE
				""")
				.bind("tokenHash", tokenHash)
				.map((row, metadata) -> map(row))
				.one();
	}

	public Mono<Void> markRotated(UUID tokenId, UUID replacementId, Instant rotatedAt) {
		return databaseClient.sql("""
				UPDATE refresh_tokens
				SET rotated_at = :rotatedAt, replaced_by = :replacementId
				WHERE id = :tokenId AND rotated_at IS NULL AND revoked_at IS NULL
				""")
				.bind("rotatedAt", rotatedAt)
				.bind("replacementId", replacementId)
				.bind("tokenId", tokenId)
				.fetch()
				.rowsUpdated()
				.flatMap(updated -> updated == 1
						? Mono.empty()
						: Mono.error(new IllegalStateException("refresh token rotation lost its lock")));
	}

	public Mono<Void> revokeFamily(UUID familyId, Instant revokedAt) {
		return databaseClient.sql("""
				UPDATE refresh_tokens
				SET revoked_at = COALESCE(revoked_at, :revokedAt)
				WHERE family_id = :familyId
				""")
				.bind("revokedAt", revokedAt)
				.bind("familyId", familyId)
				.fetch()
				.rowsUpdated()
				.then();
	}

	public Mono<Void> revokeAllForUser(UUID userId, Instant revokedAt) {
		return databaseClient.sql("""
				UPDATE refresh_tokens
				SET revoked_at = COALESCE(revoked_at, :revokedAt)
				WHERE user_id = :userId
				""")
				.bind("revokedAt", revokedAt)
				.bind("userId", userId)
				.fetch()
				.rowsUpdated()
				.then();
	}

	private RefreshSession map(Row row) {
		return new RefreshSession(
				row.get("id", UUID.class),
				row.get("user_id", UUID.class),
				row.get("family_id", UUID.class),
				row.get("token_hash", byte[].class),
				row.get("issued_at", Instant.class),
				row.get("expires_at", Instant.class),
				row.get("rotated_at", Instant.class),
				row.get("revoked_at", Instant.class),
				row.get("replaced_by", UUID.class),
				row.get("created_ip_hash", byte[].class),
				row.get("user_agent_summary", String.class));
	}
}
