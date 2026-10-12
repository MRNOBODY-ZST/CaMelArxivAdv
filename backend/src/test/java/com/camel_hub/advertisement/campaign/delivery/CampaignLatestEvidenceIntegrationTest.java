package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.CampaignWorkflowRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A newer paper/contact mapping supersedes approved historical evidence, even after an author change. */
class CampaignLatestEvidenceIntegrationTest extends CampaignDeliveryDatabaseTestSupport {

	@ParameterizedTest
	@ValueSource(strings = {"NONE", "SAME", "DIFFERENT"})
	void paperContactPolicyDoesNotSendStaleNamedCopyAfterIdentityEvidenceChanges(String authorMode) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "stale-name@research.test");
		sql("UPDATE campaigns SET recipient_policy = 'PAPER_CONTACT', paper_contact_ids = ARRAY['" + PAPER + "'::uuid]");
		insertLatest(recipient, authorMode, "LOW", false, "UNVERIFIED", true, "SUCCEEDED");
		assertThat(new CampaignWorkflowRepository(databaseClient).preflight(campaign, NOW.minus(Duration.ofDays(180))).block().eligible()).isZero();
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block()).isNull();
		assertThat(count("delivery_attempts")).isZero();
	}

	@org.junit.jupiter.api.Test
	void paperContactPolicyRejectsExtractionProvenanceFromAnotherPaper() {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "wrong-provenance@research.test");
		sql("UPDATE campaigns SET recipient_policy = 'PAPER_CONTACT', paper_contact_ids = ARRAY['" + PAPER + "'::uuid]");
		UUID other = UUID.randomUUID();
		sql("INSERT INTO papers(id, arxiv_id, title, abstract_text, submitted_at, updated_at, pdf_url) VALUES ('" + other + "', '2608.99999', 'Other paper', 'Other public abstract', now(), now(), 'https://arxiv.org/pdf/2608.99999')");
		sql("UPDATE extraction_runs SET paper_id = '" + other + "'");
		assertThat(new CampaignWorkflowRepository(databaseClient).preflight(campaign, NOW.minus(Duration.ofDays(180))).block().eligible()).isZero();
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block()).isNull();
		assertThat(count("delivery_attempts")).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"UNSUBSCRIBE", "COOLDOWN"})
	void paperContactPolicyRechecksSafetyAfterApproval(String reason) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "paper-live-safety@research.test");
		sql("UPDATE campaigns SET recipient_policy = 'PAPER_CONTACT', paper_contact_ids = ARRAY['" + PAPER + "'::uuid] WHERE id = '" + campaign + "'");
		insertLatest(recipient, "NONE", "LOW", false, "UNVERIFIED", true, "SUCCEEDED");
		sql("UPDATE campaign_recipients SET author_id = NULL, author_name_snapshot = NULL WHERE id = '" + recipient + "'");
		assertThat(new CampaignWorkflowRepository(databaseClient).preflight(campaign, NOW.minus(Duration.ofDays(180))).block().eligible()).isEqualTo(1);
		if ("UNSUBSCRIBE".equals(reason)) {
			sql("INSERT INTO unsubscribe_records (email_hmac, campaign_id, campaign_recipient_id, token_hash) SELECT email_hmac, campaign_id, id, digest('unsubscribe', 'sha256') FROM campaign_recipients WHERE id = '" + recipient + "'");
		} else {
			sql("INSERT INTO recipient_delivery_cooldowns (email_hmac, last_smtp_accepted_at, updated_at) SELECT email_hmac, TIMESTAMPTZ '" + NOW + "', TIMESTAMPTZ '" + NOW + "' FROM campaign_recipients WHERE id = '" + recipient + "'");
		}
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block()).isNull();
		assertThat(count("delivery_attempts")).isZero();
		assertThat(recipientState(recipient).exclusionReason()).isEqualTo("UNSUBSCRIBE".equals(reason) ? "UNSUBSCRIBED" : "COOLDOWN_ACTIVE");
	}

	@ParameterizedTest
	@ValueSource(strings = {"HIGH", "MEDIUM", "LOW", "UNMAPPED"})
	void paperContactPolicyAllowsUnverifiedContactWithoutAuthorAtAllGates(String confidence) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "paper-contact@research.test");
		var workflow = new CampaignWorkflowRepository(databaseClient);
		String original = workflow.preflight(campaign, NOW.minus(Duration.ofDays(180))).block().recipientFingerprint();
		sql("UPDATE campaigns SET recipient_policy = 'PAPER_CONTACT', paper_contact_ids = ARRAY['" + PAPER + "'::uuid] WHERE id = '" + campaign + "'");
		assertThat(workflow.preflight(campaign, NOW.minus(Duration.ofDays(180))).block().recipientFingerprint()).isNotEqualTo(original);
		insertLatest(recipient, "NONE", confidence, false, "UNVERIFIED", true, "SUCCEEDED");
		sql("UPDATE campaign_recipients SET confidence = '" + confidence + "', author_id = NULL, author_name_snapshot = NULL WHERE id = '" + recipient + "'");
		assertThat(workflow.preflight(campaign, NOW.minus(Duration.ofDays(180))).block().eligible()).isEqualTo(1);
		assertThat(repository().reconcileUndeliverable(NOW.plusSeconds(3)).block()).isZero();
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block()).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"REJECTED", "MISSING"})
	void paperContactPolicyStillRejectsLatestRejectedOrMissingEvidence(String invalid) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "paper-invalid@research.test");
		sql("UPDATE campaigns SET recipient_policy = 'PAPER_CONTACT', paper_contact_ids = ARRAY['" + PAPER + "'::uuid] WHERE id = '" + campaign + "'");
		insertLatest(recipient, "NONE", "LOW", false, "REJECTED".equals(invalid) ? "REJECTED" : "UNVERIFIED", !"MISSING".equals(invalid), "SUCCEEDED");
		assertThat(new CampaignWorkflowRepository(databaseClient).preflight(campaign, NOW.minus(Duration.ofDays(180))).block().eligible()).isZero();
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block()).isNull();
		assertThat(count("delivery_attempts")).isZero();
	}

	@ParameterizedTest
	@EnumSource(InvalidLatest.class)
	void preflightAndClaimNeverFallBackToAnOlderApprovedAuthorMapping(InvalidLatest latest) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "latest-proof@research.test");
		CampaignWorkflowRepository workflow = new CampaignWorkflowRepository(databaseClient);
		assertThat(workflow.preflight(campaign, NOW.minus(Duration.ofDays(180))).block().eligible()).isEqualTo(1);

		insertLatest(recipient, latest.authorMode, latest.confidence, latest.humanVerified,
				latest.verification, latest.withEvidence, "SUCCEEDED");

		var preflight = workflow.preflight(campaign, NOW.minus(Duration.ofDays(180))).block();
		assertThat(preflight.total()).isEqualTo(1);
		assertThat(preflight.eligible()).isZero();
		if (!"SAME".equals(latest.authorMode)) assertThat(preflight.authorRelationMissing()).isEqualTo(1);
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block()).isNull();
		assertThat(recipientState(recipient)).isEqualTo(new RecipientState("SUPPRESSED", "EVIDENCE_INVALID"));
		assertThat(count("delivery_attempts")).isZero();
		assertThat(integer("SELECT attempt_count FROM campaign_recipients WHERE id = '" + recipient + "'"))
				.isZero();
	}

	@ParameterizedTest
	@EnumSource(InvalidLatest.class)
	void reconciliationAlsoRejectsNewerEvidenceWithoutFallingBackToHistory(InvalidLatest latest) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "latest-reconcile@research.test");
		insertLatest(recipient, latest.authorMode, latest.confidence, latest.humanVerified,
				latest.verification, latest.withEvidence, "PARTIALLY_SUCCEEDED");

		assertThat(repository().reconcileUndeliverable(NOW.plusSeconds(3)).block()).isEqualTo(1);
		assertThat(recipientState(recipient).status()).isEqualTo("SUPPRESSED");
		assertThat(repository().claimNextProduction(NOW.plusSeconds(4)).block()).isNull();
		assertThat(count("delivery_attempts")).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"SUCCEEDED", "PARTIALLY_SUCCEEDED"})
	void theLatestCorrectApprovedMappingStillPassesPreflightAndClaim(String sourceStatus) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "latest-correct@research.test");
		insertLatest(recipient, "SAME", "HIGH", true, "CONFIRMED", true, sourceStatus);

		var preflight = new CampaignWorkflowRepository(databaseClient)
				.preflight(campaign, NOW.minus(Duration.ofDays(180))).block();
		assertThat(preflight.eligible()).isEqualTo(1);
		assertThat(preflight.authorRelationMissing()).isZero();
		assertThat(repository().reconcileUndeliverable(NOW.plusSeconds(3)).block()).isZero();
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block())
				.extracting(CampaignDeliveryRepository.ProductionClaim::recipientId).isEqualTo(recipient);
		assertThat(count("delivery_attempts")).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(ValidLatest.class)
	void originalPolicyAllowsSourceSupportedHighWithoutReviewAndConfirmedHumanMediumAtEveryGate(ValidLatest latest) {
		UUID campaign = insertCampaign("RUNNING");
		UUID recipient = insertEligibleRecipient(campaign, "original-policy@research.test");
		insertLatest(recipient, "SAME", latest.confidence, latest.humanVerified,
				latest.verification, true, "SUCCEEDED");
		sql("UPDATE campaign_recipients SET confidence = '" + latest.confidence + "' WHERE id = '" + recipient + "'");

		var preflight = new CampaignWorkflowRepository(databaseClient)
				.preflight(campaign, NOW.minus(Duration.ofDays(180))).block();
		assertThat(preflight.eligible()).isEqualTo(1);
		assertThat(repository().reconcileUndeliverable(NOW.plusSeconds(3)).block()).isZero();
		assertThat(repository().claimNextProduction(NOW.plusSeconds(3)).block())
				.extracting(CampaignDeliveryRepository.ProductionClaim::recipientId).isEqualTo(recipient);
		assertThat(count("delivery_attempts")).isEqualTo(1);
	}

	private void insertLatest(
			UUID recipient, String authorMode, String confidence, boolean humanVerified,
			String verification, boolean withEvidence, String sourceStatus
	) {
		UUID contact = uuid("SELECT contact_id FROM campaign_recipients WHERE id = '" + recipient + "'");
		UUID run = UUID.randomUUID();
		UUID mapping = UUID.randomUUID();
		UUID relation = PAPER_AUTHOR;
		if ("DIFFERENT".equals(authorMode)) {
			UUID differentAuthor = UUID.randomUUID();
			relation = UUID.randomUUID();
			sql("INSERT INTO authors(id, normalized_name, display_name) VALUES ('" + differentAuthor
					+ "', 'different researcher', 'Different Researcher')");
			sql("INSERT INTO paper_authors(id,paper_id,author_id,author_order,raw_name) VALUES ('"
					+ relation + "','" + PAPER + "','" + differentAuthor + "',2,'Different Researcher')");
		}
		if ("NONE".equals(authorMode)) relation = null;
		databaseClient.sql("""
				INSERT INTO extraction_runs(id,paper_id,parser_version,status,started_at,completed_at)
				VALUES(:run,:paper,'new-evidence-regression',:status,:started,:completed)
				""").bind("run", run).bind("paper", PAPER).bind("status", sourceStatus)
				.bind("started", NOW.plusSeconds(1)).bind("completed", NOW.plusSeconds(2))
				.fetch().rowsUpdated().block();
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO paper_author_contacts(id,paper_author_id,paper_id,contact_id,extraction_run_id,
				                                  confidence,human_verified,verification_status)
				VALUES(:mapping,:relation,:paper,:contact,:run,:confidence,:human,:verification)
				""").bind("mapping", mapping).bind("paper", PAPER).bind("contact", contact).bind("run", run)
				.bind("confidence", confidence).bind("human", humanVerified).bind("verification", verification);
		statement = relation == null ? statement.bindNull("relation", UUID.class) : statement.bind("relation", relation);
		statement.fetch().rowsUpdated().block();
		if (withEvidence) {
			databaseClient.sql("""
					INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context)
					VALUES(:mapping,'new-paper.tex','LATEST_TEST_EVIDENCE','New masked evidence')
					""").bind("mapping", mapping).fetch().rowsUpdated().block();
		}
	}

	private enum InvalidLatest {
		LOW_WITHOUT_AUTHOR("NONE", "LOW", false, "UNVERIFIED", true),
		HIGH_WITHOUT_AUTHOR("NONE", "HIGH", true, "CONFIRMED", true),
		CONFIRMED_DIFFERENT_AUTHOR("DIFFERENT", "HIGH", true, "CONFIRMED", true),
		LOW_SAME_AUTHOR("SAME", "LOW", true, "CONFIRMED", true),
		MEDIUM_NOT_HUMAN_VERIFIED("SAME", "MEDIUM", false, "CONFIRMED", true),
		MEDIUM_NOT_CONFIRMED("SAME", "MEDIUM", true, "UNVERIFIED", true),
		REJECTED("SAME", "HIGH", true, "REJECTED", true),
		EVIDENCE_MISSING("SAME", "HIGH", true, "CONFIRMED", false);

		final String authorMode;
		final String confidence;
		final boolean humanVerified;
		final String verification;
		final boolean withEvidence;

		InvalidLatest(String authorMode, String confidence, boolean humanVerified, String verification, boolean withEvidence) {
			this.authorMode = authorMode;
			this.confidence = confidence;
			this.humanVerified = humanVerified;
			this.verification = verification;
			this.withEvidence = withEvidence;
		}
	}

	private enum ValidLatest {
		HIGH_UNVERIFIED("HIGH", false, "UNVERIFIED"),
		HIGH_CONFIRMED("HIGH", true, "CONFIRMED"),
		MEDIUM_CONFIRMED("MEDIUM", true, "CONFIRMED");

		final String confidence;
		final boolean humanVerified;
		final String verification;

		ValidLatest(String confidence, boolean humanVerified, String verification) {
			this.confidence = confidence;
			this.humanVerified = humanVerified;
			this.verification = verification;
		}
	}
}
