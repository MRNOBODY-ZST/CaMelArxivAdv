package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.audit.AuditQueryService;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.persistence.RefreshTokenRepository;
import com.camel_hub.advertisement.identity.security.PasswordPolicy;
import com.camel_hub.advertisement.identity.security.RefreshTokenGenerator;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdministrationServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_admin_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	private DatabaseClient databaseClient;
	private UserAdministrationService users;
	private RoleAdministrationService roles;
	private AuditQueryService auditQuery;
	private RefreshSessionService refreshSessions;
	private BCryptPasswordEncoder passwordEncoder;
	private AuthenticationRequestContext context;

	@BeforeEach
	void setUp() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
			Flyway.configure()
					.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration")
					.load()
					.migrate();
		}
		ConnectionFactory connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM roles WHERE system_role = false").fetch().rowsUpdated().block();
		AuthProperties properties = properties();
		SensitiveValueHasher hasher = new SensitiveValueHasher(properties);
		TransactionalOperator transactions = TransactionalOperator.create(
				new R2dbcTransactionManager(connectionFactory));
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		AuditService auditService = new AuditService(databaseClient, objectMapper);
		refreshSessions = new RefreshSessionService(
				new RefreshTokenRepository(databaseClient), new IdentityRepository(databaseClient),
				new RefreshTokenGenerator(), hasher, properties, transactions);
		passwordEncoder = new BCryptPasswordEncoder(4);
		users = new UserAdministrationService(
				databaseClient, passwordEncoder, new PasswordPolicy(), refreshSessions,
				auditService, hasher, transactions);
		roles = new RoleAdministrationService(databaseClient, auditService, hasher, transactions);
		auditQuery = new AuditQueryService(databaseClient, objectMapper);
		context = new AuthenticationRequestContext("192.0.2.20", "admin-integration", "trace-admin-1");
	}

	@Test
	void protectsLastSuperAdminAndResetInvalidatesEveryCredential() {
		UUID firstAdmin = insertUser("admin-one", "admin-one@example.edu");
		assignRole(firstAdmin, "SUPER_ADMIN", firstAdmin);

		assertThatThrownBy(() -> users.setEnabled(firstAdmin, false, firstAdmin, context).block())
				.isInstanceOf(AdministrationConflictException.class)
				.hasMessageContaining("last active SUPER_ADMIN");

		UUID secondAdmin = insertUser("admin-two", "admin-two@example.edu");
		assignRole(secondAdmin, "SUPER_ADMIN", firstAdmin);
		assertThat(users.setEnabled(secondAdmin, false, firstAdmin, context).block().status().name())
				.isEqualTo("DISABLED");

		UserAdministrationService.UserView analyst = users.create(
				new UserAdministrationService.CreateUserCommand(
						"analyst", "analyst@example.edu", "Data Analyst", "Maple!Orbit92",
						Set.of("DATA_ANALYST")),
				firstAdmin, context).block();
		assertThat(analyst).isNotNull();
		assertThat(analyst.forcePasswordChange()).isTrue();
		RefreshSessionService.IssuedRefreshSession session = refreshSessions.issue(analyst.id(), context).block();

		users.resetPassword(analyst.id(), "Cedar!Galaxy94", firstAdmin, context).block();

		String hash = scalarString("SELECT password_hash FROM users WHERE id = '" + analyst.id() + "'");
		assertThat(hash).doesNotContain("Cedar!Galaxy94");
		assertThat(passwordEncoder.matches("Cedar!Galaxy94", hash)).isTrue();
		assertThat(scalarBoolean("SELECT force_password_change FROM users WHERE id = '" + analyst.id() + "'"))
				.isTrue();
		assertThat(scalarLong("SELECT token_version::bigint FROM users WHERE id = '" + analyst.id() + "'"))
				.isEqualTo(1L);
		assertThat(scalarLong("""
				SELECT count(*) FROM refresh_tokens WHERE family_id = '%s' AND revoked_at IS NOT NULL
				""".formatted(session.familyId()).strip())).isEqualTo(1L);
		assertThat(scalarLong("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'USER_PASSWORD_RESET'
				  AND (before_summary::text ILIKE '%%Cedar!Galaxy94%%'
				       OR after_summary::text ILIKE '%%Cedar!Galaxy94%%'
				       OR before_summary::text ILIKE '%%password_hash%%'
				       OR after_summary::text ILIKE '%%password_hash%%'
				       OR before_summary::text ILIKE '%%refresh_token%%'
				       OR after_summary::text ILIKE '%%refresh_token%%')
				""".strip())).isZero();
	}

	@Test
	void roleChangesValidatePermissionsAndInvalidateAssignedUsers() {
		UUID actorId = insertUser("role-admin", "role-admin@example.edu");
		assignRole(actorId, "SUPER_ADMIN", actorId);
		RoleAdministrationService.RoleView custom = roles.create(
				new RoleAdministrationService.RoleCommand(
						"RESEARCH_ADMIN", "Research Admin", "Research operations", Set.of("paper:read")),
				actorId, context).block();
		assertThat(custom).isNotNull();

		UUID assignedUser = insertUser("researcher", "researcher@example.edu");
		assignRole(assignedUser, custom.code(), actorId);
		RefreshSessionService.IssuedRefreshSession session = refreshSessions.issue(assignedUser, context).block();
		roles.update(custom.id(), new RoleAdministrationService.RoleCommand(
				"RESEARCH_ADMIN", "Research Administrator", "Updated",
				Set.of("paper:read", "paper:import")), actorId, context).block();

		assertThat(scalarLong("SELECT token_version::bigint FROM users WHERE id = '" + assignedUser + "'"))
				.isEqualTo(1L);
		assertThat(scalarLong("""
				SELECT count(*) FROM refresh_tokens WHERE family_id = '%s' AND revoked_at IS NOT NULL
				""".formatted(session.familyId()).strip())).isEqualTo(1L);
		assertThatThrownBy(() -> roles.create(new RoleAdministrationService.RoleCommand(
				"BAD_ROLE", "Bad Role", "Unknown permission", Set.of("unknown:permission")), actorId, context).block())
				.isInstanceOf(AdministrationValidationException.class);

		UUID systemRoleId = scalarUuid("SELECT id FROM roles WHERE code = 'SUPER_ADMIN'");
		assertThatThrownBy(() -> roles.update(systemRoleId, new RoleAdministrationService.RoleCommand(
				"RENAMED", "Renamed", "", Set.of("system:manage")), actorId, context).block())
				.isInstanceOf(AdministrationConflictException.class);
		assertThatThrownBy(() -> roles.delete(systemRoleId, actorId, context).block())
				.isInstanceOf(AdministrationConflictException.class);

		var auditPage = auditQuery.query(1, 20, null, null, actorId, "ROLE_UPDATED", "ROLE", "SUCCESS").block();
		assertThat(auditPage).isNotNull();
		assertThat(auditPage.items()).hasSize(1);
		assertThat(auditPage.items().getFirst().traceId()).isEqualTo("trace-admin-1");
	}

	private UUID insertUser(String username, String email) {
		return databaseClient.sql("""
				INSERT INTO users (username, email, password_hash, display_name, status, force_password_change)
				VALUES (:username, :email, '$2a$04$test', :username, 'ACTIVE', false)
				RETURNING id
				""")
				.bind("username", username).bind("email", email)
				.map((row, metadata) -> row.get("id", UUID.class)).one().block();
	}

	private void assignRole(UUID userId, String roleCode, UUID actorId) {
		databaseClient.sql("""
				INSERT INTO user_roles (user_id, role_id, assigned_by)
				SELECT :userId, id, :actorId FROM roles WHERE code = :roleCode
				""")
				.bind("userId", userId).bind("actorId", actorId).bind("roleCode", roleCode)
				.fetch().rowsUpdated().block();
	}

	private long scalarLong(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	private boolean scalarBoolean(String sql) {
		return Boolean.TRUE.equals(databaseClient.sql(sql)
				.map((row, metadata) -> row.get(0, Boolean.class)).one().block());
	}

	private String scalarString(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private UUID scalarUuid(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, UUID.class)).one().block();
	}

	private AuthProperties properties() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		return new AuthProperties(
				Duration.ofMinutes(10), Duration.ofDays(14), 5, Duration.ofMinutes(15),
				"camel-arxiv", key, key,
				new AuthProperties.RefreshCookie(true, "Strict", "/api/v1/auth"),
				new AuthProperties.BootstrapAdmin("", "", "", ""));
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
