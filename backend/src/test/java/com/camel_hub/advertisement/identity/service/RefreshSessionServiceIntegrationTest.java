package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.persistence.RefreshTokenRepository;
import com.camel_hub.advertisement.identity.security.RefreshTokenGenerator;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshSessionServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_refresh_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	private RefreshSessionService service;
	private DatabaseClient databaseClient;
	private UUID userId;
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
		userId = insertUser();
		AuthProperties properties = properties();
		service = new RefreshSessionService(
				new RefreshTokenRepository(databaseClient),
				new IdentityRepository(databaseClient),
				new RefreshTokenGenerator(),
				new SensitiveValueHasher(properties),
				properties,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
		context = new AuthenticationRequestContext("192.0.2.10", "integration-agent", "trace-refresh-1");
	}

	@Test
	void replayingARotatedTokenRevokesItsWholeFamily() {
		RefreshSessionService.IssuedRefreshSession issued = service.issue(userId, context).block();
		RefreshSessionService.RotatedRefreshSession rotated = service.rotate(issued.rawToken(), context).block();

		assertThat(rotated).isNotNull();
		assertThat(rotated.rawToken()).isNotEqualTo(issued.rawToken());
		assertThatThrownByReplay(issued.rawToken());
		assertThat(countFamilyRows(issued.familyId())).isEqualTo(2L);
		assertThat(countRevokedFamilyRows(issued.familyId())).isEqualTo(2L);
	}

	@Test
	void concurrentRefreshAllowsExactlyOneRotationAndTreatsTheOtherAsReplay() {
		RefreshSessionService.IssuedRefreshSession issued = service.issue(userId, context).block();

		List<Signal<RefreshSessionService.RotatedRefreshSession>> signals = Flux.merge(
				service.rotate(issued.rawToken(), context).materialize(),
				service.rotate(issued.rawToken(), context).materialize())
				.collectList()
				.block();

		assertThat(signals).isNotNull();
		assertThat(signals.stream().filter(Signal::isOnNext)).hasSize(1);
		assertThat(signals.stream().filter(Signal::isOnError)).hasSize(1);
		assertThat(signals.stream().filter(Signal::isOnError).findFirst().orElseThrow().getThrowable())
				.isInstanceOf(InvalidRefreshTokenException.class);
		assertThat(countRevokedFamilyRows(issued.familyId())).isEqualTo(2L);
	}

	private void assertThatThrownByReplay(String rawToken) {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(rawToken, context).block())
				.isInstanceOf(InvalidRefreshTokenException.class);
	}

	private UUID insertUser() {
		return databaseClient.sql("""
				INSERT INTO users (username, email, password_hash, display_name, status)
				VALUES ('refresh-user', 'refresh@example.edu', '$2a$12$test', 'Refresh User', 'ACTIVE')
				RETURNING id
				""")
				.map((row, metadata) -> row.get("id", UUID.class))
				.one()
				.block();
	}

	private long countFamilyRows(UUID familyId) {
		return databaseClient.sql("SELECT count(*) AS total FROM refresh_tokens WHERE family_id = :familyId")
				.bind("familyId", familyId)
				.map((row, metadata) -> row.get("total", Long.class))
				.one().block();
	}

	private long countRevokedFamilyRows(UUID familyId) {
		return databaseClient.sql("""
				SELECT count(*) AS total FROM refresh_tokens
				WHERE family_id = :familyId AND revoked_at IS NOT NULL
				""")
				.bind("familyId", familyId)
				.map((row, metadata) -> row.get("total", Long.class))
				.one().block();
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
