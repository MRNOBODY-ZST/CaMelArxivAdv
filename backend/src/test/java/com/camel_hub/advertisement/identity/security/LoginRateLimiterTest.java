package com.camel_hub.advertisement.identity.security;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.camel_hub.advertisement.identity.config.AuthProperties;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_login_limit_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	private LoginRateLimiter rateLimiter;

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
		DatabaseClient databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
		databaseClient.sql("DELETE FROM login_attempts").fetch().rowsUpdated().block();
		AuthProperties properties = properties();
		rateLimiter = new LoginRateLimiter(
				databaseClient,
				new SensitiveValueHasher(properties),
				properties);
	}

	@Test
	void blocksAtTheConfiguredFailureThreshold() {
		for (int attempt = 0; attempt < 5; attempt++) {
			rateLimiter.record("Admin", "192.0.2.10", false, "BAD_CREDENTIALS").block();
		}

		assertThat(rateLimiter.isBlocked("admin", "192.0.2.10").block()).isTrue();
		assertThat(rateLimiter.isBlocked("another-user", "192.0.2.11").block()).isFalse();
	}

	@Test
	void aSuccessStartsANewEffectiveFailureStreak() {
		for (int attempt = 0; attempt < 4; attempt++) {
			rateLimiter.record("admin", "192.0.2.10", false, "BAD_CREDENTIALS").block();
		}
		rateLimiter.record("admin", "192.0.2.10", true, null).block();
		for (int attempt = 0; attempt < 4; attempt++) {
			rateLimiter.record("admin", "192.0.2.10", false, "BAD_CREDENTIALS").block();
		}

		assertThat(rateLimiter.isBlocked("admin", "192.0.2.10").block()).isFalse();
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
