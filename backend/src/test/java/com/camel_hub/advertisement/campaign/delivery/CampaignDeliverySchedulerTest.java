package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CampaignDeliverySchedulerTest extends CampaignDeliveryDatabaseTestSupport {
	private CampaignDeliveryRepository repository;

	@BeforeEach
	void createRepository() {
		repository = repository();
	}

	@Test
	void scheduledCampaignStartsOnlyAtItsBoundary() {
		UUID campaignId = insertCampaign("SCHEDULED");
		java.time.Instant boundary = NOW.plus(Duration.ofMinutes(5));
		sql("UPDATE campaigns SET scheduled_at = TIMESTAMPTZ '" + boundary + "' WHERE id = '" + campaignId + "'");

		// PostgreSQL timestamptz is microsecond-precision; exercise the closest persisted instant.
		assertThat(repository.activateDueCampaigns(boundary.minusNanos(1_000)).block()).isZero();
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo("SCHEDULED");
		assertThat(repository.activateDueCampaigns(boundary).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo("RUNNING");
	}

	@Test
	void pollPromotesDueWorkPumpsAndReconcilesLeaseCancellationAndCompletion() {
		UUID scheduled = insertCampaign("SCHEDULED");
		UUID scheduledRecipient = insertEligibleRecipient(scheduled, "scheduled-pump@research.test");
		sql("UPDATE campaigns SET scheduled_at = TIMESTAMPTZ '" + NOW.minusSeconds(1)
				+ "' WHERE id = '" + scheduled + "'");

		UUID canceled = insertCampaign("CANCELED");
		UUID canceledRecipient = insertEligibleRecipient(canceled, "canceled-pump@research.test");

		UUID expiredCampaign = insertCampaign("RUNNING");
		UUID expiredRecipient = insertEligibleRecipient(expiredCampaign, "expired-pump@research.test");
		sql("UPDATE campaign_recipients SET next_attempt_at = TIMESTAMPTZ '" + NOW.minus(Duration.ofMinutes(5))
				+ "' WHERE id = '" + expiredRecipient + "'");
		CampaignDeliveryRepository.ProductionClaim expired = repository
				.claimNext(NOW.minus(Duration.ofMinutes(3))).block();
		assertThat(expired).isNotNull();

		AtomicInteger sends = new AtomicInteger();
		CampaignOutboundPreparer preparer = claim -> Mono.just(
				new CampaignOutboundPreparer.PreparedOutbound(
						claim.renderedSubject(),
						claim.renderedHtml().replace("{{unsubscribe_url}}", "https://tracking.example.test/u/opaque"),
						claim.renderedText().replace("{{unsubscribe_url}}", "https://tracking.example.test/u/opaque"),
						Map.of(
								"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
								"List-Unsubscribe-Post", "List-Unsubscribe=One-Click")));
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				repository, preparer, contactCrypto, (account, message) -> {
					sends.incrementAndGet();
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryScheduler scheduler = new CampaignDeliveryScheduler(
				repository, executor, DELIVERY_PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

		CampaignDeliveryScheduler.SchedulerRun result = scheduler.runOnce().block();

		assertThat(result.pumped()).isEqualTo(1);
		assertThat(sends).hasValue(1);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + scheduledRecipient + "'"))
				.isEqualTo("SMTP_ACCEPTED");
		assertThat(text("SELECT status FROM campaigns WHERE id = '" + scheduled + "'"))
				.isEqualTo("COMPLETED");
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + canceledRecipient + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + expiredRecipient + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
	}

	@Test
	void campaignCompletionTimestampIsNeverOlderThanTheLastSmtpSettlement() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "slow-settlement@research.test");
		InstantHolder clock = new InstantHolder(NOW);
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				repository, validPreparer(), contactCrypto, (account, message) -> {
					clock.now.set(NOW.plus(Duration.ofMinutes(2)));
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, clock);
		CampaignDeliveryScheduler scheduler = new CampaignDeliveryScheduler(
				repository, executor, DELIVERY_PROPERTIES, clock);

		scheduler.runOnce().block();

		assertThat(instant("SELECT completed_at FROM campaigns WHERE id = '" + campaignId + "'"))
				.isEqualTo(instant("SELECT smtp_accepted_at FROM campaign_recipients WHERE id = '"
						+ recipientId + "'"))
				.isEqualTo(NOW.plus(Duration.ofMinutes(2)));
	}

	@Test
	void scheduledInfrastructureFailureIsReportedBySafeCategoryInsteadOfSilentlyDropped() throws Exception {
		CampaignDeliveryRepository failedRepository = mock(CampaignDeliveryRepository.class);
		CampaignDeliveryExecutor unusedExecutor = mock(CampaignDeliveryExecutor.class);
		when(failedRepository.activateDueCampaigns(NOW))
				.thenReturn(Mono.error(new IllegalStateException("password=must-never-be-logged")));
		AtomicReference<String> reported = new AtomicReference<>();
		CountDownLatch observed = new CountDownLatch(1);
		CampaignDeliveryScheduler scheduler = new CampaignDeliveryScheduler(
				failedRepository, unusedExecutor, DELIVERY_PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC),
				category -> {
					reported.set(category);
					observed.countDown();
				});

		scheduler.tick();

		assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(reported).hasValue("IllegalStateException");
		assertThat(reported.get()).doesNotContain("password", "must-never-be-logged");
	}

	private CampaignOutboundPreparer validPreparer() {
		return claim -> Mono.just(new CampaignOutboundPreparer.PreparedOutbound(
				claim.renderedSubject(),
				claim.renderedHtml().replace("{{unsubscribe_url}}", "https://tracking.example.test/u/opaque"),
				claim.renderedText().replace("{{unsubscribe_url}}", "https://tracking.example.test/u/opaque"),
				Map.of(
						"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
						"List-Unsubscribe-Post", "List-Unsubscribe=One-Click")));
	}

	private static final class InstantHolder extends Clock {
		private final AtomicReference<java.time.Instant> now;

		private InstantHolder(java.time.Instant now) {
			this.now = new AtomicReference<>(now);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public java.time.Instant instant() {
			return now.get();
		}
	}
}
