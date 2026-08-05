package com.camel_hub.advertisement.identity.persistence;

import com.camel_hub.advertisement.identity.domain.UserAccount;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class IdentityRepository {

	private static final String USER_WITH_AUTHORIZATION = """
			SELECT u.id,
			       u.username,
			       u.email,
			       u.password_hash,
			       u.display_name,
			       u.status,
			       u.force_password_change,
			       u.token_version,
			       u.last_login_at,
			       u.password_changed_at,
			       COALESCE(string_agg(DISTINCT r.code, ','), '') AS roles,
			       COALESCE(string_agg(DISTINCT p.code, ','), '') AS permissions
			FROM users u
			LEFT JOIN user_roles ur ON ur.user_id = u.id
			LEFT JOIN roles r ON r.id = ur.role_id
			LEFT JOIN role_permissions rp ON rp.role_id = r.id
			LEFT JOIN permissions p ON p.id = rp.permission_id
			""";

	private final DatabaseClient databaseClient;

	public IdentityRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Mono<UserAccount> findByPrincipal(String principal) {
		return databaseClient.sql(USER_WITH_AUTHORIZATION + """
				WHERE lower(u.username) = lower(:principal) OR lower(u.email) = lower(:principal)
				GROUP BY u.id
				""")
				.bind("principal", principal.strip())
				.map((row, metadata) -> mapUser(row))
				.one();
	}

	public Mono<UserAccount> findById(UUID userId) {
		return databaseClient.sql(USER_WITH_AUTHORIZATION + """
				WHERE u.id = :userId
				GROUP BY u.id
				""")
				.bind("userId", userId)
				.map((row, metadata) -> mapUser(row))
				.one();
	}

	public Mono<Boolean> createInitialAdmin(
			String username,
			String email,
			String displayName,
			String passwordHash
	) {
		return databaseClient.sql("""
				WITH inserted_user AS (
				    INSERT INTO users (
				        username, email, password_hash, display_name, status, force_password_change
				    )
				    VALUES (:username, :email, :passwordHash, :displayName, 'ACTIVE', true)
				    ON CONFLICT DO NOTHING
				    RETURNING id
				), super_admin AS (
				    SELECT id FROM roles WHERE code = 'SUPER_ADMIN'
				)
				INSERT INTO user_roles (user_id, role_id, assigned_by)
				SELECT inserted_user.id, super_admin.id, inserted_user.id
				FROM inserted_user CROSS JOIN super_admin
				RETURNING user_id
				""")
				.bind("username", username.strip())
				.bind("email", email.strip())
				.bind("passwordHash", passwordHash)
				.bind("displayName", displayName.strip())
				.map((row, metadata) -> true)
				.one()
				.defaultIfEmpty(false);
	}

	public Mono<Void> updateLastLogin(UUID userId) {
		return databaseClient.sql("""
				UPDATE users
				SET last_login_at = now(), updated_at = now()
				WHERE id = :userId
				""")
				.bind("userId", userId)
				.fetch()
				.rowsUpdated()
				.then();
	}

	private UserAccount mapUser(Row row) {
		return new UserAccount(
				row.get("id", UUID.class),
				row.get("username", String.class),
				row.get("email", String.class),
				row.get("password_hash", String.class),
				row.get("display_name", String.class),
				UserStatus.valueOf(row.get("status", String.class)),
				Boolean.TRUE.equals(row.get("force_password_change", Boolean.class)),
				row.get("token_version", Integer.class),
				toInstant(row.get("last_login_at", OffsetDateTime.class)),
				toInstant(row.get("password_changed_at", OffsetDateTime.class)),
				codes(row.get("roles", String.class)),
				codes(row.get("permissions", String.class)));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static Set<String> codes(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		return new TreeSet<>(Arrays.asList(csv.split(",")));
	}
}
