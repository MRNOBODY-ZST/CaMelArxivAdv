package com.camel_hub.advertisement.campaign.inbound;

import com.camel_hub.advertisement.email.mailbox.MailboxTransport;
import com.camel_hub.advertisement.email.mailbox.MailboxTransportException;
import com.camel_hub.advertisement.email.mailbox.MailboxPolicy;
import com.camel_hub.advertisement.email.mailbox.MailboxProperties;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import io.r2dbc.spi.ConnectionFactories;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InboundMailIntegrationTest {
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_inbound_test").withUsername("camel").withPassword("camel-test-only");
	private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID SMTP = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID MAILBOX = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID CAMPAIGN = UUID.fromString("40000000-0000-0000-0000-000000000001");
	private static final UUID RECIPIENT_REPLY = UUID.fromString("50000000-0000-0000-0000-000000000001");
	private static final UUID RECIPIENT_BOUNCE = UUID.fromString("50000000-0000-0000-0000-000000000002");
	private static final UUID SAFETY_RUN = UUID.fromString("60000000-0000-0000-0000-000000000001");
	private static final UUID SAFETY_MESSAGE = UUID.fromString("70000000-0000-0000-0000-000000000001");
	private static final String PRODUCTION_REPLY =
			"<50000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>";
	private static final String PRODUCTION_BOUNCE =
			"<50000000-0000-0000-0000-000000000002@delivery.camel-arxiv.invalid>";
	private static final String SAFETY =
			"<safety-70000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>";

	private DatabaseClient database;
	private TransactionalOperator transactions;

	@BeforeAll
	static void migrate() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
	}

	@BeforeEach
	void setUp() {
		var factory = ConnectionFactories.get(r2dbcUrl());
		database = DatabaseClient.create(factory);
		transactions = TransactionalOperator.create(new R2dbcTransactionManager(factory));
		database.sql("TRUNCATE audit_logs, mailbox_inbound_events, mailbox_sync_cursors, campaign_safety_events, "
				+ "campaign_safety_messages, campaign_safety_runs, campaign_recipients, campaigns, "
				+ "mailbox_accounts, smtp_accounts, email_template_versions, email_templates, users CASCADE")
				.fetch().rowsUpdated().block();
		seed();
	}

	@Test
	void synchronizesRepliesAutoRepliesBouncesAndSafetyEventsWithoutCrossDomainMutation() {
		database.sql("INSERT INTO suppression_entries (email_hmac,email_domain,reason,source,created_at,expires_at) "
				+ "VALUES (digest('bounce','sha256'),'stale.example','MANUAL','FIXTURE',TIMESTAMPTZ '"
				+ NOW.minusSeconds(7_200) + "',TIMESTAMPTZ '" + NOW.minusSeconds(3_600) + "')")
				.fetch().rowsUpdated().block();
		MailboxTransport transport = mock(MailboxTransport.class);
		InboundMailModels.MailboxRead first = new InboundMailModels.MailboxRead(11, List.of(
				reply(1, PRODUCTION_REPLY, null),
				reply(2, PRODUCTION_REPLY, "auto-replied"),
				bounce(3, PRODUCTION_BOUNCE, "failed", "5.1.1",
						"smtp; 550 5.1.1 <private-author@example.org> rejected"),
				reply(4, SAFETY, null),
				bounce(5, SAFETY, "failed", "5.2.0",
						"smtp; 550 5.2.0 <private-safety@example.org> rejected"),
				reply(6, "<50000000-0000-0000-0000-000000000001@attacker.test>", null),
				new InboundMailModels.InboundEnvelope(
						7, null, null, null, null, null, null, null, true)));
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenReturn(first);
		InboundMailSynchronizer synchronizer = synchronizer(transport);

		InboundMailSynchronizer.SyncResult result = synchronizer.syncOnce(MAILBOX).block();

		assertThat(result).isNotNull();
		assertThat(result.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);
		assertThat(result.processed()).isEqualTo(7);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + RECIPIENT_REPLY + "'"))
				.isEqualTo("SMTP_ACCEPTED");
		assertThat(instant("SELECT replied_at FROM campaign_recipients WHERE id = '" + RECIPIENT_REPLY + "'"))
				.isEqualTo(NOW);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + RECIPIENT_BOUNCE + "'"))
				.isEqualTo("BOUNCED");
		assertThat(count("suppression_entries WHERE email_hmac = digest('bounce','sha256') "
				+ "AND email_domain = 'example.test' AND reason = 'BOUNCED' AND source = 'IMAP_DSN' "
				+ "AND expires_at IS NULL AND created_at = TIMESTAMPTZ '" + NOW + "'")).isEqualTo(1);
		assertThat(count("audit_logs WHERE action = 'CAMPAIGN_RECIPIENT_BOUNCED' "
				+ "AND resource_type = 'CAMPAIGN_RECIPIENT' AND resource_id = '" + RECIPIENT_BOUNCE
				+ "' AND actor_user_id IS NULL AND result = 'SUCCESS' AND error_type IS NULL"))
				.isEqualTo(1);
		assertThat(text("SELECT after_summary::text FROM audit_logs "
				+ "WHERE action = 'CAMPAIGN_RECIPIENT_BOUNCED' LIMIT 1"))
				.isEqualTo("{\"source\": \"IMAP_DSN\", \"status\": \"BOUNCED\", "
						+ "\"suppressionReason\": \"BOUNCED\", \"suppressionChanged\": true}");
		assertThat(count("campaign_safety_events WHERE safety_message_id = '" + SAFETY_MESSAGE
				+ "' AND event_type = 'REPLY'")).isEqualTo(1);
		assertThat(count("campaign_safety_events WHERE safety_message_id = '" + SAFETY_MESSAGE
				+ "' AND event_type = 'BOUNCE' AND diagnostic_code = 'smtp; 550 5.2.0'"))
				.isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_messages WHERE id = '" + SAFETY_MESSAGE + "'"))
				.isEqualTo("SMTP_ACCEPTED");
		assertThat(count("mailbox_inbound_events")).isEqualTo(7);
		assertThat(count("mailbox_inbound_events WHERE inbound_type = 'UNMATCHED' "
				+ "AND campaign_recipient_id IS NULL AND safety_message_id IS NULL")).isEqualTo(2);
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("11:7");
		assertThat(text("SELECT string_agg(to_jsonb(events)::text, ' ') FROM mailbox_inbound_events events"))
				.doesNotContain("private body", "sender@example.test", "private-author@example.org",
						"private-safety@example.org");

		when(transport.readSince(any(), eq("INBOX"), eq(11L), eq(7L), eq(50))).thenReturn(first);
		InboundMailSynchronizer.SyncResult duplicate = synchronizer.syncOnce(MAILBOX).block();
		assertThat(duplicate.processed()).isZero();
		assertThat(count("mailbox_inbound_events")).isEqualTo(7);

		InboundMailModels.MailboxRead reset = new InboundMailModels.MailboxRead(12, List.of(
				bounce(1, PRODUCTION_REPLY, "delayed", "4.2.0", "smtp; 450 retry later")));
		when(transport.readSince(any(), eq("INBOX"), eq(11L), eq(7L), eq(50))).thenReturn(reset);
		InboundMailSynchronizer.SyncResult afterReset = synchronizer.syncOnce(MAILBOX).block();
		assertThat(afterReset.processed()).isEqualTo(1);
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("12:1");
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + RECIPIENT_REPLY + "'"))
				.isEqualTo("SMTP_ACCEPTED");
		assertThat(count("suppression_entries")).isEqualTo(1);
	}

	@Test
	void mailboxFailureReleasesLeaseRetainsCursorAndStoresOnlyFailureCategory() {
		InboundMailRepository repository = new InboundMailRepository(database, transactions);
		InboundMailRepository.CursorLease held = repository.claim(MAILBOX, NOW, Duration.ofMinutes(2)).block();
		assertThat(held).isNotNull();
		assertThat(repository.claim(MAILBOX, NOW.plusSeconds(1), Duration.ofMinutes(2)).block()).isNull();
		repository.complete(held, NOW.plusSeconds(2)).block();

		MailboxTransport transport = mock(MailboxTransport.class);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50)))
				.thenThrow(new MailboxTransportException(
						MailboxTransportException.FailureCategory.CONNECTION_TIMEOUT));
		InboundMailSynchronizer.SyncResult failed = synchronizer(transport).syncOnce(MAILBOX).block();

		assertThat(failed.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.FAILED);
		assertThat(failed.processed()).isZero();
		assertThat(text("SELECT last_remote_uid || ':' || last_error_category FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("0:CONNECTION_TIMEOUT");
		assertThat(count("mailbox_sync_cursors WHERE lease_hash IS NULL AND lease_expires_at IS NULL"))
				.isEqualTo(1);
		assertThat(count("mailbox_inbound_events")).isZero();
		assertThat(count("audit_logs WHERE action = 'MAILBOX_SYNC_FAILED' "
				+ "AND resource_type = 'MAILBOX_ACCOUNT' AND resource_id = '" + MAILBOX
				+ "' AND actor_user_id IS NULL AND result = 'FAILURE' "
				+ "AND error_type = 'CONNECTION_TIMEOUT'")).isEqualTo(1);
		assertThat(text("SELECT after_summary::text FROM audit_logs "
				+ "WHERE action = 'MAILBOX_SYNC_FAILED' ORDER BY occurred_at DESC LIMIT 1"))
				.isEqualTo("{\"failureCategory\": \"CONNECTION_TIMEOUT\"}");
	}

	@Test
	void ambiguousControlledReferencesRemainUnmatchedWithoutAnyDomainSideEffect() {
		MailboxTransport transport = mock(MailboxTransport.class);
		InboundMailModels.MailboxRead ambiguous = new InboundMailModels.MailboxRead(11, List.of(
				reply(1, PRODUCTION_REPLY + " " + PRODUCTION_BOUNCE, null),
				reply(2, PRODUCTION_REPLY + " " + SAFETY, null),
				reply(3, overflowReferences(), null)));
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenReturn(ambiguous);

		InboundMailSynchronizer.SyncResult result = synchronizer(transport).syncOnce(MAILBOX).block();

		assertThat(result.processed()).isEqualTo(3);
		assertThat(count("mailbox_inbound_events WHERE inbound_type = 'UNMATCHED' "
				+ "AND referenced_message_id IS NULL AND campaign_recipient_id IS NULL "
				+ "AND safety_message_id IS NULL")).isEqualTo(3);
		assertThat(count("campaign_recipients WHERE replied_at IS NOT NULL OR status <> 'SMTP_ACCEPTED'"))
				.isZero();
		assertThat(count("campaign_safety_events")).isZero();
		assertThat(count("suppression_entries")).isZero();
	}

	@Test
	void completingAStolenLeaseFailsClosedInsteadOfReportingSuccess() {
		InboundMailRepository repository = new InboundMailRepository(database, transactions);
		InboundMailRepository.CursorLease held = repository.claim(MAILBOX, NOW, Duration.ofMinutes(2)).block();
		assertThat(held).isNotNull();
		database.sql("UPDATE mailbox_sync_cursors SET lease_hash = digest('stolen','sha256') "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'").fetch().rowsUpdated().block();

		assertThatThrownBy(() -> repository.complete(held, NOW.plusSeconds(1)).block())
				.isInstanceOf(IllegalStateException.class);
		assertThat(count("mailbox_sync_cursors WHERE lease_hash IS NOT NULL")).isEqualTo(1);
	}

	@Test
	void failingAStolenLeaseCannotChangeTheCursorOrForgeAnAuthoritativeAuditEvent() {
		InboundMailRepository repository = new InboundMailRepository(database, transactions);
		InboundMailRepository.CursorLease held = repository.claim(MAILBOX, NOW, Duration.ofMinutes(2)).block();
		assertThat(held).isNotNull();
		database.sql("UPDATE mailbox_sync_cursors SET lease_hash = digest('stolen','sha256') "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'").fetch().rowsUpdated().block();

		assertThatThrownBy(() -> repository.fail(held, "CONNECTION_TIMEOUT", NOW.plusSeconds(1)).block())
				.isInstanceOf(IllegalStateException.class);
		assertThat(count("mailbox_sync_cursors WHERE mailbox_account_id = '" + MAILBOX
				+ "' AND last_error_category IS NULL")).isEqualTo(1);
		assertThat(count("audit_logs WHERE action = 'MAILBOX_SYNC_FAILED' AND resource_id = '"
				+ MAILBOX + "'")).isZero();
	}

	@Test
	void failurePreservesTheLastSuccessfulSynchronizationTimestamp() {
		InboundMailRepository repository = new InboundMailRepository(database, transactions);
		InboundMailRepository.CursorLease successful = repository.claim(
				MAILBOX, NOW, Duration.ofMinutes(2)).block();
		assertThat(successful).isNotNull();
		repository.complete(successful, NOW.plusSeconds(1)).block();
		InboundMailRepository.CursorLease failed = repository.claim(
				MAILBOX, NOW.plusSeconds(2), Duration.ofMinutes(2)).block();
		assertThat(failed).isNotNull();

		repository.fail(failed, "CONNECTION_TIMEOUT", NOW.plusSeconds(3)).block();

		assertThat(instant("SELECT last_synced_at FROM mailbox_sync_cursors WHERE mailbox_account_id = '"
				+ MAILBOX + "'")).isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	void malformedRealMimeReportsAdvanceAsUnmatchedWithoutRecipientSideEffects() throws Exception {
		MailboxTransport mimeTransport = mailboxTransport();
		String missingBoundary = "MIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/report; report-type=delivery-status; boundary=missing\r\n"
				+ "In-Reply-To: " + PRODUCTION_REPLY + "\r\n"
				+ "\r\nthis has no MIME boundary\r\n";
		MimeMessage brokenBoundary = new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(missingBoundary.getBytes(StandardCharsets.US_ASCII)));
		InboundMailModels.InboundEnvelope malformedBoundary = mimeTransport.envelope(1, brokenBoundary);

		MimeMessage oversizedIdentity = new MimeMessage(Session.getInstance(new Properties()));
		oversizedIdentity.setHeader("In-Reply-To", PRODUCTION_REPLY);
		MimeMultipart report = new MimeMultipart("report");
		report.addBodyPart(new MimeBodyPart(new ByteArrayInputStream((
				"Content-Type: message/delivery-status\r\n\r\n"
						+ "Action: failed\r\nStatus: 5.1.1\r\nOriginal-Message-ID: "
						+ "x".repeat(4_100) + PRODUCTION_BOUNCE + "\r\n")
				.getBytes(StandardCharsets.US_ASCII))));
		oversizedIdentity.setContent(report);
		oversizedIdentity.saveChanges();
		InboundMailModels.InboundEnvelope malformedIdentity = mimeTransport.envelope(2, oversizedIdentity);
		assertThat(malformedBoundary.malformed()).isTrue();
		assertThat(malformedIdentity.malformed()).isTrue();

		MailboxTransport transport = mock(MailboxTransport.class);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenReturn(
				new InboundMailModels.MailboxRead(11, List.of(malformedBoundary, malformedIdentity)));
		InboundMailSynchronizer.SyncResult result = synchronizer(transport).syncOnce(MAILBOX).block();

		assertThat(result.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);
		assertThat(result.processed()).isEqualTo(2);
		assertThat(count("mailbox_inbound_events WHERE inbound_type = 'UNMATCHED' "
				+ "AND campaign_recipient_id IS NULL AND safety_message_id IS NULL")).isEqualTo(2);
		assertThat(count("campaign_recipients WHERE status <> 'SMTP_ACCEPTED' OR replied_at IS NOT NULL"))
				.isZero();
		assertThat(count("suppression_entries")).isZero();
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("11:2");
	}

	@Test
	void safetyReplyUsesTheMaterializedMailboxSnapshotAfterTheDraftCampaignChanges() {
		UUID replacementMailbox = insertDueMailbox(88);
		database.sql("UPDATE campaigns SET mailbox_account_id = :mailbox WHERE id = :campaign")
				.bind("mailbox", replacementMailbox).bind("campaign", CAMPAIGN)
				.fetch().rowsUpdated().block();
		InboundMailRepository repository = new InboundMailRepository(database, transactions);

		assertThat(repository.dueMailboxIds(NOW, 100).collectList().block()).contains(MAILBOX);
		MailboxTransport transport = mock(MailboxTransport.class);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenReturn(
				new InboundMailModels.MailboxRead(11, List.of(reply(1, SAFETY, null))));

		InboundMailSynchronizer.SyncResult result = synchronizer(transport).syncOnce(MAILBOX).block();

		assertThat(result.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);
		assertThat(count("campaign_safety_events WHERE safety_message_id = '" + SAFETY_MESSAGE
				+ "' AND event_type = 'REPLY'")).isEqualTo(1);
		assertThat(count("mailbox_inbound_events WHERE safety_message_id = '" + SAFETY_MESSAGE + "'"))
				.isEqualTo(1);
	}

	@Test
	void deletingMatchedTargetsCascadesTheirInboundEvidenceWithoutBlockingPrivacyDeletion() {
		MailboxTransport transport = mock(MailboxTransport.class);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenReturn(
				new InboundMailModels.MailboxRead(11, List.of(
						reply(1, PRODUCTION_REPLY, null), reply(2, SAFETY, null))));
		assertThat(synchronizer(transport).syncOnce(MAILBOX).block().processed()).isEqualTo(2);
		assertThat(count("mailbox_inbound_events")).isEqualTo(2);

		database.sql("DELETE FROM campaign_safety_messages WHERE id = '" + SAFETY_MESSAGE + "'")
				.fetch().rowsUpdated().block();
		database.sql("DELETE FROM campaign_recipients WHERE id = '" + RECIPIENT_REPLY + "'")
				.fetch().rowsUpdated().block();

		assertThat(count("mailbox_inbound_events")).isZero();
	}

	@Test
	void permanentBounceWaitsForConnectingSettlementBeforeEventCursorAndSuppression() {
		database.sql("UPDATE campaign_recipients SET status = 'CONNECTING' WHERE id = '"
				+ RECIPIENT_BOUNCE + "'").fetch().rowsUpdated().block();
		MailboxTransport transport = mock(MailboxTransport.class);
		InboundMailModels.MailboxRead bounce = new InboundMailModels.MailboxRead(11, List.of(
				bounce(1, PRODUCTION_BOUNCE, "failed", "5.1.1", "smtp; 550 unavailable")));
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenReturn(bounce);

		InboundMailSynchronizer.SyncResult pending = synchronizer(transport).syncOnce(MAILBOX).block();

		assertThat(pending.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.FAILED);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + RECIPIENT_BOUNCE + "'"))
				.isEqualTo("CONNECTING");
		assertThat(count("mailbox_inbound_events")).isZero();
		assertThat(count("suppression_entries")).isZero();
		assertThat(count("audit_logs WHERE action = 'CAMPAIGN_RECIPIENT_BOUNCED' AND resource_id = '"
				+ RECIPIENT_BOUNCE + "'")).isZero();
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("11:0");

		database.sql("UPDATE campaign_recipients SET status = 'SMTP_ACCEPTED' WHERE id = '"
				+ RECIPIENT_BOUNCE + "'").fetch().rowsUpdated().block();
		when(transport.readSince(any(), eq("INBOX"), eq(11L), eq(0L), eq(50))).thenReturn(bounce);
		InboundMailSynchronizer.SyncResult settled = synchronizer(transport).syncOnce(MAILBOX).block();

		assertThat(settled.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + RECIPIENT_BOUNCE + "'"))
				.isEqualTo("BOUNCED");
		assertThat(count("mailbox_inbound_events")).isEqualTo(1);
		assertThat(count("suppression_entries WHERE reason = 'BOUNCED' AND source = 'IMAP_DSN'"))
				.isEqualTo(1);
		assertThat(count("audit_logs WHERE action = 'CAMPAIGN_RECIPIENT_BOUNCED' AND resource_id = '"
				+ RECIPIENT_BOUNCE + "'")).isEqualTo(1);
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("11:1");

		InboundMailModels.MailboxRead duplicateBounce = new InboundMailModels.MailboxRead(11, List.of(
				bounce(2, PRODUCTION_BOUNCE, "failed", "5.1.1", "smtp; 550 unavailable")));
		when(transport.readSince(any(), eq("INBOX"), eq(11L), eq(1L), eq(50))).thenReturn(duplicateBounce);
		assertThat(synchronizer(transport).syncOnce(MAILBOX).block().status())
				.isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);
		assertThat(count("audit_logs WHERE action = 'CAMPAIGN_RECIPIENT_BOUNCED' AND resource_id = '"
				+ RECIPIENT_BOUNCE + "'")).isEqualTo(1);
	}

	@Test
	void permanentBounceAuditReportsAnExistingActiveSuppressionWithoutRewritingItsSource() {
		database.sql("INSERT INTO suppression_entries (email_hmac,email_domain,reason,source,created_at) "
				+ "SELECT email_hmac,email_domain,'MANUAL','FIXTURE',:now FROM campaign_recipients WHERE id = :recipient")
				.bind("now", NOW.minusSeconds(60)).bind("recipient", RECIPIENT_BOUNCE)
				.fetch().rowsUpdated().block();
		MailboxTransport transport = mock(MailboxTransport.class);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenReturn(
				new InboundMailModels.MailboxRead(11, List.of(
						bounce(1, PRODUCTION_BOUNCE, "failed", "5.1.1", "smtp; 550 unavailable"))));

		assertThat(synchronizer(transport).syncOnce(MAILBOX).block().status())
				.isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);

		assertThat(text("SELECT reason || ':' || source FROM suppression_entries WHERE email_hmac = "
				+ "digest('bounce','sha256')")).isEqualTo("MANUAL:FIXTURE");
		assertThat(text("SELECT after_summary::text FROM audit_logs "
				+ "WHERE action = 'CAMPAIGN_RECIPIENT_BOUNCED'"))
				.isEqualTo("{\"source\": \"FIXTURE\", \"status\": \"BOUNCED\", "
						+ "\"suppressionReason\": \"MANUAL\", \"suppressionChanged\": false}");
	}

	@Test
	void conflictingDeliveryReportMetadataPersistsOnlyAnUnmatchedEvent() {
		MailboxTransport transport = mock(MailboxTransport.class);
		InboundMailModels.InboundEnvelope rejected = new InboundMailModels.InboundEnvelope(
				1, null, null, null, null, null, NOW, null, true);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50)))
				.thenReturn(new InboundMailModels.MailboxRead(11, List.of(rejected)));

		InboundMailSynchronizer.SyncResult result = synchronizer(transport).syncOnce(MAILBOX).block();

		assertThat(result.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);
		assertThat(count("mailbox_inbound_events WHERE inbound_type = 'UNMATCHED' "
				+ "AND referenced_message_id IS NULL")).isEqualTo(1);
		assertThat(count("campaign_recipients WHERE status <> 'SMTP_ACCEPTED' OR replied_at IS NOT NULL"))
				.isZero();
		assertThat(count("suppression_entries")).isZero();
	}

	@Test
	void uidValidityResetPersistsBoundedTailFloorBeforeTheFirstEnvelopeCanFail() {
		MailboxTransport transport = mock(MailboxTransport.class);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50)))
				.thenReturn(new InboundMailModels.MailboxRead(11, List.of()));
		assertThat(synchronizer(transport).syncOnce(MAILBOX).block().status())
				.isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);

		database.sql("UPDATE campaign_recipients SET status = 'CONNECTING' WHERE id = '"
				+ RECIPIENT_BOUNCE + "'").fetch().rowsUpdated().block();
		InboundMailModels.MailboxRead resetTail = new InboundMailModels.MailboxRead(
				12, 999_950, List.of(bounce(
						999_951, PRODUCTION_BOUNCE, "failed", "5.1.1", "smtp; 550 unavailable")));
		when(transport.readSince(any(), eq("INBOX"), eq(11L), eq(0L), eq(50))).thenReturn(resetTail);

		assertThat(synchronizer(transport).syncOnce(MAILBOX).block().status())
				.isEqualTo(InboundMailSynchronizer.SyncStatus.FAILED);
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("12:999950");

		when(transport.readSince(any(), eq("INBOX"), eq(12L), eq(999_950L), eq(50))).thenReturn(resetTail);
		assertThat(synchronizer(transport).syncOnce(MAILBOX).block().status())
				.isEqualTo(InboundMailSynchronizer.SyncStatus.FAILED);
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("12:999950");
	}

	@Test
	void previouslySuccessfulMailboxesThatNowFailRotateBehindAnUnattemptedTwentyFirstMailbox() {
		List<UUID> allMailboxes = new ArrayList<>();
		allMailboxes.add(MAILBOX);
		for (int index = 2; index <= 21; index++) allMailboxes.add(insertDueMailbox(index));
		InboundMailRepository repository = new InboundMailRepository(database, transactions);
		for (UUID mailboxId : allMailboxes) {
			InboundMailRepository.CursorLease initial = repository.claim(
					mailboxId, NOW.minusSeconds(100), Duration.ofMinutes(2)).block();
			assertThat(initial).isNotNull();
			repository.complete(initial, NOW.minusSeconds(99)).block();
		}
		List<UUID> firstBatch = repository.dueMailboxIds(NOW, 20).collectList().block();
		assertThat(firstBatch).hasSize(20);
		Set<UUID> unattempted = new HashSet<>(allMailboxes);
		unattempted.removeAll(firstBatch);
		assertThat(unattempted).hasSize(1);

		for (UUID mailboxId : firstBatch) {
			InboundMailRepository.CursorLease lease = repository.claim(
					mailboxId, NOW, Duration.ofMinutes(2)).block();
			assertThat(lease).isNotNull();
			repository.fail(lease, "CONNECTION_TIMEOUT", NOW.plusSeconds(1)).block();
		}

		List<UUID> secondBatch = repository.dueMailboxIds(NOW.plusSeconds(2), 20).collectList().block();
		assertThat(secondBatch).containsAll(unattempted);
	}

	@Test
	void heartbeatsDuringASlowReadSoASecondWorkerCannotAlternatelyStealTheLease() throws Exception {
		MailboxTransport transport = mock(MailboxTransport.class);
		CountDownLatch readStarted = new CountDownLatch(1);
		CountDownLatch releaseRead = new CountDownLatch(1);
		when(transport.readSince(any(), eq("INBOX"), eq(0L), eq(0L), eq(50))).thenAnswer(ignored -> {
			readStarted.countDown();
			if (!releaseRead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timed out");
			return new InboundMailModels.MailboxRead(11, List.of());
		});
		AtomicReference<Instant> current = new AtomicReference<>(NOW);
		Clock advancing = mock(Clock.class);
		when(advancing.instant()).thenAnswer(ignored -> current.get());
		VirtualTimeScheduler heartbeatScheduler = VirtualTimeScheduler.create();
		InboundMailRepository firstRepository = new InboundMailRepository(database, transactions);
		InboundMailSynchronizer firstWorker = new InboundMailSynchronizer(
				firstRepository, transport, new InboundMailParser(), advancing,
				Duration.ofMinutes(2), 50, heartbeatScheduler);
		CompletableFuture<InboundMailSynchronizer.SyncResult> running = firstWorker
				.syncOnce(MAILBOX).toFuture();
		assertThat(readStarted.await(5, TimeUnit.SECONDS)).isTrue();

		current.set(NOW.plusSeconds(50));
		heartbeatScheduler.advanceTimeBy(Duration.ofSeconds(50));
		Long renewed = database.sql("SELECT 1 FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "' AND lease_expires_at > TIMESTAMPTZ '"
				+ NOW.plusSeconds(130) + "'").map((row, metadata) -> 1L).one()
				.repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(10)).take(200))
				.block(Duration.ofSeconds(3));
		assertThat(renewed).isEqualTo(1L);
		InboundMailRepository secondRepository = new InboundMailRepository(database, transactions);
		assertThat(secondRepository.claim(
				MAILBOX, NOW.plusSeconds(130), Duration.ofMinutes(2)).block()).isNull();

		releaseRead.countDown();
		InboundMailSynchronizer.SyncResult result = running.get(5, TimeUnit.SECONDS);
		assertThat(result.status()).isEqualTo(InboundMailSynchronizer.SyncStatus.COMPLETED);
		assertThat(result.processed()).isZero();
		assertThat(text("SELECT uid_validity || ':' || last_remote_uid FROM mailbox_sync_cursors "
				+ "WHERE mailbox_account_id = '" + MAILBOX + "'")).isEqualTo("11:0");
		assertThat(count("mailbox_sync_cursors WHERE lease_hash IS NULL AND last_synced_at IS NOT NULL"))
				.isEqualTo(1);
		heartbeatScheduler.dispose();
	}

	private InboundMailSynchronizer synchronizer(MailboxTransport transport) {
		return new InboundMailSynchronizer(
				new InboundMailRepository(database, transactions),
				transport, new InboundMailParser(), Clock.fixed(NOW, ZoneOffset.UTC),
				Duration.ofMinutes(2), 50);
	}

	private MailboxTransport mailboxTransport() {
		MailboxProperties properties = new MailboxProperties(
				true, Set.of("localhost"), Duration.ofSeconds(3), Duration.ofSeconds(7), 50);
		return new MailboxTransport(
				new SmtpSecretCrypto(Base64.getEncoder().encodeToString(
						"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))),
				new MailboxPolicy(properties), properties);
	}

	private InboundMailModels.InboundEnvelope reply(long uid, String reference, String automatic) {
		return new InboundMailModels.InboundEnvelope(
				uid, "<inbound-" + uid + "@example.test>", reference, null, automatic,
				"text/plain", NOW, null, false);
	}

	private InboundMailModels.InboundEnvelope bounce(
			long uid, String reference, String action, String status, String diagnostic
	) {
		return new InboundMailModels.InboundEnvelope(
				uid, "<dsn-" + uid + "@example.test>", null, null, "auto-generated",
				"multipart/report; report-type=delivery-status", NOW,
				new InboundMailModels.DsnFields(action, status, diagnostic, reference), false);
	}

	private String overflowReferences() {
		StringBuilder references = new StringBuilder(PRODUCTION_REPLY);
		for (int index = 1; index < 20; index++) {
			references.append(" <00000000-0000-0000-0000-%012d@delivery.camel-arxiv.invalid>"
					.formatted(index));
		}
		return references + " " + PRODUCTION_BOUNCE;
	}

	private void seed() {
		database.sql("INSERT INTO users (id,username,email,password_hash,display_name) VALUES ('" + ACTOR
				+ "','inbound-admin','inbound-admin@example.invalid','hash','Inbound Admin')")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO smtp_accounts (id,name,host,port,tls_mode,from_email,default_from_name,reply_to,"
				+ "per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,created_by) VALUES ('"
				+ SMTP + "','Inbound SMTP','localhost',1025,'PLAIN_LOCAL_ONLY','sender@example.invalid','Team',"
				+ "'reply@example.invalid',10,100,1000,100,true,'" + ACTOR + "')").fetch().rowsUpdated().block();
		database.sql("INSERT INTO mailbox_accounts (id,name,protocol,host,port,tls_mode,username,password_ciphertext,"
				+ "password_nonce,folder_name,enabled,last_tested_at,last_test_status,created_by,updated_by,updated_at) "
				+ "VALUES ('" + MAILBOX + "','Inbound IMAP','IMAP','localhost',1143,'PLAIN_LOCAL_ONLY','inbound',"
				+ "decode('00112233445566778899aabbccddeeff','hex'),decode('00112233445566778899aabb','hex'),"
				+ "'INBOX',true,TIMESTAMPTZ '" + NOW + "','SUCCEEDED','" + ACTOR + "','" + ACTOR
				+ "',TIMESTAMPTZ '" + NOW + "')").fetch().rowsUpdated().block();
		database.sql("INSERT INTO email_templates (id,name,status,created_by,updated_by) VALUES "
				+ "('80000000-0000-0000-0000-000000000001','Inbound','ACTIVE','" + ACTOR + "','" + ACTOR + "')")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO email_template_versions (id,template_id,version_number,subject_template,"
				+ "from_name_template,reply_to,html_content,text_content,content_size_bytes,created_by) VALUES "
				+ "('81000000-0000-0000-0000-000000000001','80000000-0000-0000-0000-000000000001',1,"
				+ "'Subject','Team','reply@example.invalid','<p>Body</p>','Body',4,'" + ACTOR + "')")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO campaigns (id,name,purpose,status,template_id,template_version_id,smtp_account_id,"
				+ "mailbox_account_id,from_name,from_email,reply_to,unsubscribe_enabled,created_by,updated_by) VALUES ('"
				+ CAMPAIGN + "','Inbound campaign','Reply monitoring','RUNNING','80000000-0000-0000-0000-000000000001',"
				+ "'81000000-0000-0000-0000-000000000001','" + SMTP + "','" + MAILBOX
				+ "','Team','sender@example.invalid','reply@example.invalid',true,'" + ACTOR + "','" + ACTOR + "')")
				.fetch().rowsUpdated().block();
		insertRecipient(RECIPIENT_REPLY, "reply", PRODUCTION_REPLY);
		insertRecipient(RECIPIENT_BOUNCE, "bounce", PRODUCTION_BOUNCE);
		database.sql("INSERT INTO campaign_safety_runs (id,campaign_id,smtp_account_id,mailbox_account_id,created_by,recipient_limit,"
				+ "destination_hmac,destination_masked,status,started_at,completed_at,from_name_snapshot,"
				+ "from_email_snapshot,reply_to_snapshot,tracking_opens_enabled,tracking_clicks_enabled) VALUES ('"
				+ SAFETY_RUN + "','" + CAMPAIGN + "','" + SMTP + "','" + MAILBOX + "','" + ACTOR
				+ "',1,digest('destination','sha256'),"
				+ "'f***@example.test','COMPLETED',TIMESTAMPTZ '" + NOW + "',TIMESTAMPTZ '" + NOW
				+ "','Team','sender@example.invalid','reply@example.invalid',false,false)")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO campaign_safety_messages (id,run_id,campaign_recipient_id,smtp_account_id,status,"
				+ "rfc_message_id,rendered_subject,rendered_html,rendered_text,smtp_accepted_at) VALUES ('"
				+ SAFETY_MESSAGE + "','" + SAFETY_RUN + "','" + RECIPIENT_REPLY + "','" + SMTP
				+ "','SMTP_ACCEPTED','" + SAFETY + "','Safety','<p>Safety</p>','Safety',TIMESTAMPTZ '" + NOW + "')")
				.fetch().rowsUpdated().block();
	}

	private void insertRecipient(UUID id, String hmacSeed, String messageId) {
		database.sql("INSERT INTO campaign_recipients (id,campaign_id,email_ciphertext,email_nonce,email_hmac,"
				+ "email_domain,confidence,status,personalization_status,rendered_subject,rendered_html,rendered_text,"
				+ "personalized_at,smtp_accepted_at,rfc_message_id) VALUES ('" + id + "','" + CAMPAIGN + "',decode('aabb','hex'),"
				+ "decode('ccdd','hex'),digest('" + hmacSeed + "','sha256'),'example.test','HIGH','SMTP_ACCEPTED',"
				+ "'GENERATED','Subject','<p>Body</p>','Body',TIMESTAMPTZ '" + NOW + "',TIMESTAMPTZ '"
				+ NOW + "','" + messageId + "')")
				.fetch().rowsUpdated().block();
	}

	private UUID insertDueMailbox(int index) {
		String suffix = "%012d".formatted(index);
		UUID mailboxId = UUID.fromString("30000000-0000-0000-0001-" + suffix);
		UUID campaignId = UUID.fromString("40000000-0000-0000-0001-" + suffix);
		UUID recipientId = UUID.fromString("50000000-0000-0000-0001-" + suffix);
		database.sql("INSERT INTO mailbox_accounts (id,name,protocol,host,port,tls_mode,username,password_ciphertext,"
				+ "password_nonce,folder_name,enabled,last_tested_at,last_test_status,created_by,updated_by,updated_at) "
				+ "VALUES ('" + mailboxId + "','Fair mailbox " + index + "','IMAP','localhost',1143,"
				+ "'PLAIN_LOCAL_ONLY','fair-" + index + "',decode('00112233445566778899aabbccddeeff','hex'),"
				+ "decode('00112233445566778899aabb','hex'),'INBOX',true,TIMESTAMPTZ '" + NOW
				+ "','SUCCEEDED','" + ACTOR + "','" + ACTOR + "',TIMESTAMPTZ '" + NOW + "')")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO campaigns (id,name,purpose,status,template_id,template_version_id,smtp_account_id,"
				+ "mailbox_account_id,from_name,from_email,reply_to,unsubscribe_enabled,created_by,updated_by) VALUES ('"
				+ campaignId + "','Fair campaign " + index + "','Fair inbound scheduling','RUNNING',"
				+ "'80000000-0000-0000-0000-000000000001','81000000-0000-0000-0000-000000000001','"
				+ SMTP + "','" + mailboxId + "','Team','sender@example.invalid','reply@example.invalid',true,'"
				+ ACTOR + "','" + ACTOR + "')").fetch().rowsUpdated().block();
		database.sql("INSERT INTO campaign_recipients (id,campaign_id,email_ciphertext,email_nonce,email_hmac,"
				+ "email_domain,confidence,status,personalization_status,rendered_subject,rendered_html,rendered_text,"
				+ "personalized_at,smtp_accepted_at,rfc_message_id) VALUES ('" + recipientId + "','" + campaignId
				+ "',decode('aabb','hex'),decode('ccdd','hex'),digest('fair-" + index + "','sha256'),'example.test',"
				+ "'HIGH','SMTP_ACCEPTED','GENERATED','Subject','<p>Body</p>','Body',TIMESTAMPTZ '" + NOW
				+ "',TIMESTAMPTZ '" + NOW + "','<" + recipientId + "@delivery.camel-arxiv.invalid>')")
				.fetch().rowsUpdated().block();
		return mailboxId;
	}

	private long count(String clause) {
		return database.sql("SELECT count(*) AS total FROM " + clause)
				.map((row, metadata) -> row.get("total", Number.class).longValue()).one().block();
	}

	private String text(String sql) {
		return database.sql(sql).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private Instant instant(String sql) {
		return database.sql(sql).map((row, metadata) -> row.get(0, Instant.class)).one().block();
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
