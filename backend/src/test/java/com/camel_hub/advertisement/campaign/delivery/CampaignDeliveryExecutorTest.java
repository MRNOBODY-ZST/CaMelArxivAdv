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

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class CampaignDeliveryExecutorTest extends CampaignDeliveryDatabaseTestSupport {

	private CampaignDeliveryRepository repository;

	@Test
	void preparationFailureRetainsOnlySafeStageEvidenceAfterSettlement(org.springframework.boot.test.system.CapturedOutput output) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "diagnostic@research.test");
		AtomicInteger sends = new AtomicInteger();
		var executor = executor(claim -> { throw new IllegalArgumentException("synthetic-secret@example.test token=PRIVATE"); },
				(account, message) -> { sends.incrementAndGet(); throw new AssertionError("unexpected SMTP"); });
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(sends).hasValue(0);
		assertThat(integer("SELECT count(*)::int FROM information_schema.tables WHERE table_name='campaign_preparation_diagnostics'"))
				.as("future preparation failures have durable safe evidence").isEqualTo(1);
		String evidence = text("SELECT row_to_json(d)::text FROM campaign_preparation_diagnostics d JOIN delivery_attempts a ON a.id=d.production_attempt_id WHERE a.campaign_recipient_id='" + recipient + "'");
		assertThat(evidence).contains("TRACKING_RENDER", "VALIDATION", "PREP_TRACKING_RENDER_FAILED", "trace_ref")
				.doesNotContain("synthetic-secret", "PRIVATE", "IllegalArgumentException");
		assertThat(output.getAll()).doesNotContain("synthetic-secret", "PRIVATE");
	}

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
		CampaignDeliveryRepository.ProductionClaim first = repository.claimNextProduction(NOW).block();
		repository.completeFailure(first.recipientId(), first.attemptId(), first.leaseDigest(),
				temporary(450), NOW).block();
		CampaignDeliveryRepository.ProductionClaim second = repository
				.claimNextProduction(NOW.plus(Duration.ofMinutes(1))).block();
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
		var diagnostic=new com.camel_hub.advertisement.campaign.CampaignReportingRepository(databaseClient).deliveries(campaignId,0,20).blockFirst().preparationDiagnostic();
		assertThat(diagnostic.stage()).isEqualTo(PreparationDiagnostic.PreparationStage.MESSAGE_BUILD);
		assertThat(diagnostic.exceptionType()).isEqualTo(PreparationDiagnostic.SafeExceptionType.VALIDATION);
	}

	@Test void authenticatedDecryptionFailureRetainsCryptoCauseClassification() {
		UUID campaign=insertCampaign("RUNNING");UUID recipient=insertEligibleRecipient(campaign,"authenticated-diagnostic@research.test");
		sql("UPDATE campaign_recipients SET email_ciphertext=set_byte(email_ciphertext,0,(get_byte(email_ciphertext,0)+1)%256) WHERE id='"+recipient+"'");
		var executor=executor(defaultPreparer(),(account,message)->{throw new AssertionError("SMTP");});
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		var diagnostic=new com.camel_hub.advertisement.campaign.CampaignReportingRepository(databaseClient).deliveries(campaign,0,20).blockFirst().preparationDiagnostic();
		assertThat(diagnostic.stage().name()).isEqualTo("MESSAGE_BUILD");assertThat(diagnostic.exceptionType().name()).isEqualTo("CRYPTO");
	}

	@Test void actualEligibilityAndFenceFailuresHaveTheirOwnStagesAndNeverSend() {
		for(boolean fence:new boolean[]{false,true}) for(boolean sync:new boolean[]{false,true}) {
			resetDatabase();UUID campaign=insertCampaign("RUNNING");insertEligibleRecipient(campaign,"boundary-diagnostic@research.test");
			var failing=org.mockito.Mockito.spy(repository());
			var error=new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-token");
			var stub=sync?org.mockito.Mockito.doThrow(error):org.mockito.Mockito.doReturn(Mono.error(error));
			if(fence) stub.when(failing).beginSmtp(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
			else stub.when(failing).productionEligible(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
			var executor=new CampaignDeliveryExecutor(failing,defaultPreparer(),contactCrypto,(account,message)->{throw new AssertionError("SMTP");},Clock.fixed(NOW,ZoneOffset.UTC));
			assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
			var diagnostic=new com.camel_hub.advertisement.campaign.CampaignReportingRepository(databaseClient).deliveries(campaign,0,20).blockFirst().preparationDiagnostic();
			assertThat(diagnostic.stage().name()).isEqualTo(fence?"SMTP_FENCE":"CLAIM_VALIDATION");
			assertThat(diagnostic.exceptionType().name()).isEqualTo("PERSISTENCE");
			assertThat(count("delivery_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
		}
	}

	@Test void partnerHoldDiagnosticCommitsWithHoldAndSaveFailureRollsBackHold() {
		for(boolean reject:new boolean[]{false,true}) {
			resetDatabase();UUID campaign=insertCampaign("RUNNING");UUID recipient=insertEligibleRecipient(campaign,"partner-diagnostic@research.test");
			sql("UPDATE campaign_recipients SET rendered_html=rendered_html || '<a href=\"{{partner_claim_url}}\">Activate</a>',rendered_text=rendered_text || ' {{partner_claim_url}}' WHERE id='"+recipient+"'");
			var failing=executor(claim -> PreparationDiagnostic.at(PreparationDiagnostic.PreparationStage.ARTIFACT_PERSIST, () -> Mono.error(new org.springframework.dao.DataAccessResourceFailureException("synthetic-secret"))),
				(account,message)->{throw new AssertionError("SMTP");});
			if(reject) sql("ALTER TABLE campaign_preparation_diagnostics ADD CONSTRAINT synthetic_hold_rejection CHECK (stage <> 'ARTIFACT_PERSIST')");
			try {
				if(reject) {
					org.assertj.core.api.Assertions.assertThatThrownBy(() -> failing.pumpOnce().block()).isInstanceOf(org.springframework.dao.DataAccessException.class);
					assertThat(text("SELECT status FROM campaigns WHERE id='"+campaign+"'")).isEqualTo("RUNNING");
					assertThat(text("SELECT status FROM delivery_attempts")).isEqualTo("CONNECTING");assertThat(count("campaign_preparation_diagnostics")).isZero();
				} else {
					assertThat(failing.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PARTNER_INVITE_HELD);
					assertThat(text("SELECT status FROM campaigns WHERE id='"+campaign+"'")).isEqualTo("PAUSED");
					var view=new com.camel_hub.advertisement.campaign.CampaignReportingRepository(databaseClient).deliveries(campaign,0,20).blockFirst();
					assertThat(view.status()).isEqualTo("CANCELED");assertThat(view.failureCategory()).isEqualTo("PARTNER_INVITE_INVALID_INPUT");
					assertThat(view.preparationDiagnostic().stage().name()).isEqualTo("ARTIFACT_PERSIST");assertThat(view.preparationDiagnostic().exceptionType().name()).isEqualTo("PERSISTENCE");
				}
				assertThat(count("delivery_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
			} finally {if(reject) sql("ALTER TABLE campaign_preparation_diagnostics DROP CONSTRAINT synthetic_hold_rejection");}
		}
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

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(PreparationDiagnostic.PreparationStage.class)
	void eachStagePersistsSameTraceForSynchronousAndReactiveFailureWithoutSmtp(PreparationDiagnostic.PreparationStage stage) {
		for (boolean sync : new boolean[]{true,false}) {
			UUID campaign=insertCampaign("RUNNING");
			UUID recipient=insertEligibleRecipient(campaign,UUID.randomUUID()+"@research.test");
			AtomicReference<PreparationDiagnostic> expected=new AtomicReference<>();
			var executor=executor(claim -> PreparationDiagnostic.<CampaignOutboundPreparer.PreparedOutbound>at(stage, () -> {
				if(sync) throw new IllegalArgumentException("synthetic-private-token");
				return Mono.error(new IllegalArgumentException("synthetic-private-token"));
			}).doOnError(error -> expected.set(PreparationDiagnostic.diagnostic(error))),
				(account,message) -> {throw new AssertionError("SMTP must not run");});
			assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
			var view=new com.camel_hub.advertisement.campaign.CampaignReportingRepository(databaseClient).deliveries(campaign,0,20).blockFirst();
			assertThat(view.preparationDiagnostic()).isEqualTo(expected.get());
			assertThat(view.preparationDiagnostic().stage()).isEqualTo(stage);
			assertThat(view.preparationDiagnosticExplanation()).isNull();
			assertThat(view.smtpResponseSummary()).isNull();
			assertThat(count("delivery_attempts WHERE campaign_recipient_id='"+recipient+"' AND smtp_started_at IS NOT NULL")).isZero();
		}
	}

	@Test void rejectedLeaseDoesNotWriteDiagnosticAndSaveFailureRollsBackSettlement() {
		UUID campaign=insertCampaign("RUNNING");insertEligibleRecipient(campaign,"rejected-diagnostic@research.test");
		var claim=repository.claimNextProduction(NOW).block();
		var diagnostic=new PreparationDiagnostic(PreparationDiagnostic.PreparationStage.BODY_PERSIST,
			PreparationDiagnostic.SafeExceptionType.PERSISTENCE,PreparationDiagnostic.PreparationErrorCode.PREP_BODY_PERSIST_FAILED,UUID.randomUUID());
		var failure=new SmtpTransportException(SmtpTransportException.FailureCategory.PREPARATION_FAILED,AttemptStatus.PERMANENT_FAILURE,TransportStage.MAIL_FROM,null,null,false);
		assertThat(repository.completeFailureDetailed(claim.recipientId(),claim.attemptId(),new byte[32],failure,NOW,diagnostic).block().applied()).isFalse();
		assertThat(count("campaign_preparation_diagnostics")).isZero();
		sql("ALTER TABLE campaign_preparation_diagnostics ADD CONSTRAINT synthetic_diagnostic_rejection CHECK (stage <> 'BODY_PERSIST')");
		try {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.completeFailureDetailed(claim.recipientId(),claim.attemptId(),claim.leaseDigest(),failure,NOW,diagnostic).block()).isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(text("SELECT status FROM delivery_attempts WHERE id='"+claim.attemptId()+"'")).isEqualTo("CONNECTING");
			assertThat(text("SELECT status FROM campaign_recipients WHERE id='"+claim.recipientId()+"'")).isEqualTo("CONNECTING");
			assertThat(count("campaign_preparation_diagnostics")).isZero();
		} finally {sql("ALTER TABLE campaign_preparation_diagnostics DROP CONSTRAINT synthetic_diagnostic_rejection");}
	}

	@Test void diagnosticSaveFailureEscapesExecutorWithoutSuccessOrSend() {
		UUID campaign=insertCampaign("RUNNING");insertEligibleRecipient(campaign,"save-diagnostic@research.test");
		sql("ALTER TABLE campaign_preparation_diagnostics ADD CONSTRAINT synthetic_diagnostic_rejection CHECK (stage <> 'TRACKING_RENDER')");
		AtomicInteger sends=new AtomicInteger();
		try {
			var executor=executor(claim -> Mono.error(new IllegalArgumentException("synthetic-secret")),(account,message)->{sends.incrementAndGet();throw new AssertionError("SMTP");});
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.pumpOnce().block()).isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(sends).hasValue(0);assertThat(count("campaign_preparation_diagnostics")).isZero();
			assertThat(text("SELECT status FROM delivery_attempts")).isEqualTo("CONNECTING");
		} finally {sql("ALTER TABLE campaign_preparation_diagnostics DROP CONSTRAINT synthetic_diagnostic_rejection");}
	}

	@Test
	void deniedFinalSafetyFenceDoesNotSendOrInventFailureSettlement() {
		UUID campaign=insertCampaign("PAUSED");insertEligibleRecipient(campaign,"safety-source@research.test");
		var safety=new com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository(databaseClient,transactions,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
		var signer=new com.camel_hub.advertisement.campaign.safety.CampaignSafetySigner(HMAC_KEY);
		safety.materialize(new com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository.MaterializeCommand(
			campaign,ACTOR,0,1,signer.destinationHmac("fixed@example.test"),"f***@example.test",NOW,"synthetic")).block();
		AtomicInteger sends=new AtomicInteger();
		var preparer=new com.camel_hub.advertisement.campaign.safety.CampaignSafetyOutboundPreparer() {
			public Mono<PreparedSafetyOutbound> prepare(CampaignDeliveryRepository.SafetyClaim claim) {
				return Mono.just(new PreparedSafetyOutbound("fixed@example.test",claim.renderedSubject(),claim.renderedHtml(),claim.renderedText(),Map.of()));
			}
			public PreparedSafetyOutbound validateForSend(CampaignDeliveryRepository.SafetyClaim claim,PreparedSafetyOutbound prepared) {
				sql("UPDATE campaign_safety_runs SET status='CANCELED'");return prepared;
			}
		};
		var executor=new CampaignDeliveryExecutor(safetyRepository("fixed@example.test"),claim->Mono.error(new AssertionError("production")),safety,preparer,contactCrypto,
			(account,message)->{sends.incrementAndGet();return new SmtpTransport.SmtpOutcome(AttemptStatus.SMTP_ACCEPTED,TransportStage.POST_DATA,250,"queued");},Clock.fixed(NOW,ZoneOffset.UTC));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
		assertThat(sends).hasValue(0);
		assertThat(text("SELECT status FROM campaign_safety_attempts")).isEqualTo("CONNECTING");
		assertThat(count("campaign_safety_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
	}

	private SmtpTransportException temporary(int code) {
		return new SmtpTransportException(
				SmtpTransportException.FailureCategory.SMTP_REJECTED,
				AttemptStatus.TEMPORARY_FAILURE, TransportStage.RCPT_TO,
				code, code + " temporary", true);
	}

	@Test void unavailableSafetySettlementFailsClosedWithoutNullDereferenceOrFabricatedDiagnostic() {
		UUID campaign=insertCampaign("PAUSED");insertEligibleRecipient(campaign,"missing-safety-repository@research.test");
		var safety=new com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository(databaseClient,transactions,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
		var signer=new com.camel_hub.advertisement.campaign.safety.CampaignSafetySigner(HMAC_KEY);
		safety.materialize(new com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository.MaterializeCommand(campaign,ACTOR,0,1,signer.destinationHmac("fixed@example.test"),"f***@example.test",NOW,"synthetic")).block();
		var executor=new CampaignDeliveryExecutor(safetyRepository("fixed@example.test"),defaultPreparer(),contactCrypto,(account,message)->{throw new AssertionError("SMTP");},Clock.fixed(NOW,ZoneOffset.UTC));
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.pumpOnce().block()).isInstanceOf(IllegalStateException.class).hasMessage("Campaign safety settlement is unavailable");
		assertThat(text("SELECT status FROM campaign_safety_attempts")).isEqualTo("CONNECTING");assertThat(count("campaign_preparation_diagnostics")).isZero();
	}

	@Test void diagnosticSchemaRequiresOneRealAttemptAndPreservesAttemptHistory() {
		UUID campaign=insertCampaign("RUNNING");insertEligibleRecipient(campaign,"schema-diagnostic@research.test");
		var claim=repository.claimNextProduction(NOW).block();
		String fields="stage,exception_type,error_code,trace_ref";
		String values="'BODY_PERSIST','PERSISTENCE','PREP_BODY_PERSIST_FAILED',gen_random_uuid()";
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql("INSERT INTO campaign_preparation_diagnostics("+fields+") VALUES("+values+")")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql("INSERT INTO campaign_preparation_diagnostics(production_attempt_id,"+fields+") VALUES(gen_random_uuid(),"+values+")")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		sql("INSERT INTO campaign_preparation_diagnostics(production_attempt_id,"+fields+") VALUES('"+claim.attemptId()+"',"+values+")");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql("INSERT INTO campaign_preparation_diagnostics(production_attempt_id,"+fields+") VALUES('"+claim.attemptId()+"',"+values+")")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql("DELETE FROM delivery_attempts WHERE id='"+claim.attemptId()+"'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(count("delivery_attempts")).isEqualTo(1);assertThat(count("campaign_preparation_diagnostics")).isEqualTo(1);
	}

	private CampaignDeliveryExecutor executor(
			CampaignOutboundPreparer preparer, CampaignDeliveryExecutor.CampaignSmtpSender sender
	) {
		return new CampaignDeliveryExecutor(
				repository, preparer, contactCrypto, sender,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}
}
