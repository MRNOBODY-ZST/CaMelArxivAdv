package com.camel_hub.advertisement.email.mailbox;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
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

class MailboxServiceIntegrationTest {
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_mailbox_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final AuthenticationRequestContext CONTEXT =
			new AuthenticationRequestContext("127.0.0.1", "JUnit", "mailbox-test");
	private static DatabaseClient databaseClient;
	private static ConnectionFactory connectionFactory;
	private MailboxRepository repository;
	private MailboxService service;

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
		databaseClient.sql("TRUNCATE mailbox_accounts, audit_logs, users CASCADE")
				.fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'mailbox-admin',
				        'mailbox-admin@example.invalid', 'hash', 'Mailbox Admin')
				""").fetch().rowsUpdated().block();
		AuditService audit = mock(AuditService.class);
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		String key = Base64.getEncoder().encodeToString(
				"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
		MailboxProperties properties = new MailboxProperties(
				true, Set.of("mail-test"), Duration.ofSeconds(5), Duration.ofSeconds(10), 50);
		repository = new MailboxRepository(databaseClient);
		service = new MailboxService(
				repository, new SmtpSecretCrypto(key), new MailboxPolicy(properties), mock(MailboxTransport.class),
				properties, audit, hasher,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void encryptsPasswordsAndPreservesOrRotatesThemOnUpdate() {
		var created = service.create(ACTOR, command("Inbox", "first-secret", true), CONTEXT).block();
		assertThat(created.passwordConfigured()).isTrue();
		assertThat(created.protocol()).isEqualTo(MailboxModels.Protocol.IMAP);
		var stored = repository.find(created.id()).block();
		assertThat(stored.passwordNonce()).hasSize(12);
		assertThat(new String(stored.passwordCiphertext(), StandardCharsets.ISO_8859_1))
				.doesNotContain("first-secret");

		var preserved = service.update(ACTOR, created.id(), 0,
				command("Inbox", null, true), CONTEXT).block();
		var afterPreserve = repository.find(created.id()).block();
		assertThat(afterPreserve.passwordCiphertext()).containsExactly(stored.passwordCiphertext());
		assertThat(afterPreserve.passwordNonce()).containsExactly(stored.passwordNonce());

		service.update(ACTOR, created.id(), preserved.lockVersion(),
				command("Inbox", "second-secret", true), CONTEXT).block();
		var afterRotate = repository.find(created.id()).block();
		assertThat(afterRotate.passwordCiphertext()).isNotEqualTo(stored.passwordCiphertext());
		assertThat(afterRotate.passwordNonce()).isNotEqualTo(stored.passwordNonce());
	}

	@Test
	void rejectsPreviewForDisabledAccountsAndStaleDeletes() {
		var created = service.create(ACTOR, command("Disabled", "secret", false), CONTEXT).block();
		assertThatThrownBy(() -> service.preview(created.id(), 20).block())
				.isInstanceOf(MailboxValidationException.class);
		assertThatThrownBy(() -> service.delete(ACTOR, created.id(), 99, CONTEXT).block())
				.isInstanceOf(MailboxConflictException.class);
	}

	private MailboxService.MailboxCommand command(String name, String password, boolean enabled) {
		return new MailboxService.MailboxCommand(
				name, "IMAP", "mail-test", 3143, "PLAIN_LOCAL_ONLY", "user@example.org",
				password, "INBOX", enabled);
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
