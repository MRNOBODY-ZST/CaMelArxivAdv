package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_smtp_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final AuthenticationRequestContext CONTEXT =
			new AuthenticationRequestContext("127.0.0.1", "JUnit", "smtp-test");
	private static DatabaseClient databaseClient;
	private static ConnectionFactory connectionFactory;
	private SmtpRepository repository;
	private SmtpService service;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
	}

	@BeforeEach
	void setUp() {
		databaseClient.sql("TRUNCATE campaigns, smtp_accounts, audit_logs, users CASCADE")
				.fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'smtp-admin',
				        'smtp-admin@example.invalid', 'hash', 'SMTP Admin')
				""").fetch().rowsUpdated().block();
		AuditService audit = mock(AuditService.class);
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		String key = Base64.getEncoder().encodeToString(
				"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
		SmtpProperties properties = new SmtpProperties(false, Set.of("mailpit"),
				Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10), key);
		repository = new SmtpRepository(databaseClient);
		service = new SmtpService(repository, new SmtpSecretCrypto(key), new SmtpPolicy(properties),
				audit, hasher, TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)),
				mock(SmtpTransport.class));
	}

	@Test
	void encryptsPasswordsNeverReturnsThemAndPreservesOrRotatesOnUpdate() {
		var created = service.create(ACTOR, command("Local Mailpit", "first-secret"), CONTEXT).block();
		assertThat(created.passwordConfigured()).isTrue();
		assertThat(created.lockVersion()).isZero();
		var stored = repository.find(created.id()).block();
		assertThat(stored.passwordNonce()).hasSize(12);
		assertThat(new String(stored.passwordCiphertext(), StandardCharsets.ISO_8859_1))
				.doesNotContain("first-secret");

		var preserved = service.update(ACTOR, created.id(), 0, command("Local Mailpit", null), CONTEXT).block();
		var afterPreserve = repository.find(created.id()).block();
		assertThat(afterPreserve.passwordCiphertext()).containsExactly(stored.passwordCiphertext());
		assertThat(afterPreserve.passwordNonce()).containsExactly(stored.passwordNonce());

		var rotated = service.update(ACTOR, created.id(), preserved.lockVersion(),
				command("Local Mailpit", "second-secret"), CONTEXT).block();
		var afterRotate = repository.find(created.id()).block();
		assertThat(afterRotate.passwordCiphertext()).isNotEqualTo(stored.passwordCiphertext());
		assertThat(afterRotate.passwordNonce()).isNotEqualTo(stored.passwordNonce());
		assertThat(rotated.passwordConfigured()).isTrue();
	}

	@Test
	void blocksLiveDestinationsAndRejectsStaleDelete() {
		assertThatThrownBy(() -> service.create(ACTOR, new SmtpService.SmtpCommand(
				"External", "smtp.example.org", 587, "STARTTLS_REQUIRED", null, null,
				"sender@example.org", "Sender", "reply@example.org", 10, 100, 1_000, 50, false), CONTEXT))
				.isInstanceOf(SmtpValidationException.class);

		var created = service.create(ACTOR, command("Delete me", null), CONTEXT).block();
		assertThatThrownBy(() -> service.delete(ACTOR, created.id(), 99, CONTEXT).block())
				.isInstanceOf(SmtpConflictException.class);
		service.delete(ACTOR, created.id(), 0, CONTEXT).block();
		assertThatThrownBy(() -> service.get(created.id()).block()).isInstanceOf(SmtpNotFoundException.class);
	}

	private SmtpService.SmtpCommand command(String name, String password) {
		return new SmtpService.SmtpCommand(
				name, "mailpit", 1025, "PLAIN_LOCAL_ONLY", null, password,
				"sender@example.org", "Research Team", "reply@example.org",
				10, 100, 1_000, 50, true);
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
