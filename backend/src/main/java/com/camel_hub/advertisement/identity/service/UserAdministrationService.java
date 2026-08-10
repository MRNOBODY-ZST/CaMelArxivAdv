package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import com.camel_hub.advertisement.identity.security.PasswordPolicy;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import io.r2dbc.spi.Row;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public class UserAdministrationService {

	private static final int MAXIMUM_PAGE_SIZE = 100;
	private final DatabaseClient databaseClient;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final RefreshSessionService refreshSessions;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public UserAdministrationService(
			DatabaseClient databaseClient,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			RefreshSessionService refreshSessions,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		this.databaseClient = databaseClient;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.refreshSessions = refreshSessions;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<PageResponse<UserView>> list(int page, int pageSize, String search, String status) {
		validatePage(page, pageSize);
		String normalizedSearch = search == null ? "" : search.strip();
		String normalizedStatus = normalizeStatus(status);
		int offset = (page - 1) * pageSize;
		var items = databaseClient.sql("""
				SELECT u.id, u.username, u.email, u.display_name, u.status, u.force_password_change,
				       u.token_version, u.last_login_at, u.created_at,
				       COALESCE(string_agg(DISTINCT r.code, ','), '') AS roles
				FROM users u
				LEFT JOIN user_roles ur ON ur.user_id = u.id
				LEFT JOIN roles r ON r.id = ur.role_id
				WHERE (:search = '' OR u.username ILIKE '%' || :search || '%'
				       OR u.email ILIKE '%' || :search || '%' OR u.display_name ILIKE '%' || :search || '%')
				  AND (:status = '' OR u.status = :status)
				GROUP BY u.id
				ORDER BY u.created_at DESC, u.id
				LIMIT :pageSize OFFSET :offset
				""")
				.bind("search", normalizedSearch)
				.bind("status", normalizedStatus)
				.bind("pageSize", pageSize)
				.bind("offset", offset)
				.map((row, metadata) -> mapUser(row))
				.all()
				.collectList();
		var total = databaseClient.sql("""
				SELECT count(*) AS total
				FROM users u
				WHERE (:search = '' OR u.username ILIKE '%' || :search || '%'
				       OR u.email ILIKE '%' || :search || '%' OR u.display_name ILIKE '%' || :search || '%')
				  AND (:status = '' OR u.status = :status)
				""")
				.bind("search", normalizedSearch)
				.bind("status", normalizedStatus)
				.map((row, metadata) -> row.get("total", Long.class))
				.one();
		return Mono.zip(items, total)
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<UserView> create(
			CreateUserCommand command,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		Set<String> roles = normalizedCodes(command.roleCodes());
		Mono<String> encoded = encodeValidatedPassword(
				command.initialPassword(), command.username(), command.email());
		return encoded.flatMap(passwordHash -> transactions.transactional(
				validateRoleCodes(roles)
						.then(requireSuperAdminActorForProtectedRoles(actorId, Set.of(), roles))
						.then(databaseClient.sql("""
								INSERT INTO users (
								    username, email, password_hash, display_name, status, force_password_change
								)
								VALUES (:username, :email, :passwordHash, :displayName, 'ACTIVE', true)
								RETURNING id
								""")
								.bind("username", command.username().strip())
								.bind("email", command.email().strip())
								.bind("passwordHash", passwordHash)
								.bind("displayName", command.displayName().strip())
								.map((row, metadata) -> row.get("id", UUID.class))
								.one())
						.flatMap(userId -> insertRoles(userId, roles, actorId)
								.then(audit(actorId, "USER_CREATED", userId, context,
										Map.of(), Map.of("status", "ACTIVE", "roles", roles)))
								.then(findView(userId)))))
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new AdministrationConflictException("Username or email is already in use"))
				.onErrorResume(AccessDeniedException.class,
						exception -> authorizationDenied(actorId, "new-user", context, exception));
	}

	public Mono<UserView> update(
			UUID userId,
			UpdateUserCommand command,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		Set<String> newRoles = normalizedCodes(command.roleCodes());
		Mono<UserView> work = lockUser(userId)
				.switchIfEmpty(Mono.error(new AdministrationNotFoundException("User")))
				.flatMap(locked -> rolesForUser(userId)
						.flatMap(currentRoles -> validateRoleCodes(newRoles)
								.then(requireSuperAdminActorForProtectedRoles(actorId, currentRoles, newRoles))
								.then(protectLastSuperAdmin(locked, currentRoles, newRoles, false))
								.then(databaseClient.sql("""
										UPDATE users
										SET email = :email, display_name = :displayName,
										    token_version = token_version + 1, updated_at = now()
										WHERE id = :userId
										""")
										.bind("email", command.email().strip())
										.bind("displayName", command.displayName().strip())
										.bind("userId", userId)
										.fetch().rowsUpdated().then())
								.then(replaceRoles(userId, newRoles, actorId))
								.then(refreshSessions.revokeAll(userId))
								.then(audit(actorId, "USER_UPDATED", userId, context,
										Map.of("displayName", locked.displayName(), "roles", currentRoles),
										Map.of("displayName", command.displayName().strip(), "roles", newRoles)))
								.then(findView(userId))));
		return transactions.transactional(work)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new AdministrationConflictException("Email is already in use"))
				.onErrorResume(AccessDeniedException.class,
						exception -> authorizationDenied(actorId, userId.toString(), context, exception));
	}

	public Mono<UserView> setEnabled(
			UUID userId,
			boolean enabled,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		String targetStatus = enabled ? UserStatus.ACTIVE.name() : UserStatus.DISABLED.name();
		Mono<UserView> work = lockUser(userId)
				.switchIfEmpty(Mono.error(new AdministrationNotFoundException("User")))
				.flatMap(locked -> rolesForUser(userId)
						.flatMap(currentRoles -> {
							Mono<UserView> mutation = protectLastSuperAdmin(
									locked, currentRoles, currentRoles, !enabled)
									.then(databaseClient.sql("""
											UPDATE users
											SET status = :status, token_version = token_version + 1, updated_at = now()
											WHERE id = :userId
											""")
											.bind("status", targetStatus)
											.bind("userId", userId)
											.fetch().rowsUpdated().then())
									.then(refreshSessions.revokeAll(userId))
									.then(audit(actorId, enabled ? "USER_ENABLED" : "USER_DISABLED", userId, context,
											Map.of("status", locked.status().name()), Map.of("status", targetStatus)))
									.then(findView(userId));
							return requireSuperAdminActorForProtectedRoles(actorId, currentRoles, currentRoles)
									.then(mutation);
						}));
		return transactions.transactional(work)
				.onErrorResume(AccessDeniedException.class,
						exception -> authorizationDenied(actorId, userId.toString(), context, exception));
	}

	public Mono<Void> resetPassword(
			UUID userId,
			String newPassword,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		return lockUser(userId)
				.switchIfEmpty(Mono.error(new AdministrationNotFoundException("User")))
				.flatMap(locked -> encodeValidatedPassword(newPassword, locked.username(), locked.email())
						.flatMap(encoded -> transactions.transactional(
								lockUser(userId)
										.switchIfEmpty(Mono.error(new AdministrationNotFoundException("User")))
										.flatMap(current -> rolesForUser(userId)
												.flatMap(currentRoles -> {
													Mono<Void> mutation = databaseClient.sql("""
															UPDATE users
															SET password_hash = :passwordHash, force_password_change = true,
															    token_version = token_version + 1,
															    password_changed_at = now(), updated_at = now()
															WHERE id = :userId
															""")
															.bind("passwordHash", encoded)
															.bind("userId", userId)
															.fetch().rowsUpdated().then()
															.then(refreshSessions.revokeAll(userId))
															.then(audit(actorId, "USER_PASSWORD_RESET", userId, context,
																	Map.of("forcePasswordChange", current.forcePasswordChange()),
																	Map.of("forcePasswordChange", true, "sessions", "REVOKED")));
													return requireSuperAdminActorForProtectedRoles(
															actorId, currentRoles, currentRoles).then(mutation);
												})))))
				.onErrorResume(AccessDeniedException.class,
						exception -> authorizationDenied(actorId, userId.toString(), context, exception));
	}

	private Mono<Void> validateRoleCodes(Set<String> roleCodes) {
		if (roleCodes.isEmpty()) {
			return Mono.error(new AdministrationValidationException("At least one role is required"));
		}
		return databaseClient.sql("SELECT count(*) AS total FROM roles WHERE code = ANY(:codes)")
				.bind("codes", roleCodes.toArray(String[]::new))
				.map((row, metadata) -> row.get("total", Long.class))
				.one()
				.flatMap(total -> total == roleCodes.size()
						? Mono.empty()
						: Mono.error(new AdministrationValidationException("One or more role codes are unknown")));
	}

	private Mono<Void> requireSuperAdminActorForProtectedRoles(
			UUID actorId,
			Set<String> currentRoles,
			Set<String> newRoles
	) {
		if (!currentRoles.contains("SUPER_ADMIN") && !newRoles.contains("SUPER_ADMIN")) {
			return Mono.empty();
		}
		return databaseClient.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM users u
				  JOIN user_roles ur ON ur.user_id = u.id
				  JOIN roles r ON r.id = ur.role_id
				  WHERE u.id = :actorId AND u.status = 'ACTIVE' AND r.code = 'SUPER_ADMIN'
				) AS allowed
				""")
				.bind("actorId", actorId)
				.map((row, metadata) -> Boolean.TRUE.equals(row.get("allowed", Boolean.class)))
				.one()
				.flatMap(allowed -> allowed
						? Mono.empty()
						: Mono.error(new AccessDeniedException(
								"Only an active SUPER_ADMIN may manage SUPER_ADMIN accounts or membership")));
	}

	private <T> Mono<T> authorizationDenied(
			UUID actorId,
			String resourceId,
			AuthenticationRequestContext context,
			AccessDeniedException exception
	) {
		Mono<Void> record = auditService.record(new AuditEvent(
				actorId, "SUPER_ADMIN_MANAGEMENT_DENIED", "USER", resourceId, ipHash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(), Map.of("status", "DENIED"),
				AuditResult.DENIED, "SUPER_ADMIN_PROTECTED"))
				.onErrorResume(auditFailure -> Mono.empty());
		return record.then(Mono.error(exception));
	}

	private Mono<Void> replaceRoles(UUID userId, Set<String> roleCodes, UUID actorId) {
		return databaseClient.sql("DELETE FROM user_roles WHERE user_id = :userId")
				.bind("userId", userId).fetch().rowsUpdated().then()
				.then(insertRoles(userId, roleCodes, actorId));
	}

	private Mono<Void> insertRoles(UUID userId, Set<String> roleCodes, UUID actorId) {
		return databaseClient.sql("""
				INSERT INTO user_roles (user_id, role_id, assigned_by)
				SELECT :userId, id, :actorId FROM roles WHERE code = ANY(:codes)
				""")
				.bind("userId", userId)
				.bind("actorId", actorId)
				.bind("codes", roleCodes.toArray(String[]::new))
				.fetch().rowsUpdated().then();
	}

	private Mono<Void> protectLastSuperAdmin(
			LockedUser user,
			Set<String> currentRoles,
			Set<String> newRoles,
			boolean disabling
	) {
		boolean removesActiveSuperAdmin = user.status() == UserStatus.ACTIVE
				&& currentRoles.contains("SUPER_ADMIN")
				&& (disabling || !newRoles.contains("SUPER_ADMIN"));
		if (!removesActiveSuperAdmin) {
			return Mono.empty();
		}
		Mono<Void> serializationLock = databaseClient.sql(
				"SELECT pg_advisory_xact_lock(7205759403792793)")
				.map((row, metadata) -> true)
				.one()
				.then();
		return serializationLock.then(databaseClient.sql("""
				SELECT count(DISTINCT u.id) AS total
				FROM users u
				JOIN user_roles ur ON ur.user_id = u.id
				JOIN roles r ON r.id = ur.role_id
				WHERE u.status = 'ACTIVE' AND r.code = 'SUPER_ADMIN'
				""")
				.map((row, metadata) -> row.get("total", Long.class))
				.one()
				.flatMap(total -> total != null && total > 1
						? Mono.empty()
						: Mono.error(new AdministrationConflictException(
								"The last active SUPER_ADMIN cannot be disabled or stripped of the role"))));
	}

	private Mono<LockedUser> lockUser(UUID userId) {
		return databaseClient.sql("""
				SELECT id, username, email, display_name, status, force_password_change
				FROM users WHERE id = :userId FOR UPDATE
				""")
				.bind("userId", userId)
				.map((row, metadata) -> new LockedUser(
						row.get("id", UUID.class), row.get("username", String.class),
						row.get("email", String.class), row.get("display_name", String.class),
						UserStatus.valueOf(row.get("status", String.class)),
						Boolean.TRUE.equals(row.get("force_password_change", Boolean.class))))
				.one();
	}

	private Mono<Set<String>> rolesForUser(UUID userId) {
		return databaseClient.sql("""
				SELECT COALESCE(string_agg(r.code, ',' ORDER BY r.code), '') AS roles
				FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = :userId
				""")
				.bind("userId", userId)
				.map((row, metadata) -> codes(row.get("roles", String.class)))
				.one();
	}

	private Mono<UserView> findView(UUID userId) {
		return databaseClient.sql("""
				SELECT u.id, u.username, u.email, u.display_name, u.status, u.force_password_change,
				       u.token_version, u.last_login_at, u.created_at,
				       COALESCE(string_agg(DISTINCT r.code, ','), '') AS roles
				FROM users u
				LEFT JOIN user_roles ur ON ur.user_id = u.id
				LEFT JOIN roles r ON r.id = ur.role_id
				WHERE u.id = :userId
				GROUP BY u.id
				""")
				.bind("userId", userId)
				.map((row, metadata) -> mapUser(row))
				.one();
	}

	private Mono<String> encodeValidatedPassword(String password, String username, String email) {
		return Mono.fromCallable(() -> {
			try {
				passwordPolicy.validate(password, username, email);
			}
			catch (IllegalArgumentException exception) {
				throw new PasswordPolicyViolationException(exception.getMessage());
			}
			return passwordEncoder.encode(password);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private Mono<Void> audit(
			UUID actorId,
			String action,
			UUID userId,
			AuthenticationRequestContext context,
			Map<String, ?> before,
			Map<String, ?> after
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "USER", userId.toString(), ipHash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), before, after, AuditResult.SUCCESS, null));
	}

	private byte[] ipHash(String ipAddress) {
		return hasher.hash(ipAddress == null ? "unknown" : ipAddress.strip());
	}

	private UserView mapUser(Row row) {
		return new UserView(
				row.get("id", UUID.class), row.get("username", String.class), row.get("email", String.class),
				row.get("display_name", String.class), UserStatus.valueOf(row.get("status", String.class)),
				Boolean.TRUE.equals(row.get("force_password_change", Boolean.class)),
				row.get("token_version", Integer.class), toInstant(row.get("last_login_at", OffsetDateTime.class)),
				toInstant(row.get("created_at", OffsetDateTime.class)), codes(row.get("roles", String.class)));
	}

	private static Set<String> codes(String csv) {
		return csv == null || csv.isBlank() ? Set.of() : new TreeSet<>(Arrays.asList(csv.split(",")));
	}

	private static Set<String> normalizedCodes(Set<String> codes) {
		TreeSet<String> normalized = new TreeSet<>();
		if (codes != null) {
			codes.stream().map(String::strip).map(value -> value.toUpperCase(Locale.ROOT)).forEach(normalized::add);
		}
		return Set.copyOf(normalized);
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static String normalizeStatus(String status) {
		if (status == null || status.isBlank()) {
			return "";
		}
		String normalized = status.strip().toUpperCase(Locale.ROOT);
		try {
			UserStatus.valueOf(normalized);
			return normalized;
		}
		catch (IllegalArgumentException exception) {
			throw new AdministrationValidationException("Unknown user status");
		}
	}

	private static void validatePage(int page, int pageSize) {
		if (page < 1 || pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE) {
			throw new AdministrationValidationException("Page must be positive and pageSize must be between 1 and 100");
		}
	}

	public record CreateUserCommand(
			String username, String email, String displayName, String initialPassword, Set<String> roleCodes
	) {
	}

	public record UpdateUserCommand(String email, String displayName, Set<String> roleCodes) {
	}

	public record UserView(
			UUID id,
			String username,
			String email,
			String displayName,
			UserStatus status,
			boolean forcePasswordChange,
			int tokenVersion,
			Instant lastLoginAt,
			Instant createdAt,
			Set<String> roles
	) {
	}

	private record LockedUser(
			UUID id,
			String username,
			String email,
			String displayName,
			UserStatus status,
			boolean forcePasswordChange
	) {
	}
}
