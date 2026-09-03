package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.CampaignReportingRepository;
import com.camel_hub.advertisement.campaign.tracking.CampaignTrackingRepository;
import com.camel_hub.advertisement.campaign.tracking.CampaignTrackingService;
import com.camel_hub.advertisement.campaign.tracking.CampaignTrackingSigner;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import com.camel_hub.advertisement.email.smtp.SmtpPolicy;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import static com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignTrackingDeliveryRetryIntegrationTest extends CampaignDeliveryDatabaseTestSupport {

	private static final String TRACKING_KEY = Base64.getEncoder().encodeToString(
			"delivery-retry-tracking-key-32bytes".getBytes(StandardCharsets.UTF_8));

	@Test
	void realExecutorReusesFrozenBodiesCapabilitiesAndRfc8058HeadersAfterA450Retry() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingService tracking = new CampaignTrackingService(
				new CampaignTrackingRepository(databaseClient),
				new MailTrackingProperties(true, "https://tracking.example.test.", TRACKING_KEY, Duration.ofDays(30)),
				new CampaignTrackingSigner(TRACKING_KEY), new MailOpenClassifier(), clock, transactions);
		List<SmtpTransport.OutboundMessage> attempts = new ArrayList<>();
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking, contactCrypto, (account, message) -> {
					attempts.add(message);
					if (sends.getAndIncrement() == 0) {
						throw new SmtpTransportException(
								SmtpTransportException.FailureCategory.SMTP_REJECTED,
								AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
								450, "450 retry later", true);
					}
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, clock);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		String firstFrozenHtml = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ fixture.recipientId() + "'");
		String firstFrozenText = text("SELECT rendered_text FROM campaign_recipients WHERE id = '"
				+ fixture.recipientId() + "'");
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);

		clock.set(NOW.plus(Duration.ofMinutes(1)));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);

		assertThat(attempts).hasSize(2);
		SmtpTransport.OutboundMessage first = attempts.get(0);
		SmtpTransport.OutboundMessage retry = attempts.get(1);
		assertThat(retry.subject()).isEqualTo(first.subject());
		assertThat(retry.html()).isEqualTo(first.html()).isEqualTo(firstFrozenHtml);
		assertThat(retry.text()).isEqualTo(first.text()).isEqualTo(firstFrozenText);
		assertThat(retry.headers()).isEqualTo(first.headers())
				.containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
				.containsKey("List-Unsubscribe");
		assertThat(first.html()).contains("https://tracking.example.test/")
				.doesNotContain("tracking.example.test./");
		assertThat(retry.rfcMessageId()).isEqualTo(first.rfcMessageId());
		assertThat(retry.correlationId()).isEqualTo(first.correlationId());
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
		assertThat(count("delivery_attempts")).isEqualTo(2);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + fixture.recipientId() + "'"))
				.isEqualTo("SMTP_ACCEPTED");
	}

	@Test
	void frozenRetryThatCrossesItsLeaseFenceNeverReachesSmtpOrCreatesNewCapabilities() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		CampaignTrackingService initialTracking = tracking(
				Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(13));
		CampaignDeliveryRepository.ProductionClaim first = delivery.claimNextProduction(NOW).block();
		initialTracking.prepare(first).block();
		delivery.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
						450, "450 retry later", true), NOW).block();
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
		String frozenBefore = frozenSnapshot(fixture.recipientId());

		Instant retryAt = NOW.plus(Duration.ofDays(1));
		SequencedClock expiresDuringFrozenValidation = new SequencedClock(
				retryAt, retryAt, retryAt.plus(DELIVERY_PROPERTIES.leaseDuration()).plusSeconds(1));
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking(expiresDuringFrozenValidation, Duration.ofMinutes(13)),
				contactCrypto, (account, message) -> {
					sends.incrementAndGet();
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, expiresDuringFrozenValidation);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(expiresDuringFrozenValidation.calls()).isGreaterThanOrEqualTo(3);
		assertThat(sends).hasValue(0);
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
		assertThat(frozenSnapshot(fixture.recipientId())).isEqualTo(frozenBefore);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + fixture.recipientId() + "'"))
				.isEqualTo("PERMANENT_FAILURE");
	}

	@Test
	void explicit450RetryRotatesCapabilitiesBeforeTheyCanExpireInsideItsActiveLease() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		Duration ttl = Duration.ofMinutes(13);
		CampaignTrackingSigner signer = new CampaignTrackingSigner(TRACKING_KEY);
		CampaignTrackingService initialTracking = tracking(Clock.fixed(NOW, ZoneOffset.UTC), ttl);
		CampaignDeliveryRepository.ProductionClaim first = delivery.claimNextProduction(NOW).block();
		CampaignOutboundPreparer.PreparedOutbound initial = initialTracking.prepare(first).block();
		Capabilities old = capabilities(initial.html());
		delivery.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
						450, "450 retry later", true), NOW).block();

		Instant expiresAt = NOW.plus(ttl);
		Instant retryAt = expiresAt.minusMillis(1);
		SequencedClock clock = new SequencedClock(retryAt, retryAt, expiresAt, expiresAt);
		AtomicReference<SmtpTransport.OutboundMessage> sent = new AtomicReference<>();
		CampaignTrackingService retryTracking = tracking(clock, ttl);
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, retryTracking, contactCrypto, (account, message) -> {
					sent.set(message);
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, clock);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		Capabilities fresh = capabilities(sent.get());
		assertThat(fresh).isNotEqualTo(old);
		assertThat(signer.verifyOpen(old.open(), expiresAt)).isEmpty();
		assertThat(signer.verifyClick(old.click(), expiresAt)).isEmpty();
		assertThat(signer.verifyUnsubscribe(old.unsubscribe(), expiresAt)).isEmpty();
		assertThat(signer.verifyOpen(fresh.open(), expiresAt)).isPresent();
		assertThat(signer.verifyClick(fresh.click(), expiresAt)).isPresent();
		assertThat(signer.verifyUnsubscribe(fresh.unsubscribe(), expiresAt)).isPresent();
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
	}

	@Test
	void nearExpiryFrozenCapabilitiesFailClosedWithoutExplicitSmtpRejectedProvenance() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		Duration ttl = Duration.ofMinutes(13);
		CampaignTrackingService initialTracking = tracking(Clock.fixed(NOW, ZoneOffset.UTC), ttl);
		CampaignDeliveryRepository.ProductionClaim first = delivery.claimNextProduction(NOW).block();
		initialTracking.prepare(first).block();
		delivery.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
						450, "450 retry later", true), NOW).block();
		databaseClient.sql("UPDATE delivery_attempts SET failure_category = 'CONNECTION_TIMEOUT' WHERE id = :attempt")
				.bind("attempt", first.attemptId()).fetch().rowsUpdated().block();
		String before = frozenSnapshot(fixture.recipientId());

		Instant expiresAt = NOW.plus(ttl);
		Instant retryAt = expiresAt.minusMillis(1);
		SequencedClock clock = new SequencedClock(retryAt, retryAt, expiresAt, expiresAt);
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking(clock, ttl), contactCrypto, (account, message) -> {
					sends.incrementAndGet();
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, clock);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(sends).hasValue(0);
		assertThat(frozenSnapshot(fixture.recipientId())).isEqualTo(before);
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + fixture.recipientId() + "'"))
				.isEqualTo("PERMANENT_FAILURE");
	}

	@Test
	void realExecutorKeepsFrozenContentValidAtTheThirdAttemptRetryHorizon() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingService tracking = new CampaignTrackingService(
				new CampaignTrackingRepository(databaseClient),
				new MailTrackingProperties(true, "https://tracking.example.test", TRACKING_KEY,
						Duration.ofMinutes(13)),
				new CampaignTrackingSigner(TRACKING_KEY), new MailOpenClassifier(), clock, transactions);
		List<SmtpTransport.OutboundMessage> attempts = new ArrayList<>();
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking, contactCrypto, (account, message) -> {
					attempts.add(message);
					if (sends.getAndIncrement() < 2) {
						clock.set(clock.instant().plusSeconds(119));
						throw new SmtpTransportException(
								SmtpTransportException.FailureCategory.SMTP_REJECTED,
								AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
								450, "450 retry later", true);
					}
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, clock);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		clock.set(NOW.plusSeconds(179));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		clock.set(NOW.plusSeconds(598));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);

		assertThat(attempts).hasSize(3);
		assertThat(attempts.get(1).subject()).isEqualTo(attempts.get(0).subject());
		assertThat(attempts.get(1).html()).isEqualTo(attempts.get(0).html());
		assertThat(attempts.get(1).text()).isEqualTo(attempts.get(0).text());
		assertThat(attempts.get(1).headers()).isEqualTo(attempts.get(0).headers());
		assertThat(attempts.get(2).subject()).isEqualTo(attempts.get(0).subject());
		assertThat(attempts.get(2).html()).isEqualTo(attempts.get(0).html());
		assertThat(attempts.get(2).text()).isEqualTo(attempts.get(0).text());
		assertThat(attempts.get(2).headers()).isEqualTo(attempts.get(0).headers());
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
		assertThat(count("delivery_attempts")).isEqualTo(3);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + fixture.recipientId() + "'"))
				.isEqualTo("SMTP_ACCEPTED");
	}

	@Test
	void explicit450RetriesRotateOnlyExpiredFrozenCapabilitiesAndPreserveTheEnvelopeAndTargets() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingSigner signer = new CampaignTrackingSigner(TRACKING_KEY);
		CampaignTrackingService tracking = new CampaignTrackingService(
				new CampaignTrackingRepository(databaseClient),
				new MailTrackingProperties(true, "https://tracking.example.test", TRACKING_KEY,
						Duration.ofMinutes(13)),
				signer, new MailOpenClassifier(), clock, transactions);
		List<SmtpTransport.OutboundMessage> attempts = new ArrayList<>();
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking, contactCrypto, (account, message) -> {
					attempts.add(message);
					if (sends.getAndIncrement() < 2) {
						throw new SmtpTransportException(
								SmtpTransportException.FailureCategory.SMTP_REJECTED,
								AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
								450, "450 retry later", true);
					}
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, clock);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		Capabilities first = capabilities(attempts.getFirst());
		clock.set(NOW.plus(Duration.ofDays(1)));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		Capabilities second = capabilities(attempts.get(1));
		clock.set(NOW.plus(Duration.ofDays(2)));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		Capabilities third = capabilities(attempts.get(2));

		assertThat(attempts).hasSize(3);
		assertThat(second).isNotEqualTo(first);
		assertThat(third).isNotEqualTo(second).isNotEqualTo(first);
		assertThat(attempts).extracting(SmtpTransport.OutboundMessage::subject).containsOnly("A research question");
		assertThat(attempts).extracting(SmtpTransport.OutboundMessage::rfcMessageId).containsOnly(
				attempts.getFirst().rfcMessageId());
		assertThat(attempts).extracting(SmtpTransport.OutboundMessage::correlationId).containsOnly(
				attempts.getFirst().correlationId());
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
		assertThat(text("SELECT target_url FROM campaign_links")).isEqualTo("https://papers.example.org/abs/42");
		for (String old : List.of(first.open(), first.click(), first.unsubscribe(),
				second.open(), second.click(), second.unsubscribe())) {
			assertThat(databaseClient.sql("SELECT count(*) FROM tracking_tokens WHERE token_hash = :hash")
					.bind("hash", signer.digest(old)).map((row, metadata) -> row.get(0, Long.class)).one().block())
					.isZero();
		}

		AuthenticationRequestContext request = new AuthenticationRequestContext(
				"198.51.100.20", "Research Browser", "rotated-callbacks");
		assertThat(tracking.observeOpen(first.open(), new HttpHeaders(), request).block()).isFalse();
		assertThat(tracking.click(first.click(), new HttpHeaders(), request, true).block()).isNull();
		assertThat(tracking.unsubscribe(first.unsubscribe(), request).block()).isFalse();
		assertThat(tracking.observeOpen(third.open(), new HttpHeaders(), request).block()).isTrue();
		assertThat(tracking.click(third.click(), new HttpHeaders(), request, true).block().targetUrl())
				.isEqualTo("https://papers.example.org/abs/42");
		assertThat(tracking.unsubscribe(third.unsubscribe(), request).block()).isTrue();
	}

	@ParameterizedTest(name = "expired frozen capability rejects prior status={0}, retryable={1}, code={2}, category={3}")
	@CsvSource({
			"TEMPORARY_FAILURE, false, 450, SMTP_REJECTED",
			"TEMPORARY_FAILURE, true, 550, SMTP_REJECTED",
			"SMTP_ACCEPTED, true, 450, SMTP_REJECTED",
			"OUTCOME_UNKNOWN, true, 450, SMTP_REJECTED",
			"PERMANENT_FAILURE, true, 450, SMTP_REJECTED",
			"TEMPORARY_FAILURE, true, 450, CONNECTION_TIMEOUT"
	})
	void expiredFrozenCapabilitiesNeverRotateWithoutImmediateExplicitRetryableFourHundredProvenance(
			String previousStatus, boolean previousRetryable, int previousCode, String previousCategory
	) {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingService tracking = tracking(clock, Duration.ofMinutes(13));
		CampaignDeliveryRepository.ProductionClaim first = delivery.claimNextProduction(NOW).block();
		tracking.prepare(first).block();
		delivery.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
						450, "450 retry later", true), NOW).block();
		databaseClient.sql("""
				UPDATE delivery_attempts SET status = :status, retryable = :retryable,
				    smtp_response_code = :code, failure_category = :category WHERE id = :attempt
				""").bind("status", previousStatus).bind("retryable", previousRetryable)
				.bind("code", previousCode).bind("category", previousCategory)
				.bind("attempt", first.attemptId()).fetch().rowsUpdated().block();
		String before = frozenSnapshot(fixture.recipientId());
		clock.set(NOW.plus(Duration.ofDays(1)));
		CampaignDeliveryRepository.ProductionClaim retry = delivery.claimNextProduction(clock.instant()).block();

		assertThatThrownBy(() -> tracking.prepare(retry).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Frozen campaign capabilities are invalid");
		assertThat(frozenSnapshot(fixture.recipientId())).isEqualTo(before);
		assertThat(count("tracking_tokens")).isEqualTo(3);
	}

	@Test
	void expiredRetryRejectsMixedCapabilityExpiriesEvenWhenEveryTokenAndDigestIsOtherwiseValid() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingSigner signer = new CampaignTrackingSigner(TRACKING_KEY);
		CampaignTrackingService tracking = tracking(clock, Duration.ofMinutes(13));
		CampaignDeliveryRepository.ProductionClaim first = delivery.claimNextProduction(NOW).block();
		CampaignOutboundPreparer.PreparedOutbound prepared = tracking.prepare(first).block();
		Capabilities original = capabilities(new SmtpTransport.OutboundMessage(
				"recipient@example.invalid", prepared.subject(), "Researcher", "reply@example.invalid",
				prepared.html(), prepared.text(), first.correlationId()));
		Instant differentExpiry = NOW.plus(Duration.ofDays(2));
		String differentOpen = signer.issueOpen(first.recipientId(), differentExpiry);
		databaseClient.sql("""
				UPDATE campaign_recipients SET rendered_html = replace(rendered_html, :old, :replacement)
				WHERE id = :recipient
				""").bind("old", original.open()).bind("replacement", differentOpen)
				.bind("recipient", first.recipientId()).fetch().rowsUpdated().block();
		databaseClient.sql("""
				UPDATE tracking_tokens SET token_hash = :hash, expires_at = :expires
				WHERE campaign_recipient_id = :recipient AND token_type = 'OPEN'
				""").bind("hash", signer.digest(differentOpen)).bind("expires", differentExpiry)
				.bind("recipient", first.recipientId()).fetch().rowsUpdated().block();
		delivery.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
						450, "450 retry later", true), NOW).block();
		String before = frozenSnapshot(fixture.recipientId());
		clock.set(NOW.plus(Duration.ofDays(1)));
		CampaignDeliveryRepository.ProductionClaim retry = delivery.claimNextProduction(clock.instant()).block();

		assertThatThrownBy(() -> tracking.prepare(retry).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Frozen campaign capabilities are invalid");
		assertThat(frozenSnapshot(fixture.recipientId())).isEqualTo(before);
		assertThat(count("tracking_tokens")).isEqualTo(3);
	}

	@Test
	void expiredRetryNeverRotatesAStoredCapabilityWhoseDurableDigestWasCorrupted() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingService tracking = tracking(clock, Duration.ofMinutes(13));
		CampaignDeliveryRepository.ProductionClaim first = delivery.claimNextProduction(NOW).block();
		tracking.prepare(first).block();
		delivery.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
						450, "450 retry later", true), NOW).block();
		databaseClient.sql("""
				UPDATE tracking_tokens SET token_hash = digest('corrupted-durable-digest', 'sha256')
				WHERE campaign_recipient_id = :recipient AND token_type = 'OPEN'
				""").bind("recipient", fixture.recipientId()).fetch().rowsUpdated().block();
		String before = frozenSnapshot(fixture.recipientId());
		clock.set(NOW.plus(Duration.ofDays(1)));
		CampaignDeliveryRepository.ProductionClaim retry = delivery.claimNextProduction(clock.instant()).block();

		assertThatThrownBy(() -> tracking.prepare(retry).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Frozen campaign capabilities are invalid");
		assertThat(frozenSnapshot(fixture.recipientId())).isEqualTo(before);
		assertThat(count("tracking_tokens")).isEqualTo(3);
	}

	@Test
	void callbacksArrivingInsideTheSmtpSendWindowAreNotLostBeforeAcceptedSettlement() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW.plusSeconds(10));
		CampaignTrackingService tracking = tracking(clock);
		AuthenticationRequestContext request = new AuthenticationRequestContext(
				"198.51.100.20", "Research Browser", "smtp-window-callback");
		AtomicReference<Boolean> openObserved = new AtomicReference<>();
		AtomicReference<String> clickTarget = new AtomicReference<>();
		AtomicReference<String> statusDuringSend = new AtomicReference<>();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking, contactCrypto, (account, message) -> {
					statusDuringSend.set(text("SELECT status FROM campaign_recipients WHERE id = '"
							+ fixture.recipientId() + "'"));
					String openUrl = Jsoup.parseBodyFragment(message.html())
							.selectFirst("img[src*='/t/o/']").attr("src");
					String clickUrl = Jsoup.parseBodyFragment(message.html())
							.selectFirst("a[href*='/t/c/']").attr("href");
					openObserved.set(tracking.observeOpen(
							openUrl.substring(openUrl.indexOf("/t/o/") + 5), new HttpHeaders(), request).block());
					var resolved = tracking.click(
							clickUrl.substring(clickUrl.indexOf("/t/c/") + 5), new HttpHeaders(), request, true).block();
					clickTarget.set(resolved == null ? null : resolved.targetUrl());
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				}, clock);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);

		assertThat(statusDuringSend).hasValue("CONNECTING");
		assertThat(openObserved).hasValue(true);
		assertThat(clickTarget).hasValue("https://papers.example.org/abs/42");
		assertThat(count("tracking_events")).isEqualTo(2);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + fixture.recipientId() + "'"))
				.isEqualTo("SMTP_ACCEPTED");
	}

	@Test
	void unsubscribeDuringAConnectingAttemptPreventsAnyRetryAfterItsExplicit450Failure() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingService tracking = tracking(clock);
		AtomicInteger sends = new AtomicInteger();
		AuthenticationRequestContext request = new AuthenticationRequestContext(
				"198.51.100.20", "Research Browser", "unsubscribe-during-send");
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking, contactCrypto, (account, message) -> {
					sends.incrementAndGet();
					assertThat(tracking.unsubscribe(capabilities(message).unsubscribe(), request).block()).isTrue();
					throw new SmtpTransportException(
							SmtpTransportException.FailureCategory.SMTP_REJECTED,
							AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
							450, "450 retry later", true);
				}, clock);

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + fixture.recipientId() + "'"))
				.isEqualTo("TEMPORARY_FAILURE");
		clock.set(NOW.plus(Duration.ofMinutes(1)));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
		assertThat(sends).hasValue(1);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + fixture.recipientId() + "'"))
				.isEqualTo("UNSUBSCRIBED");
		assertThat(count("unsubscribe_records")).isEqualTo(1);
		assertThat(count("suppression_entries")).isEqualTo(1);
	}

	@Test
	void realSmtpTransportCarriesFrozenBodiesAndExactRfc8058HeadersAcrossA450Retry() throws Exception {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingService tracking = tracking(clock);
		try (ServerSocket listener = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
				var peers = Executors.newVirtualThreadPerTaskExecutor()) {
			List<String> capturedData = new CopyOnWriteArrayList<>();
			var peer = peers.submit(() -> {
				serveSmtpAttempt(listener, "450 4.2.0 retry later", capturedData);
				serveSmtpAttempt(listener, "250 2.0.0 queued", capturedData);
				return null;
			});
			databaseClient.sql("UPDATE smtp_accounts SET port = :port WHERE id = :id")
					.bind("port", listener.getLocalPort()).bind("id", SMTP).fetch().rowsUpdated().block();
			SmtpProperties smtpProperties = new SmtpProperties(
					false, Set.of("localhost"), Duration.ofSeconds(2), Duration.ofSeconds(2),
					Duration.ofSeconds(2), ENCRYPTION_KEY);
			SmtpTransport transport = new SmtpTransport(
					new SmtpSecretCrypto(ENCRYPTION_KEY), new SmtpPolicy(smtpProperties), smtpProperties);
			CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
					delivery, tracking, contactCrypto, transport, clock);

			assertThat(executor.pumpOnce().block())
					.isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
			String durableHtml = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
					+ fixture.recipientId() + "'");
			String durableText = text("SELECT rendered_text FROM campaign_recipients WHERE id = '"
					+ fixture.recipientId() + "'");
			clock.set(NOW.plus(Duration.ofMinutes(1)));
			assertThat(executor.pumpOnce().block())
					.isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
			peer.get(10, TimeUnit.SECONDS);

			assertThat(capturedData).hasSize(2);
			MimeMessage first = parseMime(capturedData.get(0));
			MimeMessage retry = parseMime(capturedData.get(1));
			String unsubscribeHeader = first.getHeader("List-Unsubscribe", null);
			assertThat(unsubscribeHeader).startsWith("<https://tracking.example.test/u/").endsWith(">");
			assertThat(first.getHeader("List-Unsubscribe-Post", null))
					.isEqualTo("List-Unsubscribe=One-Click");
			assertThat(retry.getHeader("List-Unsubscribe", null)).isEqualTo(unsubscribeHeader);
			assertThat(retry.getHeader("List-Unsubscribe-Post", null))
					.isEqualTo(first.getHeader("List-Unsubscribe-Post", null));
			assertThat(retry.getHeader("Message-ID", null)).isEqualTo(first.getHeader("Message-ID", null));
			assertThat(retry.getHeader("X-CaMel-Correlation-Id", null))
					.isEqualTo(first.getHeader("X-CaMel-Correlation-Id", null));
			assertThat(mimePart(first, "text/html")).isEqualTo(durableHtml)
					.contains("/t/o/", "/t/c/", "/u/");
			assertThat(mimePart(first, "text/plain")).isEqualTo(durableText);
			assertThat(mimePart(retry, "text/html")).isEqualTo(durableHtml);
			assertThat(mimePart(retry, "text/plain")).isEqualTo(durableText);
			assertThat(count("tracking_tokens")).isEqualTo(3);
			assertThat(count("campaign_links")).isEqualTo(1);
		}
	}

	@Test
	void smtpFailureSummariesPersistedAndReturnedByReportingNeverExposeCampaignCapabilities() {
		UUIDHolder fixture = eligibleTrackedRecipient();
		CampaignDeliveryRepository delivery = repository();
		MutableClock clock = new MutableClock(NOW);
		CampaignTrackingService tracking = tracking(clock);
		AtomicReference<List<String>> rawCapabilities = new AtomicReference<>();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, tracking, contactCrypto, (account, message) -> {
					var document = Jsoup.parseBodyFragment(message.html());
					String open = document.selectFirst("img[src*='/t/o/']").attr("src")
							.replaceFirst("^.+/t/o/", "");
					String click = document.selectFirst("a[href*='/t/c/']").attr("href")
							.replaceFirst("^.+/t/c/", "");
					String unsubscribe = document.selectFirst("a[href*='/u/']").attr("href")
							.replaceFirst("^.+/u/", "");
					rawCapabilities.set(List.of(open, click, unsubscribe));
					throw new SmtpTransportException(
							SmtpTransportException.FailureCategory.SMTP_REJECTED,
							AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO, 450,
							"450 echoed " + open + " " + click + " " + unsubscribe, true);
				}, clock);

		assertThat(executor.pumpOnce().block())
				.isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		String stored = text("SELECT smtp_response_summary FROM delivery_attempts WHERE campaign_recipient_id = '"
				+ fixture.recipientId() + "'");
		String reported = new CampaignReportingRepository(databaseClient).deliveries(0, 20)
				.next().block().smtpResponseSummary();

		assertThat(rawCapabilities.get()).hasSize(3);
		for (String capability : rawCapabilities.get()) {
			assertThat(stored).doesNotContain(capability);
			assertThat(reported).doesNotContain(capability);
		}
		assertThat(stored).doesNotContain("campaign-open:v1", "campaign-click:v1", "campaign-unsubscribe:v1")
				.contains("[redacted-capability]");
		assertThat(reported).isEqualTo(stored);
	}

	private CampaignTrackingService tracking(Clock clock) {
		return tracking(clock, Duration.ofDays(30));
	}

	private CampaignTrackingService tracking(Clock clock, Duration tokenTtl) {
		return new CampaignTrackingService(
				new CampaignTrackingRepository(databaseClient),
				new MailTrackingProperties(true, "https://tracking.example.test", TRACKING_KEY, tokenTtl),
				new CampaignTrackingSigner(TRACKING_KEY), new MailOpenClassifier(), clock, transactions);
	}

	private String frozenSnapshot(java.util.UUID recipientId) {
		return text("""
				SELECT jsonb_build_object(
				    'subject', r.rendered_subject, 'html', r.rendered_html, 'text', r.rendered_text,
				    'tokens', (SELECT jsonb_agg(jsonb_build_object(
				        'type', t.token_type, 'link', t.campaign_link_id,
				        'hash', encode(t.token_hash, 'hex'), 'expires', t.expires_at) ORDER BY t.token_type, t.id)
				      FROM tracking_tokens t WHERE t.campaign_recipient_id = r.id)
				)::text
				FROM campaign_recipients r WHERE r.id = '%s'
				""".formatted(recipientId));
	}

	private UUIDHolder eligibleTrackedRecipient() {
		java.util.UUID campaign = insertCampaign("RUNNING");
		sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true WHERE id = '"
				+ campaign + "'");
		java.util.UUID recipient = insertEligibleRecipient(campaign, "retry-tracking@research.test");
		sql("UPDATE campaign_recipients SET rendered_html = "
				+ "'<p>Personalized body</p><a href=\"https://papers.example.org/abs/42\">Paper</a>"
				+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>' WHERE id = '" + recipient + "'");
		return new UUIDHolder(campaign, recipient);
	}

	private Capabilities capabilities(SmtpTransport.OutboundMessage message) {
		return capabilities(message.html());
	}

	private Capabilities capabilities(String html) {
		var document = Jsoup.parseBodyFragment(html);
		String openUrl = document.selectFirst("img[src*='/t/o/']").attr("src");
		String clickUrl = document.selectFirst("a[href*='/t/c/']").attr("href");
		String unsubscribeUrl = document.selectFirst("a[href*='/u/']").attr("href");
		return new Capabilities(
				openUrl.substring(openUrl.indexOf("/t/o/") + 5),
				clickUrl.substring(clickUrl.indexOf("/t/c/") + 5),
				unsubscribeUrl.substring(unsubscribeUrl.indexOf("/u/") + 3));
	}

	private void serveSmtpAttempt(ServerSocket listener, String dataReply, List<String> capturedData) {
		try (var socket = listener.accept();
				var reader = new BufferedReader(new InputStreamReader(
						socket.getInputStream(), StandardCharsets.US_ASCII));
				var writer = new PrintWriter(new OutputStreamWriter(
						socket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
			socket.setSoTimeout(5_000);
			reply(writer, "220 localhost campaign SMTP fixture");
			String command;
			while ((command = reader.readLine()) != null) {
				if (command.equals("DATA")) {
					reply(writer, "354 End with a dot");
					StringBuilder message = new StringBuilder();
					while ((command = reader.readLine()) != null && !command.equals(".")) {
						message.append(command).append("\r\n");
					}
					capturedData.add(message.toString());
					reply(writer, dataReply);
				}
				else if (command.equals("QUIT")) {
					reply(writer, "221 bye");
					return;
				}
				else {
					reply(writer, "250 localhost");
				}
			}
		}
		catch (Exception error) {
			throw new AssertionError(error);
		}
	}

	private void reply(PrintWriter writer, String value) {
		writer.print(value + "\r\n");
		writer.flush();
	}

	private MimeMessage parseMime(String value) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)));
	}

	private String mimePart(MimeMessage message, String contentType) throws Exception {
		Multipart multipart = (Multipart) message.getContent();
		for (int index = 0; index < multipart.getCount(); index++) {
			var part = multipart.getBodyPart(index);
			if (part.isMimeType(contentType)) return (String) part.getContent();
		}
		throw new AssertionError("Missing expected MIME alternative");
	}

	private record UUIDHolder(java.util.UUID campaignId, java.util.UUID recipientId) { }
	private record Capabilities(String open, String click, String unsubscribe) { }

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> now;

		private MutableClock(Instant now) {
			this.now = new AtomicReference<>(now);
		}

		void set(Instant value) { now.set(value); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now.get(); }
	}

	private static final class SequencedClock extends Clock {
		private final List<Instant> instants;
		private int index;

		private SequencedClock(Instant... instants) {
			this.instants = List.of(instants);
		}

		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public synchronized Instant instant() {
			return instants.get(Math.min(index++, instants.size() - 1));
		}

		synchronized int calls() { return index; }
	}
}
