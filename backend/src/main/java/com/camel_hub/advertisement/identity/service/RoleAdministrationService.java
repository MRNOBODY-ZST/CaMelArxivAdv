package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import io.r2dbc.spi.Row;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public class RoleAdministrationService {

	private final DatabaseClient databaseClient;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public RoleAdministrationService(
			DatabaseClient databaseClient,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		this.databaseClient = databaseClient;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<List<RoleView>> listRoles() {
		return databaseClient.sql("""
				SELECT r.id, r.code, r.name, r.description, r.system_role, r.created_at,
				       count(DISTINCT ur.user_id) AS user_count,
				       COALESCE(string_agg(DISTINCT p.code, ','), '') AS permissions
				FROM roles r
				LEFT JOIN user_roles ur ON ur.role_id = r.id
				LEFT JOIN role_permissions rp ON rp.role_id = r.id
				LEFT JOIN permissions p ON p.id = rp.permission_id
				GROUP BY r.id
				ORDER BY r.system_role DESC, r.code
				""")
				.map((row, metadata) -> mapRole(row))
				.all()
				.collectList();
	}

	public Mono<List<PermissionView>> listPermissions() {
		return databaseClient.sql("""
				SELECT id, code, description, created_at FROM permissions ORDER BY code
				""")
				.map((row, metadata) -> new PermissionView(
						row.get("id", UUID.class), row.get("code", String.class),
						row.get("description", String.class), toInstant(row.get("created_at", OffsetDateTime.class))))
				.all()
				.collectList();
	}

	public Mono<RoleView> create(
			RoleCommand command,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		RoleCommand normalized = normalize(command);
		Mono<RoleView> work = validatePermissionCodes(normalized.permissionCodes())
				.then(databaseClient.sql("""
						INSERT INTO roles (code, name, description, system_role)
						VALUES (:code, :name, :description, false)
						RETURNING id
						""")
						.bind("code", normalized.code())
						.bind("name", normalized.name())
						.bind("description", normalized.description())
						.map((row, metadata) -> row.get("id", UUID.class))
						.one())
				.flatMap(roleId -> insertPermissions(roleId, normalized.permissionCodes())
						.then(audit(actorId, "ROLE_CREATED", roleId, context, Map.of(),
								Map.of("code", normalized.code(), "permissions", normalized.permissionCodes())))
						.then(findRole(roleId)));
		return transactions.transactional(work)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new AdministrationConflictException("Role code is already in use"));
	}

	public Mono<RoleView> update(
			UUID roleId,
			RoleCommand command,
			UUID actorId,
			AuthenticationRequestContext context
	) {
		RoleCommand normalized = normalize(command);
		Mono<RoleView> work = lockRole(roleId)
				.switchIfEmpty(Mono.error(new AdministrationNotFoundException("Role")))
				.flatMap(current -> {
					if (current.systemRole() && !current.code().equals(normalized.code())) {
						return Mono.error(new AdministrationConflictException("System role codes cannot be renamed"));
					}
					return protectSuperAdminPermissionInvariant(current, normalized.permissionCodes())
							.then(permissionsForRole(roleId))
							.flatMap(currentPermissions -> validatePermissionCodes(normalized.permissionCodes())
									.then(databaseClient.sql("""
											UPDATE roles
											SET code = :code, name = :name, description = :description, updated_at = now()
											WHERE id = :roleId
											""")
											.bind("code", normalized.code())
											.bind("name", normalized.name())
											.bind("description", normalized.description())
											.bind("roleId", roleId)
											.fetch().rowsUpdated().then())
									.then(replacePermissions(roleId, normalized.permissionCodes()))
									.then(invalidateRoleUsers(roleId))
									.then(audit(actorId, "ROLE_UPDATED", roleId, context,
											Map.of("code", current.code(), "permissions", currentPermissions),
											Map.of("code", normalized.code(), "permissions", normalized.permissionCodes())))
									.then(findRole(roleId)));
				});
		return transactions.transactional(work)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new AdministrationConflictException("Role code is already in use"));
	}

	public Mono<Void> delete(UUID roleId, UUID actorId, AuthenticationRequestContext context) {
		Mono<Void> work = lockRole(roleId)
				.switchIfEmpty(Mono.error(new AdministrationNotFoundException("Role")))
				.flatMap(current -> {
					if (current.systemRole()) {
						return Mono.error(new AdministrationConflictException("System roles cannot be deleted"));
					}
					return rejectAssignedRoleDeletion(roleId)
							.then(databaseClient.sql("DELETE FROM roles WHERE id = :roleId")
									.bind("roleId", roleId).fetch().rowsUpdated().then())
							.then(audit(actorId, "ROLE_DELETED", roleId, context,
									Map.of("code", current.code()), Map.of("status", "DELETED")));
				});
		return transactions.transactional(work);
	}

	private Mono<Void> protectSuperAdminPermissionInvariant(LockedRole role, Set<String> requestedPermissions) {
		if (!role.code().equals("SUPER_ADMIN")) {
			return Mono.empty();
		}
		return databaseClient.sql("SELECT code FROM permissions ORDER BY code")
				.map((row, metadata) -> row.get("code", String.class))
				.all()
				.collectList()
				.flatMap(allPermissions -> requestedPermissions.equals(Set.copyOf(allPermissions))
						? Mono.empty()
						: Mono.error(new AdministrationConflictException(
								"SUPER_ADMIN permissions must always include the complete permission catalog")));
	}

	private Mono<Void> rejectAssignedRoleDeletion(UUID roleId) {
		return databaseClient.sql("SELECT count(*) AS total FROM user_roles WHERE role_id = :roleId")
				.bind("roleId", roleId)
				.map((row, metadata) -> row.get("total", Long.class))
				.one()
				.flatMap(total -> total != null && total == 0
						? Mono.empty()
						: Mono.error(new AdministrationConflictException(
								"An assigned role cannot be deleted")));
	}

	private Mono<Void> validatePermissionCodes(Set<String> permissionCodes) {
		if (permissionCodes.isEmpty()) {
			return Mono.error(new AdministrationValidationException("At least one permission is required"));
		}
		return databaseClient.sql("SELECT count(*) AS total FROM permissions WHERE code = ANY(:codes)")
				.bind("codes", permissionCodes.toArray(String[]::new))
				.map((row, metadata) -> row.get("total", Long.class))
				.one()
				.flatMap(total -> total == permissionCodes.size()
						? Mono.empty()
						: Mono.error(new AdministrationValidationException(
								"One or more permission codes are unknown")));
	}

	private Mono<Void> replacePermissions(UUID roleId, Set<String> permissionCodes) {
		return databaseClient.sql("DELETE FROM role_permissions WHERE role_id = :roleId")
				.bind("roleId", roleId).fetch().rowsUpdated().then()
				.then(insertPermissions(roleId, permissionCodes));
	}

	private Mono<Void> insertPermissions(UUID roleId, Set<String> permissionCodes) {
		return databaseClient.sql("""
				INSERT INTO role_permissions (role_id, permission_id)
				SELECT :roleId, id FROM permissions WHERE code = ANY(:codes)
				""")
				.bind("roleId", roleId)
				.bind("codes", permissionCodes.toArray(String[]::new))
				.fetch().rowsUpdated().then();
	}

	private Mono<Void> invalidateRoleUsers(UUID roleId) {
		return databaseClient.sql("""
				UPDATE users
				SET token_version = token_version + 1, updated_at = now()
				WHERE id IN (SELECT user_id FROM user_roles WHERE role_id = :roleId)
				""")
				.bind("roleId", roleId).fetch().rowsUpdated().then()
				.then(databaseClient.sql("""
						UPDATE refresh_tokens
						SET revoked_at = COALESCE(revoked_at, now())
						WHERE user_id IN (SELECT user_id FROM user_roles WHERE role_id = :roleId)
						""")
						.bind("roleId", roleId).fetch().rowsUpdated().then());
	}

	private Mono<LockedRole> lockRole(UUID roleId) {
		return databaseClient.sql("""
				SELECT id, code, name, description, system_role FROM roles WHERE id = :roleId FOR UPDATE
				""")
				.bind("roleId", roleId)
				.map((row, metadata) -> new LockedRole(
						row.get("id", UUID.class), row.get("code", String.class), row.get("name", String.class),
						row.get("description", String.class), Boolean.TRUE.equals(row.get("system_role", Boolean.class))))
				.one();
	}

	private Mono<Set<String>> permissionsForRole(UUID roleId) {
		return databaseClient.sql("""
				SELECT COALESCE(string_agg(p.code, ',' ORDER BY p.code), '') AS permissions
				FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
				WHERE rp.role_id = :roleId
				""")
				.bind("roleId", roleId)
				.map((row, metadata) -> codes(row.get("permissions", String.class)))
				.one();
	}

	private Mono<RoleView> findRole(UUID roleId) {
		return databaseClient.sql("""
				SELECT r.id, r.code, r.name, r.description, r.system_role, r.created_at,
				       count(DISTINCT ur.user_id) AS user_count,
				       COALESCE(string_agg(DISTINCT p.code, ','), '') AS permissions
				FROM roles r
				LEFT JOIN user_roles ur ON ur.role_id = r.id
				LEFT JOIN role_permissions rp ON rp.role_id = r.id
				LEFT JOIN permissions p ON p.id = rp.permission_id
				WHERE r.id = :roleId
				GROUP BY r.id
				""")
				.bind("roleId", roleId)
				.map((row, metadata) -> mapRole(row))
				.one();
	}

	private Mono<Void> audit(
			UUID actorId,
			String action,
			UUID roleId,
			AuthenticationRequestContext context,
			Map<String, ?> before,
			Map<String, ?> after
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "ROLE", roleId.toString(),
				hasher.hash(context.ipAddress() == null ? "unknown" : context.ipAddress().strip()),
				context.userAgentSummary(), context.traceId(), before, after, AuditResult.SUCCESS, null));
	}

	private RoleView mapRole(Row row) {
		Long userCount = row.get("user_count", Long.class);
		return new RoleView(
				row.get("id", UUID.class), row.get("code", String.class), row.get("name", String.class),
				row.get("description", String.class), Boolean.TRUE.equals(row.get("system_role", Boolean.class)),
				userCount == null ? 0 : userCount.intValue(), codes(row.get("permissions", String.class)),
				toInstant(row.get("created_at", OffsetDateTime.class)));
	}

	private static RoleCommand normalize(RoleCommand command) {
		TreeSet<String> permissions = new TreeSet<>();
		if (command.permissionCodes() != null) {
			command.permissionCodes().stream().map(String::strip).forEach(permissions::add);
		}
		return new RoleCommand(
				command.code().strip().toUpperCase(Locale.ROOT), command.name().strip(),
				command.description() == null ? "" : command.description().strip(), Set.copyOf(permissions));
	}

	private static Set<String> codes(String csv) {
		return csv == null || csv.isBlank() ? Set.of() : new TreeSet<>(Arrays.asList(csv.split(",")));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	public record RoleCommand(String code, String name, String description, Set<String> permissionCodes) {
	}

	public record RoleView(
			UUID id,
			String code,
			String name,
			String description,
			boolean systemRole,
			int userCount,
			Set<String> permissions,
			Instant createdAt
	) {
	}

	public record PermissionView(UUID id, String code, String description, Instant createdAt) {
	}

	private record LockedRole(UUID id, String code, String name, String description, boolean systemRole) {
	}
}
