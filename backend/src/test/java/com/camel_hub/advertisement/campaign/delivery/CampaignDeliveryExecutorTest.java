package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignDeliveryExecutorTest extends CampaignDeliveryDatabaseTestSupport {

	private CampaignDeliveryRepository repository;

	@BeforeEach
	void createRepository() {
		repository = repository();
	}

	@Test
	void preparesReactivelyThenDecryptsOnlyAtTheFinalTransportBoundaryOutsideClaimTransaction() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "final-boundary@research.test");
		AtomicReference<CampaignDeliveryRepository.ProductionClaim> preparedClaim = new AtomicReference<>();
		AtomicReference<SmtpTransport.OutboundMessage> sentMessage = new AtomicReference<>();
		CampaignOutboundPreparer preparer = claim -> {
			preparedClaim.set(claim);
			return Mono.just(new CampaignOutboundPreparer.PreparedOutbound(
					"Final subject", "<p>Final HTML</p>", "Final text",
					Map.of(
							"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
							"List-Unsubscribe-Post", "List-Unsubscribe=One-Click")));
		};
		CampaignDeliveryExecutor.CampaignSmtpSender sender = (account, message) -> {
			// This independent write would block if the claim transaction still held the SMTP row lock.
			databaseClient.sql("UPDATE smtp_accounts SET updated_at = updated_at WHERE id = :id")
					.bind("id", account.id()).fetch().rowsUpdated()
					.timeout(Duration.ofSeconds(2)).block();
			sentMessage.set(message);
			return new SmtpTransport.SmtpOutcome(
					AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
		};
		CampaignDeliveryExecutor executor = executor(preparer, sender);

		CampaignDeliveryExecutor.PumpResult result = executor.pumpOnce()
				.timeout(Duration.ofSeconds(5)).block();

		assertThat(result).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(preparedClaim.get().recipientId()).isEqualTo(recipientId);
		assertThat(preparedClaim.get().emailCiphertext()).isNotEmpty();
		assertThat(preparedClaim.get().fromEmail()).isEqualTo("approved-snapshot@example.invalid");
		assertThat(preparedClaim.get().templateVersionId()).isEqualTo(TEMPLATE_VERSION);
		assertThat(sentMessage.get().recipient()).isEqualTo("final-boundary@research.test");
		assertThat(sentMessage.get().fromEmail()).isEqualTo("approved-snapshot@example.invalid");
		assertThat(sentMessage.get().rfcMessageId()).isEqualTo(
				"<" + recipientId + "@delivery.camel-arxiv.invalid>");
		assertThat(sentMessage.get().headers()).containsOnlyKeys(
				"List-Unsubscribe", "List-Unsubscribe-Post");
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("SMTP_ACCEPTED");
	}

	@Test
	void explicitFourHundredIsTheOnlyFailureThatRequeues() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "temporary@research.test");
		CampaignDeliveryExecutor executor = executor(defaultPreparer(), (account, message) -> {
			throw new SmtpTransportException(
					SmtpTransportException.FailureCategory.SMTP_REJECTED,
					AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
					450, "450 temporary", true);
		});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("TEMPORARY_FAILURE");
	}

	@Test
	void exhaustedExplicitFourHundredReportsTheDurableRecipientTerminalState() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "exhausted@research.test");
		CampaignDeliveryRepository.ProductionClaim first = repository.claimNext(NOW).block();
		repository.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				temporary(450), NOW).block();
		CampaignDeliveryRepository.ProductionClaim second = repository
				.claimNext(NOW.plus(Duration.ofMinutes(1))).block();
		repository.completeFailure(second.recipientId(), second.attemptId(), second.leaseDigest(),
				temporary(451), NOW.plus(Duration.ofMinutes(1))).block();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				repository, defaultPreparer(), contactCrypto, (account, message) -> {
					throw temporary(452);
				}, Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("PERMANENT_FAILURE");
		assertThat(text("SELECT status FROM delivery_attempts WHERE campaign_recipient_id = '"
				+ recipientId + "' ORDER BY attempt_number DESC LIMIT 1")).isEqualTo("TEMPORARY_FAILURE");
	}

	@Test
	void postDataUncertaintyIsTerminal() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "unknown-executor@research.test");
		CampaignDeliveryExecutor executor = executor(defaultPreparer(), (account, message) -> {
			throw new SmtpTransportException(
					SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE,
					AttemptStatus.OUTCOME_UNKNOWN, TransportStage.POST_DATA,
					null, "connection ended after DATA", false);
		});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.OUTCOME_UNKNOWN);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
	}

	@Test
	void deterministicPreparationOrDecryptionFailureIsPermanentBeforeSmtp() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "preparation@research.test");
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = executor(
				claim -> Mono.error(new IllegalArgumentException("invalid final rendering")),
				(account, message) -> {
					sends.incrementAndGet();
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(sends).hasValue(0);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("PERMANENT_FAILURE");
		assertThat(text("SELECT failure_category FROM delivery_attempts WHERE campaign_recipient_id = '"
				+ recipientId + "'"))
				.isEqualTo("PREPARATION_FAILED");
	}

	@Test
	void incompleteFinalPreparationFailsClosedBeforeSmtp() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "incomplete@research.test");
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = executor(
				claim -> Mono.just(new CampaignOutboundPreparer.PreparedOutbound(
						"Final subject", "<p>Final body</p>", "Final body", Map.of())),
				(account, message) -> {
					sends.incrementAndGet();
					return new SmtpTransport.SmtpOutcome(
							AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
				});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(sends).hasValue(0);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("PERMANENT_FAILURE");
	}

	@Test
	void synchronousPreparerFailureIsDurablySettledBeforeKafkaCanAcknowledge() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "sync-preparer@research.test");
		CampaignDeliveryExecutor executor = executor(claim -> {
			throw new IllegalArgumentException("invalid preparer configuration");
		}, (account, message) -> {
			throw new AssertionError("SMTP must not run");
		});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("PERMANENT_FAILURE");
	}

	@Test
	void finalPreparationRequiresResolvedContentAndRfc8058Headers() {
		Map<String, String> validHeaders = Map.of(
				"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
				"List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				new CampaignOutboundPreparer.PreparedOutbound(
						"Subject", "<p>{{unsubscribe_url}}</p>", "Final text", validHeaders))
				.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				new CampaignOutboundPreparer.PreparedOutbound(
						"Subject", "<p>Final</p>", "Final text", Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				new CampaignOutboundPreparer.PreparedOutbound(
						"Subject", "<p>Final</p>", "Final text", Map.of(
								"List-Unsubscribe", "<http://tracking.example.test/u/opaque>",
								"List-Unsubscribe-Post", "List-Unsubscribe=One-Click")))
				.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				new CampaignOutboundPreparer.PreparedOutbound(
						"Subject", "<p>Final</p>", "Final text", Map.of(
								"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
								"List-Unsubscribe-Post", "List-Unsubscribe=No")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void duplicateWakeupsFindNoWorkAfterDurableSettlement() {
		UUID campaignId = insertCampaign("RUNNING");
		insertEligibleRecipient(campaignId, "idempotent@research.test");
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = executor(defaultPreparer(), (account, message) -> {
			sends.incrementAndGet();
			return new SmtpTransport.SmtpOutcome(
					AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
		});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
		assertThat(sends).hasValue(1);
		assertThat(count("delivery_attempts")).isEqualTo(1);
	}

	@Test
	void onePumpSendsAtMostOneOfTwoDueRecipients() {
		UUID campaignId = insertCampaign("RUNNING");
		insertEligibleRecipient(campaignId, "single-pump-one@research.test");
		insertEligibleRecipient(campaignId, "single-pump-two@research.test");
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = executor(defaultPreparer(), (account, message) -> {
			sends.incrementAndGet();
			return new SmtpTransport.SmtpOutcome(
					AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
		});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(sends).hasValue(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_recipients WHERE status = 'SMTP_ACCEPTED'"))
				.isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_recipients WHERE status = 'QUEUED'"))
				.isEqualTo(1);
	}

	@Test
	void acceptedSmtpCannotFallBackIntoOrdinaryFailureWhenLeaseSettlementLosesRace() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "accepted-race@research.test");
		CampaignDeliveryExecutor executor = executor(defaultPreparer(), (account, message) -> {
			repository.reconcileExpiredLeases(
					NOW.plus(DELIVERY_PROPERTIES.leaseDuration()).plusSeconds(1)).block();
			return new SmtpTransport.SmtpOutcome(
					AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
		});

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.pumpOnce().block())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("requires lease reconciliation");
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
		assertThat(text("SELECT status FROM delivery_attempts WHERE campaign_recipient_id = '"
				+ recipientId + "'"))
				.isEqualTo("OUTCOME_UNKNOWN");
	}

	@Test
	void corruptedContactCiphertextFailsPermanentlyBeforeTransport() {
		UUID campaignId = insertCampaign("RUNNING");
		UUID recipientId = insertEligibleRecipient(campaignId, "bad-ciphertext@research.test");
		sql("UPDATE campaign_recipients SET email_nonce = decode('aa','hex') WHERE id = '" + recipientId + "'");
		AtomicInteger sends = new AtomicInteger();
		CampaignDeliveryExecutor executor = executor(defaultPreparer(), (account, message) -> {
			sends.incrementAndGet();
			return new SmtpTransport.SmtpOutcome(
					AttemptStatus.SMTP_ACCEPTED, TransportStage.POST_DATA, 250, "250 queued");
		});

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(sends).hasValue(0);
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + recipientId + "'"))
				.isEqualTo("PERMANENT_FAILURE");
	}

	private CampaignOutboundPreparer defaultPreparer() {
		return claim -> Mono.just(new CampaignOutboundPreparer.PreparedOutbound(
				claim.renderedSubject(),
				claim.renderedHtml().replace("{{unsubscribe_url}}", "https://tracking.example.test/u/opaque"),
				claim.renderedText().replace("{{unsubscribe_url}}", "https://tracking.example.test/u/opaque"),
				Map.of(
						"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
						"List-Unsubscribe-Post", "List-Unsubscribe=One-Click")));
	}

	private SmtpTransportException temporary(int code) {
		return new SmtpTransportException(
				SmtpTransportException.FailureCategory.SMTP_REJECTED,
				AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
				code, code + " temporary", true);
	}

	private CampaignDeliveryExecutor executor(
			CampaignOutboundPreparer preparer, CampaignDeliveryExecutor.CampaignSmtpSender sender
	) {
		return new CampaignDeliveryExecutor(
				repository, preparer, contactCrypto, sender,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}
}
