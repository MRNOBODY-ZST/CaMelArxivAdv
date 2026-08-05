package com.camel_hub.advertisement.identity.persistence;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityRepositoryTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_identity_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	private IdentityRepository repository;
	private DatabaseClient databaseClient;

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
		databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
		repository = new IdentityRepository(databaseClient);
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
	}

	@Test
	void findsAnActiveUserByUsernameOrEmailWithoutCaseSensitivity() {
		UUID userId = insertUser("Research.Admin", "Research.Admin@example.edu", "ACTIVE");
		assignRole(userId, "DATA_ANALYST");

		var byUsername = repository.findByPrincipal("research.admin").block();
		var byEmail = repository.findByPrincipal("RESEARCH.ADMIN@EXAMPLE.EDU").block();

		assertThat(byUsername).isNotNull();
		assertThat(byEmail).isNotNull();
		assertThat(byUsername.id()).isEqualTo(userId);
		assertThat(byEmail.id()).isEqualTo(userId);
		assertThat(byUsername.roles()).containsExactly("DATA_ANALYST");
		assertThat(byUsername.permissions())
				.contains("paper:read", "paper:import", "contact:read_masked", "analytics:read")
				.doesNotContain("contact:read_full");
		assertThatThrownByMutation(byUsername.permissions());
	}

	@Test
	void preservesDisabledStatusAndCreatesInitialAdminOnlyOnce() {
		UUID disabledId = insertUser("disabled", "disabled@example.edu", "DISABLED");

		assertThat(repository.findById(disabledId).block().status().name()).isEqualTo("DISABLED");
		assertThat(repository.createInitialAdmin(
				"admin", "admin@example.invalid", "Administrator", "$2a$12$hash").block()).isTrue();
		assertThat(repository.createInitialAdmin(
				"admin", "admin@example.invalid", "Administrator", "$2a$12$another").block()).isFalse();
		assertThat(databaseClient.sql("SELECT count(*) AS total FROM users WHERE username = 'admin'")
				.map((row, metadata) -> row.get("total", Long.class))
				.one().block()).isEqualTo(1L);
		assertThat(repository.findByPrincipal("admin").block().roles()).containsExactly("SUPER_ADMIN");
	}

	@Test
	void passwordUpdateIsConditionalOnTheVerifiedCredentialState() {
		UUID userId = insertUser("password-user", "password-user@example.edu", "ACTIVE");
		var account = repository.findById(userId).block();
		assertThat(account).isNotNull();

		assertThat(repository.updatePasswordIfUnchanged(
				userId, account.passwordHash(), account.tokenVersion(), "$2a$12$new-hash").block()).isTrue();
		assertThat(repository.updatePasswordIfUnchanged(
				userId, account.passwordHash(), account.tokenVersion(), "$2a$12$stale-overwrite").block()).isFalse();
	}

	private UUID insertUser(String username, String email, String status) {
		return databaseClient.sql("""
				INSERT INTO users (username, email, password_hash, display_name, status)
				VALUES (:username, :email, '$2a$12$test', 'Test User', :status)
				RETURNING id
				""")
				.bind("username", username)
				.bind("email", email)
				.bind("status", status)
				.map((row, metadata) -> row.get("id", UUID.class))
				.one()
				.block();
	}

	private void assignRole(UUID userId, String roleCode) {
		databaseClient.sql("""
				INSERT INTO user_roles (user_id, role_id)
				SELECT :userId, id FROM roles WHERE code = :roleCode
				""")
				.bind("userId", userId)
				.bind("roleCode", roleCode)
				.fetch()
				.rowsUpdated()
				.block();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void assertThatThrownByMutation(java.util.Set<String> permissions) {
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ((java.util.Set) permissions).add("system:manage")))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
