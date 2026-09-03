package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRuntimePolicy;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetySigner;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.messaging.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignDeliveryRepositoryIntegrationTest extends CampaignDeliveryDatabaseTestSupport {

	private CampaignDeliveryRepository repository;

	@BeforeEach
	void createRepository() {
		repository = repository();
	}

	@Test
	void claimsOneEligibleRecipientAndDurablyReservesBeforeNetworkIo() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "author-one@research.test");

		CampaignDeliveryRepository.ProductionClaim claim = repository.claimNextProduction(NOW).block();

		assertThat(claim).isNotNull();
		assertThat(claim.recipientId()).isEqualTo(recipientId);
		assertThat(claim.attemptNumber()).isEqualTo(1);
		assertThat(claim.idempotencyKey()).isEqualTo("delivery:" + recipientId + ":1");
		assertThat(claim.rfcMessageId()).isEqualTo("<" + recipientId + "@delivery.camel-arxiv.invalid>");
		assertThat(claim.correlationId()).isEqualTo("delivery-" + recipientId);
		assertThat(claim.leaseDigest()).hasSize(32);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("CONNECTING");
		assertThat(integer("SELECT attempt_count FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(1);
		assertThat(text("SELECT status FROM delivery_attempts WHERE campaign_recipient_id = '" + recipientId + "'"))
				.isEqualTo("CONNECTING");
		assertThat(repository.claimNext(NOW).block()).isNull();
	}

	@Test
	void disablingSafetyDurablyCancelsQueuedRunAndImmediatelyUnblocksProductionClaim() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "disabled-safety-gate@research.test");
		UUID safetyMessage = insertDueSafetyRun(recipientId, "fixed@example.test");
		UUID runId = uuid("SELECT run_id FROM campaign_safety_messages WHERE id = '" + safetyMessage + "'");
		CampaignSafetyRepository safetyRepository = new CampaignSafetyRepository(
				databaseClient, transactions, new ObjectMapper().findAndRegisterModules());

		assertThat(repository.claimNextProduction(NOW).block()).isNull();
		assertThat(safetyRepository.cancelActiveRunsBecauseDisabled(NOW, 10).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + runId + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT status FROM campaign_safety_messages WHERE id = '" + safetyMessage + "'"))
				.isEqualTo("CANCELED");
		assertThat(count("audit_logs WHERE action = 'CAMPAIGN_SAFETY_DISABLED_CANCELED' "
				+ "AND resource_id = '" + runId + "'")).isEqualTo(1);
		assertThat(repository.claimNextProduction(NOW).block()).extracting(
				CampaignDeliveryRepository.ProductionClaim::recipientId).isEqualTo(recipientId);
		assertThat(safetyRepository.cancelActiveRunsBecauseDisabled(NOW, 10).block()).isZero();
		assertThat(count("audit_logs WHERE action = 'CAMPAIGN_SAFETY_DISABLED_CANCELED' "
				+ "AND resource_id = '" + runId + "'")).isEqualTo(1);
	}

	@Test
	void disablingSafetyCancelsAnActiveRunWhoseCreatingUserNoLongerExists() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "deleted-creator-gate@research.test");
		UUID safetyMessage = insertDueSafetyRun(recipientId, "fixed@example.test");
		UUID runId = uuid("SELECT run_id FROM campaign_safety_messages WHERE id = '" + safetyMessage + "'");
		UUID deletedCreator = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES (:id, :username, :email, 'hash', 'Deleted safety creator')
				""").bind("id", deletedCreator).bind("username", "deleted-safety-" + deletedCreator)
				.bind("email", deletedCreator + "@example.invalid").fetch().rowsUpdated().block();
		databaseClient.sql("UPDATE campaign_safety_runs SET created_by = :creator WHERE id = :run")
				.bind("creator", deletedCreator).bind("run", runId).fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM users WHERE id = :creator")
				.bind("creator", deletedCreator).fetch().rowsUpdated().block();
		assertThat(count("campaign_safety_runs WHERE id = '" + runId + "' AND created_by IS NULL"))
				.isEqualTo(1);
		CampaignSafetyRepository safetyRepository = new CampaignSafetyRepository(
				databaseClient, transactions, new ObjectMapper().findAndRegisterModules());

		assertThat(repository.claimNextProduction(NOW).block()).isNull();
		assertThat(safetyRepository.cancelActiveRunsBecauseDisabled(NOW, 10).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + runId + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT status FROM campaign_safety_messages WHERE id = '" + safetyMessage + "'"))
				.isEqualTo("CANCELED");
		assertThat(count("audit_logs WHERE action = 'CAMPAIGN_SAFETY_DISABLED_CANCELED' "
				+ "AND resource_id = '" + runId + "' AND actor_user_id IS NULL")).isEqualTo(1);
		assertThat(repository.claimNextProduction(NOW).block()).extracting(
				CampaignDeliveryRepository.ProductionClaim::recipientId).isEqualTo(recipientId);
	}

	@ParameterizedTest(name = "{0} rolling limit defers without reserving")
	@MethodSource("rollingLimits")
	void enforcesEveryRollingLimitAtItsActualReleaseBoundary(
			String window, int minute, int hour, int day, int domainHour,
			int existing, Duration oldestAge, Duration spacing, Duration windowLength
	) {
		setLimits(minute, hour, day, domainHour);
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "candidate@research.test");
		for (int index = 0; index < existing; index++) {
			insertProductionReservation(
					"history-" + window + "-" + index,
					"research.test", NOW.minus(oldestAge).plus(spacing.multipliedBy(index)), "SMTP_ACCEPTED");
		}

		assertThat(repository.claimNext(NOW).block()).isNull();

		Instant expectedRelease = NOW.minus(oldestAge).plus(windowLength);
		assertThat(instant("SELECT next_attempt_at FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(expectedRelease);
		assertThat(count("delivery_attempts")).isEqualTo(existing);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("QUEUED");
	}

	private static Stream<Arguments> rollingLimits() {
		return Stream.of(
				Arguments.of("minute", 2, 100, 1000, 100, 2,
						Duration.ofSeconds(50), Duration.ofSeconds(20), Duration.ofMinutes(1)),
				Arguments.of("hour", 100, 10, 1000, 100, 10,
						Duration.ofMinutes(50), Duration.ofMinutes(4), Duration.ofHours(1)),
				Arguments.of("day", 100, 100, 30, 100, 30,
						Duration.ofHours(23), Duration.ofMinutes(40), Duration.ofDays(1)),
				Arguments.of("domain-hour", 100, 100, 1000, 10, 10,
						Duration.ofMinutes(45), Duration.ofMinutes(4), Duration.ofHours(1))
		);
	}

	@Test
	void countsSafetyConnectingAndAcceptedAttemptsAgainstTheSameAccountWindows() {
		setLimits(1, 100, 1000, 100);
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "candidate@research.test");
		insertSafetyReservation(recipientId, NOW.minusSeconds(20), "CONNECTING");

		assertThat(repository.claimNext(NOW).block()).isNull();
		assertThat(count("delivery_attempts")).isZero();
		assertThat(instant("SELECT next_attempt_at FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(NOW.plusSeconds(40));
	}

	@Test
	void rateLimitedSafetyWorkNeverFallsThroughToMutateTheProductionQueue() {
		setLimits(1, 100, 1000, 100);
		UUID safetyCampaign = insertCampaign("DRAFT");
		UUID safetySource = insertEligibleRecipient(safetyCampaign, "source@authors.test");
		UUID safetyMessage = insertDueSafetyRun(safetySource, "fixed@research.test");
		UUID productionCampaign = insertCampaign("RUNNING");
		UUID productionRecipient = insertEligibleRecipient(productionCampaign, "production@authors.test");
		insertProductionReservation("shared-capacity", "other.test", NOW.minusSeconds(20), "SMTP_ACCEPTED");
		String productionBefore = text("SELECT to_jsonb(r)::text FROM campaign_recipients r WHERE id = '"
				+ productionRecipient + "'");

		assertThat(safetyRepository("fixed@research.test").claimNext(NOW).block()).isNull();

		assertThat(instant("SELECT next_attempt_at FROM campaign_safety_messages WHERE id = '"
				+ safetyMessage + "'")).isEqualTo(NOW.plusSeconds(40));
		assertThat(text("SELECT to_jsonb(r)::text FROM campaign_recipients r WHERE id = '"
				+ productionRecipient + "'")).isEqualTo(productionBefore);
		assertThat(count("campaign_safety_attempts")).isZero();
	}

	@ParameterizedTest(name = "production history enforces the safety {0} window")
	@MethodSource("rollingLimits")
	void productionReservationsEnforceEveryRollingWindowOnSafetyClaims(
			String window, int minute, int hour, int day, int domainHour,
			int existing, Duration oldestAge, Duration spacing, Duration windowLength
	) {
		setLimits(minute, hour, day, domainHour);
		UUID campaignId = insertCampaign("DRAFT");
		UUID source = insertEligibleRecipient(campaignId, "source@research.test");
		UUID safetyMessage = insertDueSafetyRun(source, "fixed@research.test");
		for (int index = 0; index < existing; index++) {
			insertProductionReservation("safety-" + window + "-" + index, "research.test",
					NOW.minus(oldestAge).plus(spacing.multipliedBy(index)), "SMTP_ACCEPTED");
		}

		assertThat(safetyRepository("fixed@research.test").claimNext(NOW).block()).isNull();

		assertThat(instant("SELECT next_attempt_at FROM campaign_safety_messages WHERE id = '"
				+ safetyMessage + "'")).isEqualTo(NOW.minus(oldestAge).plus(windowLength));
		assertThat(count("campaign_safety_attempts")).isZero();
	}

	@Test
	void usesTheLatestReleaseWhenSeveralRateWindowsAreSaturated() {
		setLimits(2, 3, 1000, 100);
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "multi-window@research.test");
		insertProductionReservation("multi-old", "other.test", NOW.minus(Duration.ofMinutes(40)), "SMTP_ACCEPTED");
		insertProductionReservation("multi-minute-1", "other.test", NOW.minusSeconds(50), "CONNECTING");
		insertProductionReservation("multi-minute-2", "other.test", NOW.minusSeconds(20), "SMTP_ACCEPTED");

		assertThat(repository.claimNext(NOW).block()).isNull();
		assertThat(instant("SELECT next_attempt_at FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(NOW.plus(Duration.ofMinutes(20)));
		assertThat(count("delivery_attempts")).isEqualTo(3);
	}

	@Test
	void countsOnlyConnectingAndSmtpAcceptedReservations() {
		setLimits(1, 1, 1, 1);
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "eligible@research.test");
		for (String status : java.util.List.of(
				"TEMPORARY_FAILURE", "PERMANENT_FAILURE", "OUTCOME_UNKNOWN", "CANCELED")) {
			insertProductionReservation("ignored-" + status, "research.test", NOW.minusSeconds(10), status);
		}

		CampaignDeliveryRepository.ProductionClaim claim = repository.claimNextProduction(NOW).block();

		assertThat(claim).isNotNull();
		assertThat(claim.recipientId()).isEqualTo(recipientId);
		assertThat(count("delivery_attempts")).isEqualTo(5);
	}

	@Test
	void rateReleaseIsExclusiveBeforeTheBoundaryAndAvailableAtTheBoundary() {
		setLimits(1, 100, 1000, 100);
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "boundary@research.test");
		insertProductionReservation("boundary-history", "other.test", NOW.minusSeconds(50), "SMTP_ACCEPTED");

		assertThat(repository.claimNext(NOW).block()).isNull();
		Instant release = NOW.plusSeconds(10);
		assertThat(repository.claimNext(release.minusNanos(1)).block()).isNull();
		CampaignDeliveryRepository.ProductionClaim atBoundary = repository.claimNextProduction(release).block();

		assertThat(atBoundary).isNotNull();
		assertThat(atBoundary.recipientId()).isEqualTo(recipientId);
	}

	@Test
	void derivesHistoricalSafetyDomainFromItsMaskedSnapshotNotCurrentConfiguration() {
		setLimits(100, 100, 1000, 1);
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "candidate@test.invalid");
		insertSafetyReservation(recipientId, NOW.minus(Duration.ofMinutes(15)), "SMTP_ACCEPTED");
		CampaignDeliveryRepository withSafetyDomain = new CampaignDeliveryRepository(
				databaseClient, transactions, DELIVERY_PROPERTIES,
				new CampaignSafetyProperties(true, "fixed@research.test", 20));

		assertThat(withSafetyDomain.claimNext(NOW).block()).isNull();
		assertThat(instant("SELECT next_attempt_at FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(NOW.plus(Duration.ofMinutes(45)));
	}

	@Test
	void atomicallyTerminatesRecipientsWhoseEligibilityChangedAfterApproval() {
		UUID suppressedCampaign = insertCampaign("RUNNING");
		UUID suppressed = insertEligibleRecipient(suppressedCampaign, "suppressed@research.test");
		sql("INSERT INTO suppression_entries (email_hmac, email_domain, reason, source) "
				+ "SELECT email_hmac, email_domain, 'MANUAL', 'TEST' FROM campaign_recipients WHERE id = '" + suppressed + "'");

		UUID unsubscribedCampaign = insertCampaign("RUNNING");
		UUID unsubscribed = insertEligibleRecipient(unsubscribedCampaign, "unsubscribed@research.test");
		sql("INSERT INTO unsubscribe_records (email_hmac, campaign_id, campaign_recipient_id, token_hash) "
				+ "SELECT email_hmac, campaign_id, id, digest('unsubscribe', 'sha256') FROM campaign_recipients WHERE id = '"
				+ unsubscribed + "'");

		UUID evidenceCampaign = insertCampaign("RUNNING");
		UUID invalidEvidence = insertEligibleRecipient(evidenceCampaign, "evidence@research.test");
		sql("UPDATE paper_author_contacts SET human_verified = false WHERE contact_id = "
				+ "(SELECT contact_id FROM campaign_recipients WHERE id = '" + invalidEvidence + "')");

		UUID cooldownCampaign = insertCampaign("RUNNING");
		UUID cooled = insertEligibleRecipient(cooldownCampaign, "cooled@research.test");
		sql("INSERT INTO recipient_delivery_cooldowns (email_hmac, last_smtp_accepted_at, updated_at) "
				+ "SELECT email_hmac, TIMESTAMPTZ '" + NOW.minus(10, ChronoUnit.DAYS) + "', TIMESTAMPTZ '" + NOW
				+ "' FROM campaign_recipients WHERE id = '" + cooled + "'");

		for (int index = 0; index < 4; index++) {
			assertThat(repository.claimNext(NOW).block()).isNull();
		}

		assertThat(recipientState(suppressed)).isEqualTo(new RecipientState("SUPPRESSED", "GLOBAL_SUPPRESSION"));
		assertThat(recipientState(unsubscribed)).isEqualTo(new RecipientState("UNSUBSCRIBED", "UNSUBSCRIBED"));
		assertThat(recipientState(invalidEvidence)).isEqualTo(new RecipientState("SUPPRESSED", "EVIDENCE_INVALID"));
		assertThat(recipientState(cooled)).isEqualTo(new RecipientState("SUPPRESSED", "COOLDOWN_ACTIVE"));
		assertThat(count("delivery_attempts")).isZero();
	}

	@Test
	void claimRejectsRecipientWhoseApprovedConfidenceIsNoLongerHigh() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "medium@research.test");
		sql("UPDATE campaign_recipients SET confidence = 'MEDIUM' WHERE id = '" + recipientId + "'");

		assertThat(repository.claimNext(NOW).block()).isNull();

		assertThat(recipientState(recipientId)).isEqualTo(new RecipientState("SUPPRESSED", "EVIDENCE_INVALID"));
		assertThat(count("delivery_attempts")).isZero();
	}

	@Test
	void claimRejectsContactSoftDeletedAfterApproval() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "deleted@research.test");
		sql("UPDATE contacts SET deleted_at = TIMESTAMPTZ '" + NOW + "' WHERE id = "
				+ "(SELECT contact_id FROM campaign_recipients WHERE id = '" + recipientId + "')");

		assertThat(repository.claimNext(NOW).block()).isNull();

		assertThat(recipientState(recipientId)).isEqualTo(new RecipientState("SUPPRESSED", "CONTACT_INVALID"));
		assertThat(count("delivery_attempts")).isZero();
	}

	@Test
	void firstClaimRequiresBothApprovedUnsubscribePlaceholders() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "missing-placeholder@research.test");
		sql("UPDATE campaign_recipients SET rendered_text = 'Finalized too early' WHERE id = '" + recipientId + "'");

		assertThat(repository.claimNext(NOW).block()).isNull();

		assertThat(recipientState(recipientId)).isEqualTo(new RecipientState("SUPPRESSED", "CONTENT_INVALID"));
		assertThat(count("delivery_attempts")).isZero();
	}

	@Test
	void claimRequiresTheExactPaperAuthorRelationFromTheApprovedSnapshot() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "relation@research.test");
		UUID otherPaper = UUID.randomUUID();
		sql("INSERT INTO papers (id, arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at, pdf_url) "
				+ "VALUES ('" + otherPaper + "', '3000.00002', 'Other Paper', 'Abstract', "
				+ "'21000000-0000-0000-0000-000000000001', TIMESTAMPTZ '" + NOW.minus(Duration.ofDays(1))
				+ "', TIMESTAMPTZ '" + NOW + "', 'https://arxiv.org/pdf/3000.00002')");
		sql("UPDATE paper_authors SET paper_id = '" + otherPaper + "' WHERE id = '" + PAPER_AUTHOR + "'");

		assertThat(repository.claimNext(NOW).block()).isNull();

		assertThat(recipientState(recipientId)).isEqualTo(new RecipientState("SUPPRESSED", "EVIDENCE_INVALID"));
		assertThat(count("delivery_attempts")).isZero();
	}

	@Test
	void sendTimeEvidenceMatchesPreflightLatestMappingRatherThanAnUnrelatedPaperRun() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "stable-evidence@research.test");
		UUID unrelatedRun = UUID.randomUUID();
		sql("INSERT INTO extraction_runs (id, paper_id, parser_version, status, started_at, completed_at) "
				+ "VALUES ('" + unrelatedRun + "', '" + PAPER + "', 'new-parser', 'SUCCEEDED', TIMESTAMPTZ '"
				+ NOW.plusSeconds(1) + "', TIMESTAMPTZ '" + NOW.plusSeconds(2) + "')");

		CampaignDeliveryRepository.ProductionClaim claim = repository.claimNextProduction(NOW.plusSeconds(3)).block();

		assertThat(claim).isNotNull();
		assertThat(claim.recipientId()).isEqualTo(recipientId);
	}

	@Test
	void eligibilityReconciliationProcessesOnlyItsConfiguredRecipientBatch() {
		UUID campaign = insertCampaign("RUNNING");
		UUID firstRecipient = insertEligibleRecipient(campaign, "bounded-one@research.test");
		UUID secondRecipient = insertEligibleRecipient(campaign, "bounded-two@research.test");
		sql("UPDATE paper_author_contacts SET human_verified = false");
		CampaignDeliveryProperties oneCampaignPerRun = new CampaignDeliveryProperties(
				true, 1, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		CampaignDeliveryRepository bounded = new CampaignDeliveryRepository(
				databaseClient, transactions, oneCampaignPerRun,
				new CampaignSafetyProperties(false, "", 20));

		assertThat(bounded.reconcileUndeliverable(NOW).block()).isEqualTo(1);
		assertThat(java.util.List.of(recipientState(firstRecipient).status(), recipientState(secondRecipient).status()))
				.containsExactlyInAnyOrder("SUPPRESSED", "QUEUED");
		assertThat(bounded.reconcileUndeliverable(NOW.plusSeconds(1)).block()).isEqualTo(1);
		assertThat(java.util.List.of(recipientState(firstRecipient).status(), recipientState(secondRecipient).status()))
				.containsOnly("SUPPRESSED");
	}

	@Test
	void everyScheduledReconciliationOperationIsBounded() {
		CampaignDeliveryProperties onePerRun = new CampaignDeliveryProperties(
				true, 1, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		CampaignDeliveryRepository bounded = new CampaignDeliveryRepository(
				databaseClient, transactions, onePerRun,
				new CampaignSafetyProperties(false, "", 20));

		UUID scheduledOne = insertCampaign("SCHEDULED");
		UUID scheduledTwo = insertCampaign("SCHEDULED");
		assertThat(bounded.activateDueCampaigns(NOW).block()).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaigns WHERE id IN ('" + scheduledOne + "','"
				+ scheduledTwo + "') AND status = 'RUNNING'")).isEqualTo(1);

		UUID canceledOne = insertEligibleRecipient(insertCampaign("CANCELED"), "canceled-one@research.test");
		UUID canceledTwo = insertEligibleRecipient(insertCampaign("CANCELED"), "canceled-two@research.test");
		assertThat(bounded.reconcileCanceledRecipients(NOW).block()).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_recipients WHERE id IN ('" + canceledOne + "','"
				+ canceledTwo + "') AND status = 'CANCELED'")).isEqualTo(1);

		UUID expiredCampaign = insertCampaign("RUNNING");
		UUID expiredOne = insertEligibleRecipient(expiredCampaign, "expired-one@research.test");
		UUID expiredTwo = insertEligibleRecipient(expiredCampaign, "expired-two@research.test");
		CampaignDeliveryRepository.ProductionClaim firstClaim = bounded.claimNextProduction(NOW).block();
		CampaignDeliveryRepository.ProductionClaim secondClaim = bounded.claimNextProduction(NOW).block();
		assertThat(java.util.List.of(firstClaim.recipientId(), secondClaim.recipientId()))
				.containsExactlyInAnyOrder(expiredOne, expiredTwo);
		assertThat(bounded.reconcileExpiredLeases(NOW.plus(Duration.ofMinutes(3))).block()).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_recipients WHERE id IN ('" + expiredOne + "','"
				+ expiredTwo + "') AND status = 'OUTCOME_UNKNOWN'")).isEqualTo(1);

		UUID terminalOne = insertCampaign("RUNNING");
		UUID terminalTwo = insertCampaign("RUNNING");
		insertEligibleRecipient(terminalOne, "terminal-one@research.test");
		insertEligibleRecipient(terminalTwo, "terminal-two@research.test");
		sql("UPDATE campaign_recipients SET status = 'PERMANENT_FAILURE' WHERE campaign_id IN ('"
				+ terminalOne + "','" + terminalTwo + "')");
		assertThat(bounded.reconcileCampaigns(NOW).block()).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaigns WHERE id IN ('" + terminalOne + "','"
				+ terminalTwo + "') AND status = 'COMPLETED'")).isEqualTo(1);
	}

	@Test
	void eligibilityReconciliationNeverMutatesProductionRowsDuringActiveSafetyRun() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "safety-isolated@research.test");
		sql("UPDATE paper_author_contacts SET human_verified = false WHERE contact_id = "
				+ "(SELECT contact_id FROM campaign_recipients WHERE id = '" + recipientId + "')");
		insertSafetyRun(recipientId, "RUNNING");

		assertThat(repository.reconcileUndeliverable(NOW).block()).isZero();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("QUEUED");
		assertThat(count("delivery_attempts")).isZero();
	}

	@Test
	void cooldownSentinelDoesNotBlockFailuresOrExplicitRetryButAcceptanceDoes() {
		UUID firstCampaign = insertCampaign("RUNNING");
		UUID firstRecipient = insertEligibleRecipient(firstCampaign, "cooldown-semantics@research.test");
		CampaignDeliveryRepository.ProductionClaim first = repository.claimNextProduction(NOW).block();
		repository.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				temporary(450, "450 later"), NOW).block();
		assertThat(instant("SELECT last_smtp_accepted_at FROM recipient_delivery_cooldowns WHERE email_hmac = "
				+ "(SELECT email_hmac FROM campaign_recipients WHERE id = '" + firstRecipient + "')"))
				.isEqualTo(Instant.parse("1900-01-01T00:00:00Z"));

		UUID otherCampaign = insertCampaign("RUNNING");
		UUID otherRecipient = cloneRecipientToCampaign(firstRecipient, otherCampaign);
		sql("UPDATE campaign_recipients SET next_attempt_at = TIMESTAMPTZ '" + NOW
				+ "' WHERE id = '" + otherRecipient + "'");
		CampaignDeliveryRepository.ProductionClaim other = repository.claimNextProduction(NOW.plusSeconds(1)).block();
		assertThat(other).isNotNull();
		assertThat(other.recipientId()).isEqualTo(otherRecipient);
		repository.completeFailure(other.recipientId(), other.attemptId(), other.leaseDigest(),
				new SmtpTransportException(SmtpTransportException.FailureCategory.SMTP_REJECTED,
						AttemptStatus.PERMANENT_FAILURE, TransportStage.RCPT_TO, 550, "550 rejected", false),
				NOW.plusSeconds(1)).block();

		CampaignDeliveryRepository.ProductionClaim retry = repository.claimNextProduction(NOW.plus(Duration.ofMinutes(1))).block();
		assertThat(retry).isNotNull();
		assertThat(retry.recipientId()).isEqualTo(firstRecipient);
		repository.completeAccepted(retry.recipientId(), retry.attemptId(), retry.leaseDigest(),
				new SmtpTransport.SmtpOutcome(AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA,
						250, "250 queued"), NOW.plus(Duration.ofMinutes(1))).block();

		UUID thirdCampaign = insertCampaign("RUNNING");
		UUID thirdRecipient = cloneRecipientToCampaign(firstRecipient, thirdCampaign);
		sql("UPDATE campaign_recipients SET status = 'QUEUED', attempt_count = 0, next_attempt_at = TIMESTAMPTZ '"
				+ NOW.plus(Duration.ofMinutes(2)) + "' WHERE id = '" + thirdRecipient + "'");
		assertThat(repository.claimNext(NOW.plus(Duration.ofMinutes(2))).block()).isNull();
		assertThat(recipientState(thirdRecipient))
				.isEqualTo(new RecipientState("SUPPRESSED", "COOLDOWN_ACTIVE"));
	}

	@Test
	void unknownOutcomeConservativelyBlocksTheSameAddressUntilAdministrativeResolution() {
		UUID firstCampaign = insertCampaign("RUNNING");
		UUID firstRecipient = insertEligibleRecipient(firstCampaign, "unknown-cooldown@research.test");
		CampaignDeliveryRepository.ProductionClaim first = repository.claimNextProduction(NOW).block();
		repository.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE,
						AttemptStatus.OUTCOME_UNKNOWN, TransportStage.POST_DATA,
						null, "connection ended after DATA", false), NOW).block();
		UUID secondCampaign = insertCampaign("RUNNING");
		UUID secondRecipient = cloneRecipientToCampaign(firstRecipient, secondCampaign);

		assertThat(repository.claimNext(NOW.plusSeconds(1)).block()).isNull();
		assertThat(recipientState(secondRecipient)).isEqualTo(new RecipientState("QUEUED", null));
		assertThat(count("delivery_attempts")).isEqualTo(1);
		assertThat(instant("SELECT last_smtp_accepted_at FROM recipient_delivery_cooldowns WHERE email_hmac = "
				+ "(SELECT email_hmac FROM campaign_recipients WHERE id = '" + firstRecipient + "')"))
				.isEqualTo(Instant.parse("1900-01-01T00:00:00Z"));
	}

	@Test
	void unresolvedAddressCannotStarveUnrelatedDeliverableCampaigns() {
		UUID unknownCampaign = insertCampaign("RUNNING");
		UUID unknownRecipient = insertEligibleRecipient(unknownCampaign, "unknown-starvation@research.test");
		CampaignDeliveryRepository.ProductionClaim unknown = repository.claimNextProduction(NOW).block();
		repository.completeFailure(unknown.recipientId(), unknown.attemptId(), unknown.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE,
						AttemptStatus.OUTCOME_UNKNOWN, TransportStage.POST_DATA,
						null, "connection ended after DATA", false), NOW).block();
		UUID campaignA = insertCampaign("RUNNING");
		UUID campaignB = insertCampaign("RUNNING");
		UUID firstByDatabase = databaseClient.sql("SELECT id FROM campaigns WHERE id IN (:a, :b) ORDER BY id LIMIT 1")
				.bind("a", campaignA).bind("b", campaignB)
				.map((row, metadata) -> row.get("id", UUID.class)).one().block();
		UUID blockedCampaign = firstByDatabase;
		UUID deliverableCampaign = firstByDatabase.equals(campaignA) ? campaignB : campaignA;
		cloneRecipientToCampaign(unknownRecipient, blockedCampaign);
		UUID deliverable = insertEligibleRecipient(deliverableCampaign, "unrelated@research.test");

		CampaignDeliveryRepository.ProductionClaim claim = repository.claimNextProduction(NOW.plusSeconds(1)).block();

		assertThat(claim).isNotNull();
		assertThat(claim.recipientId()).isEqualTo(deliverable);
	}

	@Test
	void retriesOnlyExplicitFourHundredsAtOneAndFiveMinutesThenStopsAfterAttemptThree() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "retry@research.test");
		CampaignDeliveryRepository.ProductionClaim first = repository.claimNextProduction(NOW).block();

		repository.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				temporary(450, "450 busy"), NOW).block();
		assertThat(recipientState(recipientId)).isEqualTo(new RecipientState("TEMPORARY_FAILURE", null));
		assertThat(instant("SELECT next_attempt_at FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(NOW.plus(Duration.ofMinutes(1)));
		assertThat(repository.claimNext(NOW.plusSeconds(59)).block()).isNull();

		CampaignDeliveryRepository.ProductionClaim second = repository.claimNextProduction(NOW.plus(Duration.ofMinutes(1))).block();
		assertThat(second.attemptNumber()).isEqualTo(2);
		assertThat(second.rfcMessageId()).isEqualTo(first.rfcMessageId());
		assertThat(second.correlationId()).isEqualTo(first.correlationId());
		repository.completeFailure(second.recipientId(), second.attemptId(), second.leaseDigest(),
				temporary(451, "451 later"), NOW.plus(Duration.ofMinutes(1))).block();
		assertThat(instant("SELECT next_attempt_at FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(NOW.plus(Duration.ofMinutes(6)));

		CampaignDeliveryRepository.ProductionClaim third = repository.claimNextProduction(NOW.plus(Duration.ofMinutes(6))).block();
		assertThat(third.attemptNumber()).isEqualTo(3);
		repository.completeFailure(third.recipientId(), third.attemptId(), third.leaseDigest(),
				temporary(452, "452 still busy"), NOW.plus(Duration.ofMinutes(6))).block();

		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("PERMANENT_FAILURE");
		assertThat(bool("SELECT retryable FROM delivery_attempts WHERE id = '" + third.attemptId() + "'"))
				.isFalse();
		assertThat(repository.claimNext(NOW.plus(Duration.ofDays(1))).block()).isNull();
	}

	@Test
	void retryAcceptsTaskFourFinalizedContentWithoutRequiringTheOriginalPlaceholder() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "finalized-retry@research.test");
		CampaignDeliveryRepository.ProductionClaim first = repository.claimNextProduction(NOW).block();
		repository.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				temporary(450, "450 later"), NOW).block();
		sql("UPDATE campaign_recipients SET rendered_html = '<p>Final</p><a href=\"https://tracking.test/u/opaque\">unsubscribe</a>', "
				+ "rendered_text = 'Final https://tracking.test/u/opaque' WHERE id = '" + recipientId + "'");

		CampaignDeliveryRepository.ProductionClaim retry = repository
				.claimNextProduction(NOW.plus(Duration.ofMinutes(1))).block();

		assertThat(retry).isNotNull();
		assertThat(retry.attemptNumber()).isEqualTo(2);
		assertThat(retry.rfcMessageId()).isEqualTo(first.rfcMessageId());
	}

	@Test
	void unknownPostDataOutcomeAndExpiredLeaseAreTerminalAndNeverRequeued() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "unknown@research.test");
		CampaignDeliveryRepository.ProductionClaim claim = repository.claimNextProduction(NOW).block();

		repository.completeFailure(claim.recipientId(), claim.attemptId(), claim.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE,
						AttemptStatus.OUTCOME_UNKNOWN, TransportStage.POST_DATA, null,
						"connection ended after DATA", false), NOW).block();

		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
		assertThat(repository.claimNext(NOW.plus(Duration.ofDays(2))).block()).isNull();

		UUID secondCampaign = insertCampaign("RUNNING");
		UUID expired = insertEligibleRecipient(secondCampaign, "expired@research.test");
		CampaignDeliveryRepository.ProductionClaim expiredClaim = repository.claimNextProduction(NOW.plusSeconds(1)).block();
		assertThat(expiredClaim.recipientId()).isEqualTo(expired);
		assertThat(repository.reconcileExpiredLeases(
				NOW.plus(DELIVERY_PROPERTIES.leaseDuration()).plusSeconds(1)).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + expired + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
	}

	@Test
	void leaseDigestFencesCompletionAndAcceptanceUpdatesCooldown() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "accepted@research.test");
		CampaignDeliveryRepository.ProductionClaim claim = repository.claimNextProduction(NOW).block();
		SmtpTransport.SmtpOutcome accepted = new SmtpTransport.SmtpOutcome(
				AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");

		assertThat(repository.completeAccepted(
				claim.recipientId(), claim.attemptId(), new byte[32], accepted, NOW).block()).isFalse();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("CONNECTING");

		assertThat(repository.completeAccepted(
				claim.recipientId(), claim.attemptId(), claim.leaseDigest(), accepted, NOW).block()).isTrue();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("SMTP_ACCEPTED");
		assertThat(instant("SELECT last_smtp_accepted_at FROM recipient_delivery_cooldowns WHERE email_hmac = "
				+ "(SELECT email_hmac FROM campaign_recipients WHERE id = '" + recipientId + "')"))
				.isEqualTo(NOW);
	}

	@Test
	void wrongLeaseCannotSettleFailure() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "failure-fence@research.test");
		CampaignDeliveryRepository.ProductionClaim claim = repository.claimNextProduction(NOW).block();

		assertThat(repository.completeFailure(claim.recipientId(), claim.attemptId(), new byte[32],
				temporary(450, "450 later"), NOW).block()).isFalse();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("CONNECTING");
		assertThat(text("SELECT status FROM delivery_attempts WHERE id = '" + claim.attemptId() + "'"))
				.isEqualTo("CONNECTING");
		assertThatThrownBy(() -> repository.completeFailure(
				claim.recipientId(), claim.attemptId(), null,
				temporary(450, "450 later"), NOW).block())
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void completesCampaignOnlyAfterEveryRecipientIsTerminal() {
		UUID campaignId = insertCampaign("RUNNING");
		insertEligibleRecipient(campaignId, "done@research.test");
		sql("UPDATE campaign_recipients SET status = 'SMTP_ACCEPTED', smtp_accepted_at = TIMESTAMPTZ '" + NOW + "'");

		assertThat(repository.reconcileCampaigns(NOW).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo("COMPLETED");
	}

	@ParameterizedTest
	@ValueSource(strings = {"QUEUED", "CONNECTING", "TEMPORARY_FAILURE"})
	void neverCompletesCampaignWhileAnyRecipientIsNonTerminal(String recipientStatus) {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "nonterminal@research.test");
		sql("UPDATE campaign_recipients SET status = '" + recipientStatus + "' WHERE id = '" + recipientId + "'");

		assertThat(repository.reconcileCampaigns(NOW).block()).isZero();
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo("RUNNING");
	}

	@Test
	void claimRechecksCurrentAccountMailboxAndContactReadiness() {
		UUID accountCampaign = insertCampaign("RUNNING");
		UUID accountRecipient = insertEligibleRecipient(accountCampaign, "stale-account@research.test");
		sql("UPDATE smtp_accounts SET updated_at = last_tested_at + interval '1 second', last_test_status = NULL "
				+ "WHERE id = '" + SMTP + "'");
		assertThat(repository.claimNext(NOW).block()).isNull();
		assertThat(count("delivery_attempts")).isZero();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + accountRecipient + "'"))
				.isEqualTo("SUPPRESSED");

		sql("UPDATE smtp_accounts SET last_test_status = 'SUCCEEDED', last_tested_at = updated_at WHERE id = '" + SMTP + "'");
		UUID mailboxCampaign = insertCampaign("RUNNING");
		UUID mailboxRecipient = insertEligibleRecipient(mailboxCampaign, "stale-mailbox@research.test");
		sql("UPDATE mailbox_accounts SET protocol = 'POP3' WHERE id = '" + MAILBOX + "'");
		assertThat(repository.claimNext(NOW).block()).isNull();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + mailboxRecipient + "'"))
				.isEqualTo("SUPPRESSED");
	}

	@Test
	void activatesDueSchedulesAndCancelsOnlyWorkNotHandedToSmtp() {
		UUID scheduled = insertCampaign("SCHEDULED");
		UUID queued = insertEligibleRecipient(scheduled, "scheduled@research.test");
		sql("UPDATE campaigns SET scheduled_at = TIMESTAMPTZ '" + NOW.minusSeconds(1)
				+ "' WHERE id = '" + scheduled + "'");

		assertThat(repository.activateDueCampaigns(NOW).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + scheduled + "'"))
				.isEqualTo("RUNNING");

		sql("UPDATE campaigns SET status = 'CANCELED', canceled_at = TIMESTAMPTZ '" + NOW
				+ "' WHERE id = '" + scheduled + "'");
		assertThat(repository.reconcileCanceledRecipients(NOW).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + queued + "'"))
				.isEqualTo("CANCELED");

		UUID inFlightCampaign = insertCampaign("RUNNING");
		UUID inFlight = insertEligibleRecipient(inFlightCampaign, "inflight@research.test");
		repository.claimNext(NOW).block();
		sql("UPDATE campaigns SET status = 'CANCELED', canceled_at = TIMESTAMPTZ '" + NOW
				+ "' WHERE id = '" + inFlightCampaign + "'");
		repository.reconcileCanceledRecipients(NOW).block();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + inFlight + "'"))
				.isEqualTo("CONNECTING");
	}

	@Test
	void outboxPublisherAllowlistIncludesOnlyKnownTopicsIncludingDeliveryWakeups() {
		sql("INSERT INTO outbox_messages (topic_name, routing_key, message_type, message_version, "
				+ "idempotency_key, payload, trace_id) VALUES "
				+ "('camel.mail.delivery.jobs.v1', 'mail.delivery.wakeup', 'CAMPAIGN_DELIVERY_WAKEUP', 1, "
				+ "'known-delivery', '{}', 'deliverytrace1'), "
				+ "('unknown.private.topic', 'unknown', 'UNKNOWN', 1, 'unknown-private', '{}', 'deliverytrace2')");

		var claimed = new OutboxRepository(databaseClient).claimBatch(10).collectList().block();

		assertThat(claimed).extracting(OutboxRepository.OutboxMessage::topic)
				.containsExactly("camel.mail.delivery.jobs.v1");
	}

	private SmtpTransportException temporary(int code, String summary) {
		return new SmtpTransportException(
				SmtpTransportException.FailureCategory.SMTP_REJECTED,
				AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO, code, summary, true);
	}
}

abstract class CampaignDeliveryDatabaseTestSupport {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_delivery_test").withUsername("camel").withPassword("camel-test-only");
	static final Instant NOW = Instant.parse("2030-04-05T10:15:30Z");
	static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	static final UUID TEMPLATE = UUID.fromString("70000000-0000-0000-0000-000000000001");
	static final UUID TEMPLATE_VERSION = UUID.fromString("70100000-0000-0000-0000-000000000001");
	static final UUID SMTP = UUID.fromString("72000000-0000-0000-0000-000000000001");
	static final UUID MAILBOX = UUID.fromString("73000000-0000-0000-0000-000000000001");
	static final UUID PAPER = UUID.fromString("30000000-0000-0000-0000-000000000001");
	static final UUID AUTHOR = UUID.fromString("40000000-0000-0000-0000-000000000001");
	static final UUID PAPER_AUTHOR = UUID.fromString("41000000-0000-0000-0000-000000000001");
	static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(
			"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
	static final String HMAC_KEY = Base64.getEncoder().encodeToString(
			"fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
	static final CampaignDeliveryProperties DELIVERY_PROPERTIES = new CampaignDeliveryProperties(
			true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
			Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));

	static DatabaseClient databaseClient;
	static TransactionalOperator transactions;
	static ContactCrypto contactCrypto;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		ConnectionFactory connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
		transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
		contactCrypto = new ContactCrypto(new ContactDataProtectionProperties(ENCRYPTION_KEY, HMAC_KEY));
	}

	@BeforeEach
	void resetDatabase() {
		sql("""
				TRUNCATE campaign_safety_events, campaign_safety_links, campaign_safety_attempts,
				         campaign_safety_messages, campaign_safety_runs, delivery_attempts,
				         recipient_delivery_cooldowns, suppression_entries, unsubscribe_records,
				         campaign_exclusions, extraction_evidence, paper_author_contacts,
				         campaign_recipients, campaigns, extraction_runs, contacts, paper_authors,
				         authors, papers, arxiv_categories, arxiv_archives, arxiv_groups,
				         mailbox_accounts, smtp_accounts, email_template_versions, email_templates,
				         users CASCADE
				""");
		seedDependencies();
	}

	CampaignDeliveryRepository repository() {
		return new CampaignDeliveryRepository(
				databaseClient, transactions, DELIVERY_PROPERTIES,
				new CampaignSafetyProperties(false, "", 20));
	}

	CampaignDeliveryRepository safetyRepository(String recipient) {
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, recipient, 20);
		CampaignSafetySigner signer = new CampaignSafetySigner(HMAC_KEY);
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety,
				new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
						Duration.ofSeconds(2), Duration.ofSeconds(2), ""),
				new MailTrackingProperties(true, "https://tracking.example.test", HMAC_KEY,
						Duration.ofDays(30), Duration.ofMinutes(15)),
				signer, DELIVERY_PROPERTIES.leaseDuration());
		return new CampaignDeliveryRepository(
				databaseClient, transactions, DELIVERY_PROPERTIES, safety, policy);
	}

	UUID insertCampaign(String status) {
		UUID id = UUID.randomUUID();
			sql("""
					INSERT INTO campaigns (
					    id, name, purpose, status, template_id, template_version_id,
					    smtp_account_id, mailbox_account_id, from_name, from_email, reply_to,
					    unsubscribe_enabled, started_at, scheduled_at, created_by, updated_by
					) VALUES (
					    '%s', 'Delivery campaign', 'A concrete approved research purpose', '%s',
					    '%s', '%s', '%s', '%s', 'Approved Researcher', 'approved-snapshot@example.invalid',
					    'reply@example.invalid', true, TIMESTAMPTZ '%s',
					    CASE WHEN '%s' = 'SCHEDULED' THEN TIMESTAMPTZ '%s' ELSE NULL END, '%s', '%s'
					)
					""".formatted(id, status, TEMPLATE, TEMPLATE_VERSION, SMTP, MAILBOX, NOW,
							status, NOW, ACTOR, ACTOR));
		return id;
	}

	UUID insertEligibleRecipient(UUID campaignId, String email) {
		UUID contactId = UUID.randomUUID();
		UUID runId = databaseClient.sql("SELECT id FROM extraction_runs WHERE paper_id = :paper LIMIT 1")
				.bind("paper", PAPER).map((row, metadata) -> row.get(0, UUID.class)).one()
				.blockOptional().orElseGet(UUID::randomUUID);
		UUID mappingId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		String domain = email.substring(email.indexOf('@') + 1);
		ContactCrypto.EncryptedValue encrypted = contactCrypto.encrypt(email);
		byte[] hmac = contactCrypto.hmac(email);
		databaseClient.sql("""
				INSERT INTO contacts (
				    id, email_ciphertext, email_nonce, email_hmac, email_domain,
				    display_ciphertext, display_nonce, syntax_valid, example_address, suppression_status
				) VALUES (:id, :ciphertext, :nonce, :hmac, :domain, :ciphertext, :nonce, true, false, 'ACTIVE')
				""").bind("id", contactId).bind("ciphertext", encrypted.ciphertext())
				.bind("nonce", encrypted.nonce()).bind("hmac", hmac).bind("domain", domain)
				.fetch().rowsUpdated().block();
		if (count("extraction_runs") == 0) {
			sql("INSERT INTO extraction_runs (id, paper_id, parser_version, status, started_at, completed_at) "
					+ "VALUES ('" + runId + "', '" + PAPER + "', 'delivery-test', 'SUCCEEDED', TIMESTAMPTZ '"
					+ NOW.minusSeconds(10) + "', TIMESTAMPTZ '" + NOW.minusSeconds(5) + "')");
		}
		sql("INSERT INTO paper_author_contacts (id, paper_author_id, paper_id, contact_id, extraction_run_id, "
				+ "confidence, corresponding_author, human_verified, verification_status) VALUES ('" + mappingId + "', '"
				+ PAPER_AUTHOR + "', '" + PAPER + "', '" + contactId + "', '" + runId
				+ "', 'HIGH', true, true, 'CONFIRMED')");
		sql("INSERT INTO extraction_evidence (paper_author_contact_id, source_relative_path, rule_name, masked_context) "
				+ "VALUES ('" + mappingId + "', 'paper.tex', 'author-email', 'masked')");
		databaseClient.sql("""
				INSERT INTO campaign_recipients (
				    id, campaign_id, contact_id, paper_id, author_id, email_ciphertext, email_nonce,
				    email_hmac, email_domain, author_name_snapshot, paper_title_snapshot,
				    confidence, status, personalization_status, rendered_subject,
				    rendered_html, rendered_text, personalized_at, queued_at, next_attempt_at
				) VALUES (
				    :id, :campaignId, :contactId, :paperId, :authorId, :ciphertext, :nonce, :hmac,
				    :domain, 'Ada Lovelace', 'Safe Systems', 'HIGH', 'QUEUED', 'GENERATED',
				    'A research question', '<p>Personalized body</p><a href="{{unsubscribe_url}}">unsubscribe</a>',
				    'Personalized body {{unsubscribe_url}}', :now, :now, :now
				)
				""").bind("id", recipientId).bind("campaignId", campaignId).bind("contactId", contactId)
				.bind("paperId", PAPER).bind("authorId", AUTHOR).bind("ciphertext", encrypted.ciphertext())
				.bind("nonce", encrypted.nonce()).bind("hmac", hmac).bind("domain", domain).bind("now", NOW)
				.fetch().rowsUpdated().block();
		return recipientId;
	}

	void insertProductionReservation(String key, String domain, Instant startedAt, String status) {
		UUID campaignId = existingCampaign();
		UUID recipient = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO campaign_recipients (
				    id, campaign_id, email_ciphertext, email_nonce, email_hmac, email_domain,
				    confidence, status, personalization_status, personalized_at, next_attempt_at
				) VALUES (:id, :campaignId, decode('aa','hex'), decode('bb','hex'), digest(:key,'sha256'),
				          :domain, 'HIGH', 'SMTP_ACCEPTED', 'GENERATED', :startedAt, :startedAt)
				""").bind("id", recipient).bind("campaignId", campaignId).bind("key", key)
				.bind("domain", domain).bind("startedAt", startedAt).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO delivery_attempts (
				    campaign_recipient_id, smtp_account_id, attempt_number, idempotency_key,
				    status, started_at, completed_at
				) VALUES (:recipient, :smtp, 1, :key, :status, :startedAt, :startedAt)
				""").bind("recipient", recipient).bind("smtp", SMTP).bind("key", "attempt-" + key)
				.bind("status", status).bind("startedAt", startedAt).fetch().rowsUpdated().block();
	}

	void insertSafetyReservation(UUID sourceRecipient, Instant startedAt, String status) {
		UUID run = insertSafetyRun(sourceRecipient, "COMPLETED");
		UUID message = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO campaign_safety_messages (
				    id, run_id, campaign_recipient_id, smtp_account_id, status,
				    next_attempt_at, attempt_count, rfc_message_id
				) VALUES (:message, :run, :recipient, :smtp, :status, :startedAt, 1,
				          '<safety-fixture@delivery.camel-arxiv.invalid>')
				""").bind("message", message).bind("run", run).bind("recipient", sourceRecipient)
				.bind("smtp", SMTP).bind("status", status).bind("startedAt", startedAt)
				.fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO campaign_safety_attempts (
				    safety_message_id, attempt_number, idempotency_key, status,
				    rfc_message_id, started_at, completed_at
				) VALUES (:message, 1, :key, :status,
				          '<safety-fixture@delivery.camel-arxiv.invalid>', :startedAt, :startedAt)
				""").bind("message", message).bind("key", "safety:" + message + ":1")
				.bind("status", status).bind("startedAt", startedAt).fetch().rowsUpdated().block();
	}

	UUID insertSafetyRun(UUID sourceRecipient, String status) {
		UUID run = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO campaign_safety_runs (
				    id, campaign_id, smtp_account_id, created_by, recipient_limit,
				    destination_hmac, destination_masked, status,
				    from_name_snapshot, from_email_snapshot, reply_to_snapshot,
				    tracking_opens_enabled, tracking_clicks_enabled
				) SELECT :run, r.campaign_id, :smtp, :actor, 1, digest('safety@test.invalid','sha256'),
				         's***@test.invalid', :status, c.from_name, c.from_email, c.reply_to,
				         c.tracking_opens_enabled, c.tracking_clicks_enabled
				  FROM campaign_recipients r JOIN campaigns c ON c.id = r.campaign_id
				 WHERE r.id = :recipient
				""").bind("run", run).bind("smtp", SMTP).bind("actor", ACTOR).bind("status", status)
				.bind("recipient", sourceRecipient).fetch().rowsUpdated().block();
		return run;
	}

	UUID insertDueSafetyRun(UUID sourceRecipient, String destination) {
		UUID run = UUID.randomUUID();
		UUID message = UUID.randomUUID();
		CampaignSafetySigner signer = new CampaignSafetySigner(HMAC_KEY);
		CampaignSafetyProperties properties = new CampaignSafetyProperties(true, destination, 20);
		databaseClient.sql("""
				INSERT INTO campaign_safety_runs (
				    id, campaign_id, smtp_account_id, created_by, recipient_limit,
				    destination_hmac, destination_masked, status,
				    from_name_snapshot, from_email_snapshot, reply_to_snapshot,
				    tracking_opens_enabled, tracking_clicks_enabled
				) SELECT :run, campaign_id, :smtp, :actor, 1, :hmac, :masked, 'QUEUED',
				         c.from_name, c.from_email, c.reply_to,
				         c.tracking_opens_enabled, c.tracking_clicks_enabled
				  FROM campaign_recipients r JOIN campaigns c ON c.id = r.campaign_id
				 WHERE r.id = :recipient
				""").bind("run", run).bind("smtp", SMTP).bind("actor", ACTOR)
				.bind("hmac", signer.destinationHmac(properties.validatedRecipient()))
				.bind("masked", properties.maskedRecipient()).bind("recipient", sourceRecipient)
				.fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO campaign_safety_messages (
				    id, run_id, campaign_recipient_id, smtp_account_id, status,
				    next_attempt_at, rendered_subject, rendered_html, rendered_text
				) VALUES (:message, :run, :recipient, :smtp, 'QUEUED', :now,
				          'Safety fixture', '<p>Safety fixture</p>', 'Safety fixture')
				""").bind("message", message).bind("run", run).bind("recipient", sourceRecipient)
				.bind("smtp", SMTP).bind("now", NOW).fetch().rowsUpdated().block();
		return message;
	}

	UUID cloneRecipientToCampaign(UUID sourceRecipient, UUID campaignId) {
		UUID id = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO campaign_recipients (
				    id, campaign_id, contact_id, paper_id, author_id, email_ciphertext, email_nonce,
				    email_hmac, email_domain, author_name_snapshot, paper_title_snapshot,
				    confidence, status, personalization_status, rendered_subject,
				    rendered_html, rendered_text, personalized_at, queued_at, next_attempt_at
				)
				SELECT :id, :campaignId, contact_id, paper_id, author_id, email_ciphertext, email_nonce,
				       email_hmac, email_domain, author_name_snapshot, paper_title_snapshot,
				       confidence, 'QUEUED', personalization_status, rendered_subject,
				       rendered_html, rendered_text, personalized_at, queued_at, next_attempt_at
				FROM campaign_recipients WHERE id = :source
				""").bind("id", id).bind("campaignId", campaignId).bind("source", sourceRecipient)
				.fetch().rowsUpdated().block();
		return id;
	}

	void setLimits(int minute, int hour, int day, int domainHour) {
		sql("UPDATE smtp_accounts SET per_minute_limit = " + minute + ", per_hour_limit = " + hour
				+ ", per_day_limit = " + day + ", per_domain_hour_limit = " + domainHour + " WHERE id = '" + SMTP + "'");
	}

	RecipientState recipientState(UUID recipient) {
		return databaseClient.sql("SELECT status, exclusion_reason FROM campaign_recipients WHERE id = :id")
				.bind("id", recipient).map((row, metadata) -> new RecipientState(
						row.get("status", String.class), row.get("exclusion_reason", String.class)))
				.one().block();
	}

	record RecipientState(String status, String exclusionReason) { }

	void sql(String statement) {
		databaseClient.sql(statement).fetch().rowsUpdated().block();
	}

	long count(String table) {
		return databaseClient.sql("SELECT count(*) FROM " + table)
				.map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	String text(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	UUID uuid(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, UUID.class)).one().block();
	}

	int integer(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, Integer.class)).one().block();
	}

	boolean bool(String statement) {
		return Boolean.TRUE.equals(databaseClient.sql(statement)
				.map((row, metadata) -> row.get(0, Boolean.class)).one().block());
	}

	Instant instant(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, Instant.class)).one().block();
	}

	private UUID existingCampaign() {
		return databaseClient.sql("SELECT id FROM campaigns ORDER BY id LIMIT 1")
				.map((row, metadata) -> row.get(0, UUID.class)).one().block();
	}

	private void seedDependencies() {
		sql("INSERT INTO users (id, username, email, password_hash, display_name) VALUES ('" + ACTOR
				+ "', 'delivery-admin', 'admin@example.invalid', 'hash', 'Delivery Admin')");
		sql("INSERT INTO email_templates (id, name, status, created_by, updated_by) VALUES ('" + TEMPLATE
				+ "', 'Delivery template', 'ACTIVE', '" + ACTOR + "', '" + ACTOR + "')");
		sql("INSERT INTO email_template_versions (id, template_id, version_number, subject_template, "
				+ "from_name_template, reply_to, html_content, text_content, content_size_bytes, created_by) VALUES ('"
				+ TEMPLATE_VERSION + "', '" + TEMPLATE + "', 1, 'Subject', 'Researcher', 'reply@example.invalid', "
				+ "'<p>Body</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>', 'Body {{unsubscribe_url}}', 80, '" + ACTOR + "')");
		sql("INSERT INTO smtp_accounts (id, name, host, port, tls_mode, from_email, default_from_name, reply_to, "
				+ "per_minute_limit, per_hour_limit, per_day_limit, per_domain_hour_limit, enabled, last_tested_at, "
				+ "last_test_status, created_by, updated_at) VALUES ('" + SMTP + "', 'Delivery SMTP', 'localhost', 1025, "
				+ "'PLAIN_LOCAL_ONLY', 'sender@example.invalid', 'Researcher', 'reply@example.invalid', 100, 100, 1000, 100, "
				+ "true, TIMESTAMPTZ '" + NOW.minusSeconds(30) + "', 'SUCCEEDED', '" + ACTOR + "', TIMESTAMPTZ '"
				+ NOW.minusSeconds(60) + "')");
		sql("INSERT INTO mailbox_accounts (id, name, protocol, host, port, tls_mode, username, password_ciphertext, "
				+ "password_nonce, folder_name, enabled, last_tested_at, last_test_status, created_by, updated_by) VALUES ('"
				+ MAILBOX + "', 'Delivery mailbox', 'IMAP', 'localhost', 1143, 'PLAIN_LOCAL_ONLY', 'mailbox-user', "
				+ "decode('00112233445566778899aabbccddeeff','hex'), decode('00112233445566778899aabb','hex'), 'INBOX', true, "
				+ "TIMESTAMPTZ '" + NOW.minusSeconds(30) + "', 'SUCCEEDED', '" + ACTOR + "', '" + ACTOR + "')");
		sql("INSERT INTO arxiv_groups (id, group_id, group_name) VALUES ('20000000-0000-0000-0000-000000000001', 'cs', 'CS')");
		sql("INSERT INTO arxiv_categories (id, group_ref_id, group_id, group_name, category_id, category_name) VALUES "
				+ "('21000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', "
				+ "'cs', 'CS', 'cs.AI', 'AI')");
		sql("INSERT INTO papers (id, arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at, pdf_url) "
				+ "VALUES ('" + PAPER + "', '3000.00001', 'Safe Systems', 'Abstract', "
				+ "'21000000-0000-0000-0000-000000000001', TIMESTAMPTZ '" + NOW.minus(Duration.ofDays(1))
				+ "', TIMESTAMPTZ '" + NOW + "', 'https://arxiv.org/pdf/3000.00001')");
		sql("INSERT INTO authors (id, normalized_name, display_name) VALUES ('" + AUTHOR + "', 'ada', 'Ada Lovelace')");
		sql("INSERT INTO paper_authors (id, paper_id, author_id, author_order, corresponding_author, raw_name) VALUES ('"
				+ PAPER_AUTHOR + "', '" + PAPER + "', '" + AUTHOR + "', 1, true, 'Ada Lovelace')");
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
