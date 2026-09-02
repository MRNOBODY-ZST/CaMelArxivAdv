package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CampaignWorkflowIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_campaign_workflow_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID TEMPLATE = UUID.fromString("70000000-0000-0000-0000-000000000001");
	private static final UUID TEMPLATE_VERSION = UUID.fromString("70100000-0000-0000-0000-000000000001");
	private static final UUID SEGMENT = UUID.fromString("71000000-0000-0000-0000-000000000001");
	private static final UUID SMTP = UUID.fromString("72000000-0000-0000-0000-000000000001");
	private static final UUID MAILBOX = UUID.fromString("73000000-0000-0000-0000-000000000001");
	private static final UUID PAPER = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID AUTHOR = UUID.fromString("40000000-0000-0000-0000-000000000001");
	private static final UUID PAPER_AUTHOR = UUID.fromString("41000000-0000-0000-0000-000000000001");
	private static final AuthenticationRequestContext CONTEXT =
			new AuthenticationRequestContext("192.0.2.90", "CampaignWorkflowTest", "workflowtrace1");
	private static DatabaseClient databaseClient;

	private ObjectMapper objectMapper;
	private CampaignPreflightService preflight;
	private CampaignWorkflowService workflow;
	private CampaignService campaigns;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
	}

	@BeforeEach
	void setUp() {
		sql("""
				TRUNCATE audit_logs, outbox_messages, campaign_exclusions, unsubscribe_records,
				         recipient_delivery_cooldowns, extraction_evidence, paper_author_contacts,
				         campaign_recipients, campaigns, segments, extraction_runs, contacts,
				         paper_authors, authors, papers, arxiv_categories, arxiv_archives, arxiv_groups,
				         mailbox_accounts, smtp_accounts, email_template_versions, email_templates, users CASCADE
				""");
		seedDependencies();
		objectMapper = new ObjectMapper().findAndRegisterModules();
		ConnectionFactory connectionFactory = ConnectionFactories.get(r2dbcUrl());
		TransactionalOperator transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
		CampaignRepository campaignRepository = new CampaignRepository(databaseClient);
		CampaignWorkflowRepository workflowRepository = new CampaignWorkflowRepository(databaseClient);
		CampaignDeliveryProperties delivery = new CampaignDeliveryProperties(
				false, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.org",
				"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", Duration.ofDays(30));
		preflight = new CampaignPreflightService(workflowRepository, delivery, tracking, objectMapper);
		campaigns = new CampaignService(
				campaignRepository, new SegmentRepository(databaseClient, objectMapper),
				new PersonalizationProperties(true, "openai", "gpt-test", 100), objectMapper, transactions);
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(anyString())).thenReturn(new byte[32]);
		workflow = new CampaignWorkflowService(
				workflowRepository, preflight, campaigns, new AuditService(databaseClient, objectMapper),
				hasher, objectMapper, transactions);
	}

	@Test
	void preflightUsesFrozenRecipientsAndStrictCurrentEvidence() {
		UUID campaignId = insertCampaign("DRAFT");
		insertRecipient(campaignId, "eligible", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "medium", "MEDIUM", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "unverified", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", false, "CONFIRMED", true);
		insertHistoricalVerifiedEvidence(campaignId, "unverified");
		insertRecipient(campaignId, "unconfirmed", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "UNVERIFIED", true);
		insertRecipient(campaignId, "authorless", "HIGH", true, false, "ACTIVE", true,
				null, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "syntax", "HIGH", false, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "example", "HIGH", true, true, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "inactive", "HIGH", true, false, "SUPPRESSED", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "deleted", "HIGH", true, false, "ACTIVE", false,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "low-evidence", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "MEDIUM", true, "CONFIRMED", true);
		insertRecipient(campaignId, "no-evidence", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", false);
		insertRecipient(campaignId, "suppressed", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "unsubscribed", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "excluded", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "cooled", "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		insertRecipient(campaignId, "mismatch", "HIGH", true, false, "ACTIVE", true,
				UUID.randomUUID(), false, "HIGH", true, "CONFIRMED", true);

		sql("INSERT INTO suppression_entries (email_hmac, email_domain, reason, source) "
				+ "SELECT email_hmac, email_domain, 'MANUAL', 'TEST' FROM campaign_recipients "
				+ "WHERE campaign_id = '" + campaignId + "' AND author_name_snapshot = 'suppressed'");
		sql("INSERT INTO unsubscribe_records (email_hmac, campaign_id, campaign_recipient_id, token_hash) "
				+ "SELECT email_hmac, campaign_id, id, digest('unsubscribe-token', 'sha256') "
				+ "FROM campaign_recipients WHERE campaign_id = '" + campaignId
				+ "' AND author_name_snapshot = 'unsubscribed'");
		sql("INSERT INTO campaign_exclusions (campaign_id, email_hmac, reason, created_by) "
				+ "SELECT campaign_id, email_hmac, 'MANUAL', '" + ACTOR + "' FROM campaign_recipients "
				+ "WHERE campaign_id = '" + campaignId + "' AND author_name_snapshot = 'excluded'");
		sql("INSERT INTO recipient_delivery_cooldowns (email_hmac, last_smtp_accepted_at) "
				+ "SELECT email_hmac, now() - interval '179 days' FROM campaign_recipients "
				+ "WHERE campaign_id = '" + campaignId + "' AND author_name_snapshot = 'cooled'");

		long auditBefore = count("audit_logs");
		long outboxBefore = count("outbox_messages");
		var result = preflight.preflight(campaignId).block();

		assertThat(result).isNotNull();
		assertThat(result.ready()).isTrue();
		assertThat(result.checks()).containsOnlyKeys(
				"CONTENT_READY", "UNSUBSCRIBE_PRESENT", "SENDER_VALID", "SMTP_READY",
				"MAILBOX_READY", "TRACKING_READY", "RECIPIENTS_ELIGIBLE");
		assertThat(result.checks().values()).allMatch(CampaignPreflightService.PreflightCheck::passed);
		assertThat(result.counts()).containsEntry("TOTAL", 16L).containsEntry("ELIGIBLE", 1L)
				.containsEntry("CONFIDENCE_NOT_HIGH", 1L)
				.containsEntry("EVIDENCE_UNVERIFIED", 1L)
				.containsEntry("EVIDENCE_UNCONFIRMED", 1L)
				.containsEntry("AUTHOR_RELATION_MISSING", 2L)
				.containsEntry("SYNTAX_INVALID", 1L)
				.containsEntry("EXAMPLE_ADDRESS", 1L)
				.containsEntry("CONTACT_INACTIVE", 2L)
				.containsEntry("CONTACT_DELETED", 1L)
				.containsEntry("EVIDENCE_NOT_HIGH", 1L)
				.containsEntry("EVIDENCE_MISSING", 1L)
				.containsEntry("SUPPRESSED", 1L)
				.containsEntry("UNSUBSCRIBED", 1L)
				.containsEntry("CAMPAIGN_EXCLUDED", 1L)
				.containsEntry("COOLDOWN_ACTIVE", 1L);
		assertThat(result.digest()).matches("[0-9a-f]{64}");
		assertThat(count("audit_logs")).isEqualTo(auditBefore);
		assertThat(count("outbox_messages")).isEqualTo(outboxBefore);
		assertThat(longValue("SELECT lock_version FROM campaigns WHERE id = '" + campaignId + "'")).isZero();
	}

	@Test
	void preflightRejectsMissingRenderedContentAndUnsubscribePlaceholder() {
		UUID campaignId = readyCampaign("DRAFT");
		sql("UPDATE campaign_recipients SET rendered_subject = '   ' WHERE campaign_id = '" + campaignId + "'");

		var missingContent = preflight.preflight(campaignId).block();
		assertThat(missingContent.ready()).isFalse();
		assertThat(missingContent.checks().get("CONTENT_READY").passed()).isFalse();

		sql("UPDATE campaign_recipients SET rendered_subject = 'Subject', rendered_html = '<p>Hello</p>' "
				+ "WHERE campaign_id = '" + campaignId + "'");
		var missingPlaceholder = preflight.preflight(campaignId).block();
		assertThat(missingPlaceholder.ready()).isFalse();
		assertThat(missingPlaceholder.checks().get("UNSUBSCRIBE_PRESENT").passed()).isFalse();
	}

	@Test
	void preflightRejectsStaleSmtpAndMailboxConnectionTests() {
		UUID campaignId = readyCampaign("DRAFT");
		sql("UPDATE smtp_accounts SET updated_at = last_tested_at + interval '1 second' WHERE id = '" + SMTP + "'");

		var staleSmtp = preflight.preflight(campaignId).block();
		assertThat(staleSmtp.ready()).isFalse();
		assertThat(staleSmtp.checks().get("SMTP_READY").passed()).isFalse();

		sql("UPDATE smtp_accounts SET last_tested_at = updated_at WHERE id = '" + SMTP + "'");
		sql("UPDATE mailbox_accounts SET updated_at = last_tested_at + interval '1 second' WHERE id = '"
				+ MAILBOX + "'");
		var staleMailbox = preflight.preflight(campaignId).block();
		assertThat(staleMailbox.ready()).isFalse();
		assertThat(staleMailbox.checks().get("SMTP_READY").passed()).isTrue();
		assertThat(staleMailbox.checks().get("MAILBOX_READY").passed()).isFalse();
	}

	@Test
	void supportsEveryPermittedLifecycleTransitionAndWritesSafeAtomicAuditAndWakeups() throws Exception {
		UUID campaignId = readyCampaign("DRAFT");
		var updated = workflow.update(campaignId, ACTOR, CONTEXT, 0,
				new CampaignWorkflowService.CampaignUpdateCommand(
						"Updated campaign", "A concrete research purpose", MAILBOX,
						"Research Team", "reply@example.org", true, true)).block();
		assertThat(updated.status()).isEqualTo("DRAFT");
		assertThat(updated.lockVersion()).isEqualTo(1);
		assertThat(updated.mailboxAccountId()).isEqualTo(MAILBOX);
		assertThat(updated.trackingOpensEnabled()).isTrue();

		var submitted = workflow.submitReview(campaignId, ACTOR, CONTEXT, 1).block();
		assertThat(submitted.status()).isEqualTo("READY_FOR_REVIEW");
		assertThat(submitted.lockVersion()).isEqualTo(2);
		assertThat(submitted.submittedForReviewAt()).isNotNull();

		var approved = workflow.approve(campaignId, ACTOR, CONTEXT, 2).block();
		assertThat(approved.status()).isEqualTo("APPROVED");
		assertThat(approved.lockVersion()).isEqualTo(3);
		assertThat(approved.approvedBy()).isEqualTo(ACTOR);

		Instant scheduledAt = Instant.now().plus(Duration.ofHours(2));
		var scheduled = workflow.schedule(campaignId, ACTOR, CONTEXT, 3, scheduledAt).block();
		assertThat(scheduled.status()).isEqualTo("SCHEDULED");
		assertThat(scheduled.lockVersion()).isEqualTo(4);
		assertThat(scheduled.scheduledAt()).isEqualTo(scheduledAt);

		var started = workflow.start(campaignId, ACTOR, CONTEXT, 4).block();
		assertThat(started.status()).isEqualTo("RUNNING");
		assertThat(started.lockVersion()).isEqualTo(5);
		assertThat(started.startedAt()).isNotNull();

		var paused = workflow.pause(campaignId, ACTOR, CONTEXT, 5).block();
		assertThat(paused.status()).isEqualTo("PAUSED");
		assertThat(paused.lockVersion()).isEqualTo(6);
		var resumed = workflow.resume(campaignId, ACTOR, CONTEXT, 6).block();
		assertThat(resumed.status()).isEqualTo("RUNNING");
		assertThat(resumed.lockVersion()).isEqualTo(7);
		var canceled = workflow.cancel(campaignId, ACTOR, CONTEXT, 7).block();
		assertThat(canceled.status()).isEqualTo("CANCELED");
		assertThat(canceled.lockVersion()).isEqualTo(8);
		assertThat(canceled.canceledAt()).isNotNull();

		assertThat(count("outbox_messages")).isEqualTo(2);
		assertThat(strings("SELECT topic_name FROM outbox_messages ORDER BY available_at, id"))
				.containsOnly("camel.mail.delivery.jobs.v1");
		for (String payload : strings("SELECT CAST(payload AS text) FROM outbox_messages ORDER BY available_at, id")) {
			JsonNode json = objectMapper.readTree(payload);
			assertThat(fieldNames(json)).containsExactlyInAnyOrder(
					"version", "messageId", "campaignId", "action", "traceId", "createdAt");
			assertThat(allFieldNames(json)).noneMatch(name -> Set.of(
					"email", "subject", "html", "text", "token", "ciphertext", "nonce").contains(name.toLowerCase()));
		}
		assertThat(strings("SELECT payload ->> 'action' FROM outbox_messages ORDER BY available_at, id"))
				.containsExactlyInAnyOrder("SCHEDULE", "START");

		assertThat(count("audit_logs")).isEqualTo(8);
		assertThat(strings("SELECT DISTINCT resource_type FROM audit_logs")).containsExactly("CAMPAIGN");
		assertThat(strings("SELECT trace_id FROM audit_logs")).containsOnly(CONTEXT.traceId());
		assertThat(strings("SELECT user_agent_summary FROM audit_logs")).containsOnly(CONTEXT.userAgentSummary());
		for (String summary : strings("SELECT CAST(before_summary AS text) FROM audit_logs UNION ALL "
				+ "SELECT CAST(after_summary AS text) FROM audit_logs")) {
			assertThat(fieldNames(objectMapper.readTree(summary))).isSubsetOf("status", "lockVersion");
		}
	}

	@Test
	void rejectionCanReturnToDraftOnlyThroughAnAuditedEdit() {
		UUID campaignId = readyCampaign("DRAFT");
		workflow.submitReview(campaignId, ACTOR, CONTEXT, 0).block();
		var rejected = workflow.reject(campaignId, ACTOR, CONTEXT, 1, "The purpose needs clarification").block();

		assertThat(rejected.status()).isEqualTo("REJECTED");
		assertThat(rejected.rejectedBy()).isEqualTo(ACTOR);
		assertThat(rejected.rejectionReason()).isEqualTo("The purpose needs clarification");
		var edited = workflow.update(campaignId, ACTOR, CONTEXT, 2,
				new CampaignWorkflowService.CampaignUpdateCommand(
						"Revised", "Clarified purpose", MAILBOX, "Research Team",
						"reply@example.org", false, false)).block();
		assertThat(edited.status()).isEqualTo("DRAFT");
		assertThat(edited.rejectedAt()).isNull();
		assertThat(edited.rejectedBy()).isNull();
		assertThat(edited.rejectionReason()).isNull();
		assertThat(longValue("SELECT count(*) FROM campaigns WHERE id = '" + campaignId
				+ "' AND review_preflight_digest IS NULL")).isEqualTo(1);
	}

	@Test
	void rejectsInvalidStateOrStaleVersionWithoutAuditOrOutboxSideEffects() {
		UUID campaignId = readyCampaign("DRAFT");

		assertThatThrownBy(() -> workflow.start(campaignId, ACTOR, CONTEXT, 0).block())
				.isInstanceOf(CampaignConflictException.class);
		assertThat(count("audit_logs")).isZero();
		assertThat(count("outbox_messages")).isZero();

		workflow.submitReview(campaignId, ACTOR, CONTEXT, 0).block();
		assertThatThrownBy(() -> workflow.approve(campaignId, ACTOR, CONTEXT, 0).block())
				.isInstanceOf(CampaignConflictException.class);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo("READY_FOR_REVIEW");
		assertThat(longValue("SELECT lock_version FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo(1);
		assertThat(count("audit_logs")).isEqualTo(1);
		assertThat(count("outbox_messages")).isZero();
	}

	@Test
	void rejectsBlankRejectionReasonWithoutStateOrAuditChanges() {
		UUID campaignId = readyCampaign("DRAFT");
		workflow.submitReview(campaignId, ACTOR, CONTEXT, 0).block();
		long auditBefore = count("audit_logs");

		assertThatThrownBy(() -> workflow.reject(campaignId, ACTOR, CONTEXT, 1, "   ").block())
				.isInstanceOf(CampaignValidationException.class);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo("READY_FOR_REVIEW");
		assertThat(count("audit_logs")).isEqualTo(auditBefore);
	}

	@Test
	void rejectsPastScheduleWithoutStateAuditOrOutboxChanges() {
		UUID campaignId = readyCampaign("DRAFT");
		workflow.submitReview(campaignId, ACTOR, CONTEXT, 0).block();
		workflow.approve(campaignId, ACTOR, CONTEXT, 1).block();
		long auditBefore = count("audit_logs");

		assertThatThrownBy(() -> workflow.schedule(
				campaignId, ACTOR, CONTEXT, 2, Instant.now().minusSeconds(1)).block())
				.isInstanceOf(CampaignValidationException.class);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo("APPROVED");
		assertThat(count("audit_logs")).isEqualTo(auditBefore);
		assertThat(count("outbox_messages")).isZero();
	}

	@Test
	void recomputesEligibilityWhenSuppressionAppearsAfterEarlierPreflight() {
		UUID submitCampaign = readyCampaign("DRAFT");
		assertThat(preflight.preflight(submitCampaign).block().ready()).isTrue();
		insertSuppression(submitCampaign);

		assertThatThrownBy(() -> workflow.submitReview(submitCampaign, ACTOR, CONTEXT, 0).block())
				.isInstanceOf(CampaignValidationException.class);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + submitCampaign + "'"))
				.isEqualTo("DRAFT");

		UUID startCampaign = readyCampaign("DRAFT");
		workflow.submitReview(startCampaign, ACTOR, CONTEXT, 0).block();
		workflow.approve(startCampaign, ACTOR, CONTEXT, 1).block();
		assertThat(preflight.preflight(startCampaign).block().ready()).isTrue();
		insertSuppression(startCampaign);

		assertThatThrownBy(() -> workflow.start(startCampaign, ACTOR, CONTEXT, 2).block())
				.isInstanceOf(CampaignValidationException.class);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + startCampaign + "'"))
				.isEqualTo("APPROVED");
		assertThat(count("outbox_messages")).isZero();
	}

	private UUID readyCampaign(String status) {
		UUID campaignId = insertCampaign(status);
		insertRecipient(campaignId, "eligible-" + campaignId, "HIGH", true, false, "ACTIVE", true,
				AUTHOR, true, "HIGH", true, "CONFIRMED", true);
		return campaignId;
	}

	private UUID insertCampaign(String status) {
		UUID id = UUID.randomUUID();
		sql("""
				INSERT INTO campaigns (
				    id, name, purpose, status, template_id, template_version_id, segment_id,
				    smtp_account_id, mailbox_account_id, from_name, from_email, reply_to,
				    tracking_opens_enabled, tracking_clicks_enabled, unsubscribe_enabled,
				    created_by, updated_by
				)
				VALUES ('%s', 'Workflow campaign', 'A concrete research discussion purpose', '%s',
				        '%s', '%s', '%s', '%s', '%s', 'Research Team', 'research@example.org',
				        'reply@example.org', false, false, true, '%s', '%s')
				""".formatted(id, status, TEMPLATE, TEMPLATE_VERSION, SEGMENT, SMTP, MAILBOX, ACTOR, ACTOR));
		return id;
	}

	private void insertRecipient(
			UUID campaignId, String label, String recipientConfidence, boolean syntaxValid, boolean example,
			String contactStatus, boolean currentContact, UUID recipientAuthor, boolean matchingAuthorRelation,
			String evidenceConfidence, boolean humanVerified, String verificationStatus, boolean evidencePresent
	) {
		UUID contactId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		UUID pacId = UUID.randomUUID();
		if (recipientAuthor != null && !AUTHOR.equals(recipientAuthor)) {
			sql("INSERT INTO authors (id, normalized_name, display_name) VALUES ('" + recipientAuthor
					+ "', 'mismatched author', 'Mismatched Author') ON CONFLICT (id) DO NOTHING");
		}
		sql("""
				INSERT INTO contacts (
				    id, email_ciphertext, email_nonce, email_hmac, email_domain, display_ciphertext,
				    display_nonce, syntax_valid, example_address, suppression_status, deleted_at
				)
				VALUES ('%s', decode('aa', 'hex'), decode('bb', 'hex'), digest('%s', 'sha256'),
				        'university.edu', decode('cc', 'hex'), decode('dddddddddddddddddddddddd', 'hex'),
				        %s, %s, '%s', %s)
				""".formatted(contactId, label, syntaxValid, example, contactStatus,
				currentContact ? "NULL" : "now()"));
		sql("""
				INSERT INTO extraction_runs (id, paper_id, parser_version, status, started_at, completed_at)
				VALUES ('%s', '%s', '1.0', 'SUCCEEDED', now(), now())
				""".formatted(runId, PAPER));
		UUID relation = matchingAuthorRelation ? PAPER_AUTHOR : null;
		sql("""
				INSERT INTO paper_author_contacts (
				    id, paper_author_id, paper_id, contact_id, extraction_run_id, confidence,
				    corresponding_author, human_verified, verification_status
				)
				VALUES ('%s', %s, '%s', '%s', '%s', '%s', true, %s, '%s')
				""".formatted(pacId, relation == null ? "NULL" : "'" + relation + "'", PAPER,
				contactId, runId, evidenceConfidence, humanVerified, verificationStatus));
		if (evidencePresent) {
			sql("""
					INSERT INTO extraction_evidence (
					    paper_author_contact_id, source_relative_path, rule_name, masked_context
					)
					VALUES ('%s', 'paper.tex', 'author-email', 'masked context')
					""".formatted(pacId));
		}
		sql("""
				INSERT INTO campaign_recipients (
				    campaign_id, contact_id, paper_id, author_id, email_ciphertext, email_nonce,
				    email_hmac, email_domain, author_name_snapshot, paper_title_snapshot,
				    confidence, status, personalization_status, rendered_subject,
				    rendered_html, rendered_text, personalized_at
				)
				VALUES ('%s', '%s', '%s', %s, decode('aa', 'hex'), decode('bb', 'hex'),
				        digest('%s', 'sha256'), 'university.edu', '%s', 'A paper', '%s', 'QUEUED',
				        'GENERATED', 'Subject', '<p>Hello</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
				        'Hello {{unsubscribe_url}}', now())
				""".formatted(campaignId, contactId, PAPER,
				recipientAuthor == null ? "NULL" : "'" + recipientAuthor + "'", label, label, recipientConfidence));
	}

	private void insertSuppression(UUID campaignId) {
		sql("INSERT INTO suppression_entries (email_hmac, email_domain, reason, source) "
				+ "SELECT email_hmac, email_domain, 'MANUAL', 'TEST' FROM campaign_recipients "
				+ "WHERE campaign_id = '" + campaignId + "'");
	}

	private void insertHistoricalVerifiedEvidence(UUID campaignId, String label) {
		UUID runId = UUID.randomUUID();
		UUID pacId = UUID.randomUUID();
		sql("INSERT INTO extraction_runs (id, paper_id, parser_version, status, started_at, completed_at) "
				+ "VALUES ('" + runId + "', '" + PAPER + "', '0.9', 'SUCCEEDED', now() - interval '2 days', "
				+ "now() - interval '2 days')");
		sql("INSERT INTO paper_author_contacts (id, paper_author_id, paper_id, contact_id, extraction_run_id, "
				+ "confidence, corresponding_author, human_verified, verification_status, created_at) "
				+ "SELECT '" + pacId + "', '" + PAPER_AUTHOR + "', '" + PAPER + "', contact_id, '" + runId
				+ "', 'HIGH', true, true, 'CONFIRMED', now() - interval '1 day' "
				+ "FROM campaign_recipients WHERE campaign_id = '" + campaignId
				+ "' AND author_name_snapshot = '" + label + "'");
		sql("INSERT INTO extraction_evidence (paper_author_contact_id, source_relative_path, rule_name, masked_context) "
				+ "VALUES ('" + pacId + "', 'old.tex', 'old-author-email', 'old masked context')");
	}

	private void seedDependencies() {
		sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'campaign-admin',
				        'campaign-admin@example.invalid', 'hash', 'Campaign Admin')
				""");
		sql("""
				INSERT INTO email_templates (id, name, description, status, created_by, updated_by)
				VALUES ('70000000-0000-0000-0000-000000000001', 'Paper outreach', 'Safe style',
				        'ACTIVE', '10000000-0000-0000-0000-000000000001',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO email_template_versions (
				    id, template_id, version_number, subject_template, from_name_template, reply_to,
				    html_content, text_content, content_size_bytes, validation_result, created_by
				)
				VALUES ('70100000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001',
				        1, 'About {{paper_title}}', 'Research Team', 'reply@example.org',
				        '<p>Hello</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
				        'Hello {{unsubscribe_url}}', 120, '{"valid":true}',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO smtp_accounts (
				    id, name, host, port, tls_mode, from_email, default_from_name, reply_to,
				    per_minute_limit, per_hour_limit, per_day_limit, per_domain_hour_limit,
				    enabled, last_tested_at, last_test_status, created_by
				)
				VALUES ('72000000-0000-0000-0000-000000000001', 'Internal Mailpit', 'mailpit', 1025,
				        'PLAIN_LOCAL_ONLY', 'research@example.org', 'Research Team', 'reply@example.org',
				        10, 100, 500, 50, true, now(), 'SUCCEEDED',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO mailbox_accounts (
				    id, name, protocol, host, port, tls_mode, username, password_ciphertext,
				    password_nonce, folder_name, enabled, last_tested_at, last_test_status,
				    created_by, updated_by
				)
				VALUES ('73000000-0000-0000-0000-000000000001', 'Replies', 'IMAP', 'mail-test', 1143,
				        'PLAIN_LOCAL_ONLY', 'reply-user', decode('00112233445566778899aabbccddeeff', 'hex'),
				        decode('00112233445566778899aabb', 'hex'), 'INBOX', true, now(), 'SUCCEEDED',
				        '10000000-0000-0000-0000-000000000001',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO segments (id, name, description, created_by)
				VALUES ('71000000-0000-0000-0000-000000000001', 'Confirmed AI', 'Safe contacts',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO arxiv_groups (id, group_id, group_name)
				VALUES ('20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science')
				""");
		sql("""
				INSERT INTO arxiv_categories (
				    id, group_ref_id, group_id, group_name, category_id, category_name
				)
				VALUES ('21000000-0000-0000-0000-000000000001',
				        '20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science',
				        'cs.AI', 'Artificial Intelligence')
				""");
		sql("""
				INSERT INTO papers (
				    id, arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at, pdf_url
				)
				VALUES ('30000000-0000-0000-0000-000000000001', '2608.00001',
				        'Safe Distributed Intelligence', 'A public abstract.',
				        '21000000-0000-0000-0000-000000000001', now() - interval '1 day', now(),
				        'https://arxiv.org/pdf/2608.00001')
				""");
		sql("""
				INSERT INTO authors (id, normalized_name, display_name)
				VALUES ('40000000-0000-0000-0000-000000000001', 'ada lovelace', 'Ada Lovelace')
				""");
		sql("""
				INSERT INTO paper_authors (
				    id, paper_id, author_id, author_order, corresponding_author, raw_name, affiliation_text
				)
				VALUES ('41000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001',
				        '40000000-0000-0000-0000-000000000001', 1, true,
				        'Ada Lovelace', 'Analytical Engine University')
				""");
	}

	private Set<String> fieldNames(JsonNode node) {
		Set<String> names = new java.util.LinkedHashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private List<String> allFieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		if (node.isObject()) {
			Iterator<String> fields = node.fieldNames();
			while (fields.hasNext()) {
				String name = fields.next();
				names.add(name);
				allFieldNames(node.get(name), names);
			}
		}
		else if (node.isArray()) {
			node.forEach(child -> allFieldNames(child, names));
		}
		return names;
	}

	private void allFieldNames(JsonNode node, List<String> names) {
		if (node.isObject()) {
			Iterator<String> fields = node.fieldNames();
			while (fields.hasNext()) {
				String name = fields.next();
				names.add(name);
				allFieldNames(node.get(name), names);
			}
		}
		else if (node.isArray()) {
			node.forEach(child -> allFieldNames(child, names));
		}
	}

	private void sql(String statement) {
		databaseClient.sql(statement).fetch().rowsUpdated().block();
	}

	private long count(String table) {
		return longValue("SELECT count(*) FROM " + table);
	}

	private long longValue(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	private String text(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private List<String> strings(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, String.class)).all()
				.collectList().block();
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
