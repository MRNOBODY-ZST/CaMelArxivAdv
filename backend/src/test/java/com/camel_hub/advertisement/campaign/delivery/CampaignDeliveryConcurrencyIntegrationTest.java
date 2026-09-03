package com.camel_hub.advertisement.campaign.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignDeliveryConcurrencyIntegrationTest extends CampaignDeliveryDatabaseTestSupport {

	private CampaignDeliveryRepository firstRepository;
	private CampaignDeliveryRepository secondRepository;

	@BeforeEach
	void createRepositories() {
		firstRepository = repository();
		secondRepository = repository();
	}

	@Test
	void twoWorkersCanNeverClaimTheSameRecipient() throws Exception {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "one-worker@research.test");

		List<CampaignDeliveryRepository.ProductionClaim> claims = concurrentClaims();

		assertThat(claims).filteredOn(java.util.Objects::nonNull).singleElement()
				.extracting(CampaignDeliveryRepository.ProductionClaim::recipientId).isEqualTo(recipientId);
		assertThat(count("delivery_attempts")).isEqualTo(1);
		assertThat(integer("SELECT attempt_count FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo(1);
	}

	@Test
	void smtpAccountLockSerializesConcurrentCapacityReservations() throws Exception {
		setLimits(1, 100, 1000, 100);
		UUID campaignId = insertCampaign("RUNNING");
		insertEligibleRecipient(campaignId, "first@research.test");
		insertEligibleRecipient(campaignId, "second@research.test");

		List<CampaignDeliveryRepository.ProductionClaim> claims = concurrentClaims();

		assertThat(claims).filteredOn(java.util.Objects::nonNull).hasSize(1);
		assertThat(count("delivery_attempts")).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_recipients WHERE status = 'CONNECTING'"))
				.isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_recipients WHERE status = 'QUEUED' "
				+ "AND next_attempt_at = TIMESTAMPTZ '" + NOW.plus(Duration.ofMinutes(1)) + "'"))
				.isEqualTo(1);
	}

	@ParameterizedTest(name = "concurrent {0} window")
	@MethodSource("rollingConcurrencyCases")
	void everyRollingWindowSerializesAtItsConfiguredLimit(
			String label, int minute, int hour, int day, int domainHour,
			int history, Duration age, String historyDomain
	) throws Exception {
		setLimits(minute, hour, day, domainHour);
		UUID campaignId = insertCampaign("RUNNING");
		insertEligibleRecipient(campaignId, "first-limit@research.test");
		insertEligibleRecipient(campaignId, "second-limit@research.test");
		for (int index = 0; index < history; index++) {
			insertProductionReservation(label + "-" + index, historyDomain,
					NOW.minus(age).plusMillis(index), "SMTP_ACCEPTED");
		}

		List<CampaignDeliveryRepository.ProductionClaim> claims = concurrentClaims();

		assertThat(claims).filteredOn(java.util.Objects::nonNull).hasSize(1);
		assertThat(count("delivery_attempts")).isEqualTo(history + 1L);
	}

	private static Stream<Arguments> rollingConcurrencyCases() {
		return Stream.of(
				Arguments.of("2/minute", 2, 100, 1000, 100, 1,
						Duration.ofSeconds(30), "other.test"),
				Arguments.of("10/hour", 100, 10, 1000, 100, 9,
						Duration.ofMinutes(30), "other.test"),
				Arguments.of("30/day", 100, 100, 30, 100, 29,
						Duration.ofHours(12), "other.test"),
				Arguments.of("10/domain-hour", 100, 100, 1000, 10, 9,
						Duration.ofMinutes(30), "research.test"));
	}

	@Test
	void elevenSameDomainWorkersCanReserveOnlyTenSlots() throws Exception {
		setLimits(100, 100, 1000, 10);
		UUID campaignId = insertCampaign("RUNNING");
		IntStream.range(0, 11).forEach(index ->
				insertEligibleRecipient(campaignId, "domain-worker-" + index + "@research.test"));
		List<CampaignDeliveryRepository> repositories = IntStream.range(0, 11)
				.mapToObj(ignored -> repository()).toList();

		List<CampaignDeliveryRepository.ProductionClaim> claims = concurrentClaims(repositories);

		assertThat(claims).filteredOn(java.util.Objects::nonNull).hasSize(10);
		assertThat(count("delivery_attempts")).isEqualTo(10);
	}

	@Test
	void conflictSafeCooldownSentinelPreventsSameEmailAcrossCampaignsFromDoubleClaiming() throws Exception {
		UUID firstCampaign = insertCampaign("RUNNING");
		UUID firstRecipient = insertEligibleRecipient(firstCampaign, "same@research.test");
		UUID secondCampaign = insertCampaign("RUNNING");
		UUID secondRecipient = cloneRecipientToCampaign(firstRecipient, secondCampaign);

		List<CampaignDeliveryRepository.ProductionClaim> claims = concurrentClaims();

		assertThat(claims).filteredOn(java.util.Objects::nonNull).hasSize(1);
		assertThat(count("delivery_attempts")).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM recipient_delivery_cooldowns")).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_recipients WHERE id IN ('" + firstRecipient
				+ "','" + secondRecipient + "') AND status = 'CONNECTING'"))
				.isEqualTo(1);
	}

	@Test
	void lateCompletionCannotOverwriteExpiredLeaseReconciliation() {
		UUID campaignId = insertCampaign("RUNNING");
		insertEligibleRecipient(campaignId, "late@research.test");
		CampaignDeliveryRepository.ProductionClaim claim = firstRepository.claimNext(NOW).block();
		firstRepository.reconcileExpiredLeases(NOW.plus(DELIVERY_PROPERTIES.leaseDuration()).plusSeconds(1)).block();

		boolean applied = firstRepository.completeAccepted(
				claim.recipientId(), claim.attemptId(), claim.leaseDigest(),
				new com.camel_hub.advertisement.email.smtp.SmtpTransport.SmtpOutcome(
						CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
						CampaignDeliveryModels.TransportStage.POST_DATA, 250, "250 queued"),
				NOW.plus(DELIVERY_PROPERTIES.leaseDuration()).plusSeconds(2)).block();

		assertThat(applied).isFalse();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
		assertThat(text("SELECT status FROM delivery_attempts WHERE id = '" + claim.attemptId() + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
	}

	@Test
	void activeSafetyRunTemporarilyExcludesProductionClaim() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "safety-overlap@research.test");
		insertSafetyRun(recipientId, "RUNNING");

		assertThat(firstRepository.claimNext(NOW).block()).isNull();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("QUEUED");
		assertThat(count("delivery_attempts")).isZero();
	}

	@Test
	void finalReservationRechecksSuppressionInsertedWhileClaimWaitsOnAccountLock() throws Exception {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "eligibility-race@research.test");
		try (Connection gate = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			gate.setAutoCommit(false);
			try (var statement = gate.createStatement()) {
				statement.execute("SELECT id FROM smtp_accounts WHERE id = '" + SMTP + "' FOR UPDATE");
			}
			CompletableFuture<CampaignDeliveryRepository.ProductionClaim> claim = firstRepository
					.claimNext(NOW).subscribeOn(Schedulers.boundedElastic()).toFuture();
			assertThat(awaitBlockedClaim()).isTrue();
			sql("INSERT INTO suppression_entries (email_hmac, email_domain, reason, source) "
					+ "SELECT email_hmac, email_domain, 'MANUAL', 'RACE_TEST' FROM campaign_recipients WHERE id = '"
					+ recipientId + "'");
			gate.commit();

			assertThat(claim.get(10, TimeUnit.SECONDS)).isNull();
		}
		assertThat(count("delivery_attempts")).isZero();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("SUPPRESSED");
	}

	@Test
	void pauseThatWinsTheCampaignLockPreventsAConcurrentClaim() throws Exception {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "pause-race@research.test");
		CampaignDeliveryRepository.ProductionClaim claim;
		try (Connection control = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			control.setAutoCommit(false);
			try (var statement = control.createStatement()) {
				statement.execute("UPDATE campaigns SET status = 'PAUSED' WHERE id = '" + campaignId + "'");
			}
			claim = firstRepository.claimNext(NOW).block(Duration.ofSeconds(5));
			control.commit();
		}

		assertThat(claim).isNull();
		assertThat(count("delivery_attempts")).isZero();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("QUEUED");
	}

	@Test
	void controlLockAlsoPreventsConcurrentEligibilityReconciliation() throws Exception {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "reconcile-race@research.test");
		sql("UPDATE paper_author_contacts SET human_verified = false WHERE contact_id = "
				+ "(SELECT contact_id FROM campaign_recipients WHERE id = '" + recipientId + "')");
		Integer reconciled;
		try (Connection control = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			control.setAutoCommit(false);
			try (var statement = control.createStatement()) {
				statement.execute("UPDATE campaigns SET status = 'PAUSED' WHERE id = '" + campaignId + "'");
			}
			reconciled = firstRepository.reconcileUndeliverable(NOW).block(Duration.ofSeconds(5));
			control.commit();
		}

		assertThat(reconciled).isZero();
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("QUEUED");
	}

	@Test
	void claimAndEligibilityReconciliationSettleWithoutLockOrderDeadlock() throws Exception {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "claim-reconcile@research.test");
		sql("UPDATE paper_author_contacts SET human_verified = false WHERE contact_id = "
				+ "(SELECT contact_id FROM campaign_recipients WHERE id = '" + recipientId + "')");
		CountDownLatch subscribed = new CountDownLatch(2);
		CompletableFuture<Void> release = new CompletableFuture<>();
		CompletableFuture<CampaignDeliveryRepository.ProductionClaim> claim = reactor.core.publisher.Mono.defer(() -> {
			subscribed.countDown();
			return reactor.core.publisher.Mono.fromFuture(release).then(firstRepository.claimNext(NOW));
		}).subscribeOn(Schedulers.boundedElastic()).toFuture();
		CompletableFuture<Integer> reconciliation = reactor.core.publisher.Mono.defer(() -> {
			subscribed.countDown();
			return reactor.core.publisher.Mono.fromFuture(release)
					.then(secondRepository.reconcileUndeliverable(NOW));
		}).subscribeOn(Schedulers.boundedElastic()).toFuture();
		assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

		release.complete(null);

		assertThat(claim.get(10, TimeUnit.SECONDS)).isNull();
		assertThat(reconciliation.get(10, TimeUnit.SECONDS)).isBetween(0, 1);
		assertThat(recipientState(recipientId)).isEqualTo(new RecipientState("SUPPRESSED", "EVIDENCE_INVALID"));
		assertThat(count("delivery_attempts")).isZero();
	}

	private boolean awaitBlockedClaim() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			try (Connection observer = DriverManager.getConnection(
					POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				 var statement = observer.createStatement();
				 var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
						 + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
				rows.next();
				if (rows.getInt(1) > 0) return true;
			}
			Thread.sleep(20);
		}
		return false;
	}

	private List<CampaignDeliveryRepository.ProductionClaim> concurrentClaims() throws Exception {
		return concurrentClaims(List.of(firstRepository, secondRepository));
	}

	private List<CampaignDeliveryRepository.ProductionClaim> concurrentClaims(
			List<CampaignDeliveryRepository> repositories
	) throws Exception {
		CountDownLatch subscribed = new CountDownLatch(repositories.size());
		CompletableFuture<Void> release = new CompletableFuture<>();
		List<CompletableFuture<CampaignDeliveryRepository.ProductionClaim>> futures = repositories.stream()
				.map(repository -> reactor.core.publisher.Mono.defer(() -> {
					subscribed.countDown();
					return reactor.core.publisher.Mono.fromFuture(release).then(repository.claimNext(NOW));
				}).subscribeOn(Schedulers.boundedElastic()).toFuture())
				.toList();
		assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
		release.complete(null);
		CampaignDeliveryRepository.ProductionClaim[] claims = new CampaignDeliveryRepository.ProductionClaim[futures.size()];
		for (int index = 0; index < futures.size(); index++) {
			claims[index] = futures.get(index).get(20, TimeUnit.SECONDS);
		}
		return Arrays.asList(claims);
	}
}
