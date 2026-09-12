package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.email.tracking.MailTrackingRepository;
import com.camel_hub.advertisement.email.tracking.MailTrackingService;
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
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

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
	private SmtpTransport transport;

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
		transport = mock(SmtpTransport.class);
		service = new SmtpService(repository, new SmtpSecretCrypto(key), new SmtpPolicy(properties),
				audit, hasher, TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)),
				transport, new MailTrackingService(new MailTrackingRepository(databaseClient),
						new MailTrackingProperties(false, "http://localhost:8080", "", Duration.ofDays(30)),
						null, new MailOpenClassifier(), Clock.systemUTC()));
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
	void persistsMonthlyQuotaAndPreservesItWhenAnOlderClientOmitsTheField() {
		var legacy = service.create(ACTOR, command("Legacy", null), CONTEXT).block();
		assertThat(legacy.perMonthLimit()).isNull();
		var capped = service.create(ACTOR, monthlyCommand("Capped", 400, 12_000), CONTEXT).block();
		assertThat(capped.perMonthLimit()).isEqualTo(12_000);
		assertThat(repository.find(capped.id()).block().perMonthLimit()).isEqualTo(12_000);
		var updated = service.update(ACTOR, capped.id(), capped.lockVersion(),
				monthlyCommand("Capped updated", 400, null), CONTEXT).block();
		assertThat(updated.perMonthLimit()).isEqualTo(12_000);
		assertThat(service.list(1, 10).block().items()).anySatisfy(account -> {
			assertThat(account.id()).isEqualTo(capped.id());
			assertThat(account.perMonthLimit()).isEqualTo(12_000);
		});
	}

	@Test
	void rejectsDiagnosticAtTheMonthlyCapBeforeAnySmtpIo() {
		var capped = service.create(ACTOR, new SmtpService.SmtpCommand(
				"Capped diagnostic", "mailpit", 1025, "PLAIN_LOCAL_ONLY", null, null,
				"sender@example.org", "Sender", "reply@example.org", 1, 1, 1, 1, 1, true), CONTEXT).block();
		databaseClient.sql("""
				INSERT INTO mail_send_records(id, source, recipient_masked, subject, smtp_account_id,
				    status, created_at, completed_at)
				VALUES (:id, 'TEMPLATE_TEST', 't***@example.org', 'Existing test', :smtp, 'SMTP_ACCEPTED', now(), now())
				""").bind("id", UUID.randomUUID()).bind("smtp", capped.id()).fetch().rowsUpdated().block();

		assertThatThrownBy(() -> service.sendDiagnostic(ACTOR, capped.id(), "recipient@example.org", "Test",
				"Diagnostic body", false, CONTEXT).block())
				.isInstanceOf(SmtpConflictException.class).hasMessageContaining("quota reached");
		verifyNoInteractions(transport);
		assertThat(databaseClient.sql("SELECT count(*) AS total FROM mail_send_records")
				.map((row, metadata) -> row.get("total", Long.class)).one().block()).isEqualTo(1);
	}

	@Test
	void rejectsMonthlyQuotaBelowDailyQuotaIncludingLegacyUpdates() {
		assertThatThrownBy(() -> service.create(ACTOR, monthlyCommand("Invalid", 400, 399), CONTEXT))
				.isInstanceOf(SmtpValidationException.class);
		var capped = service.create(ACTOR, monthlyCommand("Capped", 400, 12_000), CONTEXT).block();
		assertThatThrownBy(() -> service.update(ACTOR, capped.id(), capped.lockVersion(),
				monthlyCommand("Invalid update", 12_001, null), CONTEXT).block())
				.isInstanceOf(SmtpValidationException.class);
	}

	private SmtpService.SmtpCommand monthlyCommand(String name, int daily, Integer monthly) {
		return new SmtpService.SmtpCommand(name, "mailpit", 1025, "PLAIN_LOCAL_ONLY", null, null,
				"sender@example.org", "Research Team", "reply@example.org", 10, 100, daily, monthly, 50, true);
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
