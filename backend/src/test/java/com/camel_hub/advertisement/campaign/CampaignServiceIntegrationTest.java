package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.campaign.tracking.CampaignPublicContentRedactor;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService;
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

import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.HexFormat;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CampaignServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_campaign_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID TEMPLATE = UUID.fromString("70000000-0000-0000-0000-000000000001");
	private static final UUID SEGMENT = UUID.fromString("71000000-0000-0000-0000-000000000001");
	private static final UUID SMTP = UUID.fromString("72000000-0000-0000-0000-000000000001");
	private static DatabaseClient databaseClient;
	private static ConnectionFactory connectionFactory;
	private CampaignService service;

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
		sql("TRUNCATE mailbox_inbound_events, mailbox_sync_cursors, campaign_safety_events, "
				+ "campaign_safety_links, campaign_safety_attempts, campaign_safety_messages, campaign_safety_runs, "
				+ "recipient_delivery_cooldowns, outbox_messages, campaign_exclusions, campaign_recipients, "
				+ "campaigns, segments, suppression_entries, unsubscribe_records, extraction_evidence, "
				+ "paper_author_contacts, extraction_runs, contacts, paper_authors, authors, "
				+ "papers, arxiv_categories, arxiv_archives, arxiv_groups, mailbox_accounts, smtp_accounts, "
				+ "email_template_versions, email_templates, users CASCADE");
		seedCampaignDependencies();
		TransactionalOperator transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		service = new CampaignService(
				new CampaignRepository(databaseClient), new SegmentRepository(databaseClient, objectMapper),
				new PersonalizationProperties(true, "openai", "gpt-test", 100), objectMapper, transactions);
	}

	@Test
	void createsADraftAndQueuesOnePrivacySafePersonalizationJob() {
		var created = service.create(ACTOR, command()).block();
		var started = service.startPersonalization(
				created.id(), new AuthenticationRequestContext("127.0.0.1", "JUnit", "campaigntrace1")).block();

		assertThat(started).isNotNull();
		assertThat(started.queuedRecipients()).isEqualTo(1);
		var updated = service.get(created.id()).block();
		assertThat(updated.generationStatus()).isEqualTo("QUEUED");
		assertThat(updated.templateVersion()).isEqualTo(1);
		assertThat(updated.lockVersion()).isZero();
		assertThat(updated.mailboxAccountId()).isNull();
		assertThat(updated.deliveryCounts().queued()).isEqualTo(1);
		assertThat(updated.recipientCounts().queued()).isEqualTo(1);
		assertThat(service.recipients(created.id(), 1, 20).block().items()).singleElement()
				.satisfies(recipient -> {
					assertThat(recipient.authorName()).isEqualTo("Ada Lovelace");
					assertThat(recipient.paperTitle()).isEqualTo("Safe Distributed Intelligence");
					assertThat(recipient.personalizationStatus()).isEqualTo("QUEUED");
				});
		String payload = text("SELECT CAST(payload AS text) FROM outbox_messages");
		assertThat(payload).contains("Safe Distributed Intelligence", "Ada Lovelace", "campaigntrace1")
				.doesNotContain("university.edu", "emailCiphertext", "emailNonce", "emailHmac", "recipientEmail");
	}

	@Test
	void dynamicConfigurationOverridesDisabledEnvironmentAndPinsBothSelectionModes() {
		var settings = mock(PersonalizationSettingsService.class);
		var effective = mock(PersonalizationSettingsService.EffectiveSettings.class);
		when(effective.enabled()).thenReturn(true);
		when(effective.keyConfigured()).thenReturn(true);
		when(effective.provider()).thenReturn("anthropic");
		when(effective.model()).thenReturn("new-pinned-model");
		when(settings.effective()).thenReturn(reactor.core.publisher.Mono.just(effective));
		when(settings.snapshot(any(), eq(effective))).thenReturn(reactor.core.publisher.Mono.empty());
		service = dynamicService(settings);
		addStrictAuthors(1);

		UUID normal = service.create(ACTOR, command()).block().id();
		var first = service.startPersonalization(normal, context(), List.of(entity("30000000", 1))).block();
		UUID production = service.create(ACTOR, command()).block().id();
		var second = service.startPersonalization(production, context(), null, 1).block();

		verify(settings).snapshot(first.jobId(), effective);
		verify(settings).snapshot(second.jobId(), effective);
		assertThat(longValue("SELECT count(*) FROM campaigns WHERE generation_provider = 'anthropic' "
				+ "AND generation_model = 'new-pinned-model' AND generation_status = 'QUEUED'")).isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM delivery_attempts")).isZero();
	}

	@Test
	void snapshotFailureRollsBackRecipientsAndOutboxInsteadOfQueuingAnUnpinnedJob() {
		var settings = mock(PersonalizationSettingsService.class);
		var effective = mock(PersonalizationSettingsService.EffectiveSettings.class);
		when(effective.enabled()).thenReturn(true);
		when(effective.keyConfigured()).thenReturn(true);
		when(effective.provider()).thenReturn("anthropic");
		when(effective.model()).thenReturn("pinned-model");
		when(settings.effective()).thenReturn(reactor.core.publisher.Mono.just(effective));
		when(settings.snapshot(any(), eq(effective))).thenReturn(reactor.core.publisher.Mono.error(
				new IllegalStateException("Snapshot storage unavailable")));
		service = dynamicService(settings);
		UUID campaign = service.create(ACTOR, command()).block().id();

		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block())
				.isInstanceOf(IllegalStateException.class).hasMessage("Snapshot storage unavailable");
		assertNoQueuedGeneration(campaign);
	}

	@Test
	void disabledOrMissingKeyDynamicConfigurationNeverQueuesGeneration() {
		var settings = mock(PersonalizationSettingsService.class);
		var effective = mock(PersonalizationSettingsService.EffectiveSettings.class);
		when(settings.effective()).thenReturn(reactor.core.publisher.Mono.just(effective));
		service = dynamicService(settings);
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block())
				.isInstanceOf(PersonalizationUnavailableException.class);
		when(effective.enabled()).thenReturn(true);
		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block())
				.isInstanceOf(PersonalizationUnavailableException.class);
		assertNoQueuedGeneration(campaign);
	}

	private CampaignService dynamicService(PersonalizationSettingsService settings) {
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		return new CampaignService(new CampaignRepository(databaseClient), new SegmentRepository(databaseClient, mapper),
				new PersonalizationProperties(false, "openai", "old-env-model", 20), mapper,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)), new CampaignPublicContentRedactor(),
				new CampaignProductionSelectionPolicy(testCrypto(), new CampaignSafetyProperties(false, "Owner@example.test", 20,
						Map.of("qq", "qq@example.test", "gmail", "gmail@example.test")), Duration.ofDays(180)), settings);
	}

	@Test
	void rejectsGenerationWhenTheProviderIsDisabled() {
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		var disabled = new CampaignService(
				new CampaignRepository(databaseClient), new SegmentRepository(databaseClient, objectMapper),
				new PersonalizationProperties(false, "openai", "gpt-test", 100), objectMapper,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
		var created = disabled.create(ACTOR, command()).block();

		assertThatThrownBy(() -> disabled.startPersonalization(
				created.id(), new AuthenticationRequestContext("127.0.0.1", "JUnit", "campaigntrace2")).block())
				.isInstanceOf(PersonalizationUnavailableException.class);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isZero();
	}

	@Test
	void queuesExactlyTwentyDistinctSelectedPapersAndNoOtherAuthors() throws Exception {
		for (int paper = 2; paper <= 21; paper++) {
			addPaper(paper);
			linkContact(paper, paper + 10);
			linkContact(paper, paper + 40);
		}
		var created = service.create(ACTOR, command()).block();
		List<UUID> selected = IntStream.rangeClosed(1, 20).mapToObj(index -> entity("30000000", index)).toList();

		var started = service.startPersonalization(created.id(), context(), selected).block();

		assertThat(started.queuedRecipients()).isEqualTo(20);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(20);
		assertThat(longValue("SELECT count(DISTINCT paper_id) FROM campaign_recipients")).isEqualTo(20);
		assertThat(longValue("SELECT count(DISTINCT email_hmac) FROM campaign_recipients")).isEqualTo(20);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE paper_id = '"
				+ entity("30000000", 21) + "'")).isZero();
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		String payload = text("SELECT CAST(payload AS text) FROM outbox_messages");
		assertThat(new ObjectMapper().readTree(payload).path("payload").path("targets").size()).isEqualTo(20);
		assertThat(payload).doesNotContain("emailCiphertext", "emailNonce", "emailHmac", "research.edu");
		assertThat(service.get(created.id()).block().status()).isEqualTo("DRAFT");
		assertThat(longValue("SELECT count(*) FROM delivery_attempts")).isZero();
		assertThat(longValue("SELECT cardinality(personalization_paper_ids)::bigint FROM campaigns")).isEqualTo(20);

		// The UI intentionally sends no request body when retrying. A saved explicit
		// selection must not expand to the other eligible paper or its extra authors.
		sql("UPDATE campaigns SET generation_status = 'PARTIALLY_FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'GENERATED', personalized_at = now()");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED', personalized_at = NULL "
				+ "WHERE paper_id = '" + entity("30000000", 20) + "'");
		var retry = service.startPersonalization(created.id(), context()).block();
		assertThat(retry.queuedRecipients()).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(20);
		assertThat(longValue("SELECT count(DISTINCT paper_id) FROM campaign_recipients")).isEqualTo(20);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE personalization_status = 'GENERATED'"))
				.isEqualTo(19);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(2);
		assertThat(longValue("SELECT jsonb_array_length(payload->'payload'->'targets')::bigint FROM outbox_messages "
				+ "WHERE payload->>'jobId' = '" + retry.jobId() + "'")).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE paper_id = '"
				+ entity("30000000", 21) + "'")).isZero();
	}

	@Test
	void retainsSegmentWideRetryForLegacyCampaignsWithoutAnExplicitSelection() {
		var created = service.create(ACTOR, command()).block();
		service.startPersonalization(created.id(), context()).block();
		sql("UPDATE campaigns SET generation_status = 'COMPLETED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'GENERATED', personalized_at = now()");
		addPaper(2);
		linkContact(2, 12);

		var retry = service.startPersonalization(created.id(), context()).block();

		assertThat(retry.queuedRecipients()).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE personalization_status = 'GENERATED'"))
				.isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaigns WHERE personalization_paper_ids IS NOT NULL")).isZero();
	}

	@Test
	void aCompletedExplicitSelectionCannotRegenerateOrExpandThroughABodylessRequest() {
		var created = service.create(ACTOR, command()).block();
		service.startPersonalization(created.id(), context(), List.of(entity("30000000", 1))).block();
		sql("UPDATE campaigns SET generation_status = 'COMPLETED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'GENERATED', personalized_at = now()");
		addPaper(2);
		linkContact(2, 12);

		assertThatThrownBy(() -> service.startPersonalization(created.id(), context()).block())
				.isInstanceOf(CampaignValidationException.class);
		assertThat(service.get(created.id()).block().generationStatus()).isEqualTo("COMPLETED");
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		assertThat(longValue("SELECT cardinality(personalization_paper_ids)::bigint FROM campaigns")).isEqualTo(1);
	}

	@Test
	void movesASharedAuthorToThePaperWithNoAlternative() {
		addPaper(2);
		linkContact(2, 1);
		linkContact(1, 4);
		var created = service.create(ACTOR, command()).block();

		var started = service.startPersonalization(created.id(), context(),
				List.of(entity("30000000", 1), entity("30000000", 2))).block();

		assertThat(started.queuedRecipients()).isEqualTo(2);
		assertThat(text("SELECT encode(email_hmac, 'hex') FROM campaign_recipients WHERE paper_id = '"
				+ entity("30000000", 2) + "'")).isEqualTo("01");
		assertThat(text("SELECT encode(email_hmac, 'hex') FROM campaign_recipients WHERE paper_id = '"
				+ entity("30000000", 1) + "'")).isEqualTo("0004");
	}

	@Test
	void rollsBackTheWholeBatchWhenDistinctContactsCannotCoverEveryPaper() {
		addPaper(2);
		linkContact(2, 1);
		var created = service.create(ACTOR, command()).block();

		assertThatThrownBy(() -> service.startPersonalization(created.id(), context(),
				List.of(entity("30000000", 1), entity("30000000", 2))).block())
				.isInstanceOf(CampaignValidationException.class).hasMessageContaining("distinct eligible contact");
		assertNoQueuedGeneration(created.id());
	}

	@Test
	void rejectsMissingDeletedAndSuppressedPapersWithoutPartiallyQueuing() {
		addPaper(2);
		linkContact(2, 12);
		var created = service.create(ACTOR, command()).block();
		List<UUID> papers = List.of(entity("30000000", 1), entity("30000000", 2));

		assertThatThrownBy(() -> service.startPersonalization(created.id(), context(),
				List.of(entity("30000000", 1), UUID.randomUUID())).block())
				.isInstanceOf(CampaignValidationException.class);
		assertNoQueuedGeneration(created.id());
		sql("UPDATE papers SET deleted_at = now() WHERE id = '" + entity("30000000", 2) + "'");
		assertThatThrownBy(() -> service.startPersonalization(created.id(), context(), papers).block())
				.isInstanceOf(CampaignValidationException.class);
		assertNoQueuedGeneration(created.id());
		sql("UPDATE papers SET deleted_at = NULL WHERE id = '" + entity("30000000", 2) + "'");
		sql("INSERT INTO suppression_entries(email_hmac, email_domain, reason, source) "
				+ "VALUES (decode('000c','hex'), 'research.edu', 'MANUAL', 'TEST')");
		assertThatThrownBy(() -> service.startPersonalization(created.id(), context(), papers).block())
				.isInstanceOf(CampaignValidationException.class);
		assertNoQueuedGeneration(created.id());
	}

	@Test
	void explicitSelectionCannotBypassSegmentCriteria() {
		addPaper(2);
		linkContact(2, 12);
		sql("UPDATE paper_author_contacts SET verification_status = 'UNVERIFIED' WHERE paper_id = '"
				+ entity("30000000", 2) + "'");
		var created = service.create(ACTOR, command()).block();

		assertThatThrownBy(() -> service.startPersonalization(created.id(), context(),
				List.of(entity("30000000", 2))).block()).isInstanceOf(CampaignValidationException.class);
		assertNoQueuedGeneration(created.id());
	}

	@Test
	void preservesAnExactPreviousSelectionOnFailureRetryButRejectsChangingIt() {
		addPaper(2);
		linkContact(2, 1);
		linkContact(1, 4);
		var created = service.create(ACTOR, command()).block();
		List<UUID> papers = List.of(entity("30000000", 1), entity("30000000", 2));
		service.startPersonalization(created.id(), context(), papers).block();
		sql("UPDATE campaigns SET generation_status = 'PARTIALLY_FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'GENERATED', personalized_at = now() "
				+ "WHERE paper_id = '" + entity("30000000", 1) + "'");

		assertThatThrownBy(() -> service.startPersonalization(created.id(), context(),
				List.of(entity("30000000", 1))).block()).isInstanceOf(CampaignValidationException.class)
				.hasMessageContaining("paper selection is fixed");
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		var retry = service.startPersonalization(created.id(), context(), List.of(papers.get(1), papers.get(0))).block();
		assertThat(retry.queuedRecipients()).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE personalization_status = 'GENERATED'"))
				.isEqualTo(1);
	}

	@Test
	void rejectsEmptyDuplicateNullAndOversizedSelectionsBeforeMutation() {
		var created = service.create(ACTOR, command()).block();
		List<List<UUID>> invalid = List.of(List.of(), List.of(entity("30000000", 1), entity("30000000", 1)),
				java.util.Arrays.asList(entity("30000000", 1), null),
				IntStream.range(1, 22).mapToObj(index -> entity("30000000", index)).toList());
		for (List<UUID> selection : invalid) {
			assertThatThrownBy(() -> service.startPersonalization(created.id(), context(), selection).block())
					.isInstanceOf(CampaignValidationException.class);
		}
		assertNoQueuedGeneration(created.id());
	}

	private void assertNoQueuedGeneration(UUID campaignId) {
		assertThat(service.get(campaignId).block().generationStatus()).isEqualTo("NOT_REQUESTED");
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isZero();
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isZero();
		assertThat(longValue("SELECT count(*) FROM campaigns WHERE personalization_paper_ids IS NOT NULL")).isZero();
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("127.0.0.1", "JUnit", "paperselectiontest");
	}

	@Test
	void productionSelectionQueuesExactly120UniqueAuthorsDespiteTheLegacyRuntimeLimitOf20() {
		service = productionService();
		addStrictAuthors(121);
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThat(service.startPersonalization(campaign, context(), null, 120).block().queuedRecipients()).isEqualTo(120);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(120);
		assertThat(longValue("SELECT count(DISTINCT author_id) FROM campaign_recipients")).isEqualTo(120);
		assertThat(longValue("SELECT count(DISTINCT email_hmac) FROM campaign_recipients")).isEqualTo(120);
		assertThat(longValue("SELECT personalization_recipient_limit::bigint FROM campaigns")).isEqualTo(120);
		assertThat(longValue("SELECT jsonb_array_length(payload->'payload'->'targets')::bigint FROM outbox_messages")).isEqualTo(120);
		assertThat(text("SELECT payload::text FROM outbox_messages")).doesNotContain("research.edu", "emailHmac", "emailCiphertext");
	}

	@Test
	void productionSelectionAllowsSourceSupportedHighWithoutHumanReviewAndConfirmedHumanMediumOnly() {
		service = productionService();
		sql("DELETE FROM segment_rules WHERE segment_id = '" + SEGMENT
				+ "' AND field_name IN ('confidence', 'verificationStatus')");
		addStrictAuthors(4);
		sql("UPDATE paper_author_contacts SET human_verified = false, verification_status = 'UNVERIFIED' "
				+ "WHERE contact_id = '" + entity("60000000", 102) + "'");
		sql("UPDATE paper_author_contacts SET confidence = 'MEDIUM' "
				+ "WHERE contact_id = '" + entity("60000000", 103) + "'");
		sql("UPDATE paper_author_contacts SET confidence = 'MEDIUM', human_verified = false "
				+ "WHERE contact_id = '" + entity("60000000", 104) + "'");
		sql("UPDATE paper_author_contacts SET verification_status = 'REJECTED' "
				+ "WHERE contact_id = '" + entity("60000000", 105) + "'");
		UUID campaign = service.create(ACTOR, command()).block().id();

		assertThat(service.startPersonalization(campaign, context(), null, 2).block().queuedRecipients()).isEqualTo(2);
		assertThat(text("SELECT string_agg(contact_id::text, ',' ORDER BY contact_id) FROM campaign_recipients"))
				.isEqualTo(entity("60000000", 102) + "," + entity("60000000", 103));

		UUID tooMany = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> service.startPersonalization(tooMany, context(), null, 3).block())
				.isInstanceOf(CampaignValidationException.class).hasMessageContaining("no recipients were queued");
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE campaign_id = '" + tooMany + "'"))
				.isZero();
		assertThat(longValue("SELECT count(*) FROM outbox_messages WHERE aggregate_id = '" + tooMany + "'"))
				.isZero();
		assertThat(text("SELECT generation_status FROM campaigns WHERE id = '" + tooMany + "'"))
				.isEqualTo("NOT_REQUESTED");
	}

	@Test
	void insufficientProductionEvidenceRollsBackAllRecipientsOutboxAndModeMetadata() {
		service = productionService();
		addStrictAuthors(2);
		sql("UPDATE paper_author_contacts SET confidence = 'LOW' WHERE contact_id = '" + entity("60000000", 102) + "'");
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> service.startPersonalization(campaign, context(), null, 2).block())
				.isInstanceOf(CampaignValidationException.class).hasMessageContaining("no recipients were queued");
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isZero();
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isZero();
		assertThat(text("SELECT generation_status FROM campaigns")).isEqualTo("NOT_REQUESTED");
		assertThat(longValue("SELECT count(*) FROM campaigns WHERE personalization_recipient_limit IS NOT NULL")).isZero();
	}

	@Test
	void productionSelectionDeduplicatesAuthorAndAddressAndUsesAlternativeEdges() {
		service = productionService();
		addPaper(2);
		addPaper(3);
		addPaper(4);
		linkContact(2, 10);
		linkContact(4, 11);
		linkContact(3, 10);
		// Author 10 has two addresses; author 11 shares the first address through another paper.
		sql("UPDATE paper_authors SET author_id = '" + entity("40000000", 10)
				+ "' WHERE id = '" + entity("41000000", 4011) + "'");
		sql("UPDATE paper_authors SET author_id = '" + entity("40000000", 11)
				+ "' WHERE id = '" + entity("41000000", 3010) + "'");
		addEvidence();
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThat(service.startPersonalization(campaign, context(), null, 2).block().queuedRecipients()).isEqualTo(2);
		assertThat(longValue("SELECT count(DISTINCT author_id) FROM campaign_recipients")).isEqualTo(2);
		assertThat(longValue("SELECT count(DISTINCT email_hmac) FROM campaign_recipients")).isEqualTo(2);
		UUID tooLarge = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> service.startPersonalization(tooLarge, context(), null, 3).block())
				.isInstanceOf(CampaignValidationException.class);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE campaign_id = '" + tooLarge + "'")).isZero();
	}

	@Test
	void productionSelectionExcludesDefaultAndNamedOwnerDestinationsUsingContactHmac() {
		service = productionService();
		addStrictAuthors(5);
		int contact = 102;
		for (String address : List.of("owner@example.test", "qq@example.test", "gmail@example.test")) {
			String hmac = HexFormat.of().formatHex(testCrypto().hmac(address));
			sql("UPDATE contacts SET email_hmac = decode('" + hmac + "', 'hex') WHERE id = '" + entity("60000000", contact++) + "'");
		}
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThat(service.startPersonalization(campaign, context(), null, 2).block().queuedRecipients()).isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE contact_id IN ('"
				+ entity("60000000", 102) + "','" + entity("60000000", 103) + "','" + entity("60000000", 104) + "')")).isZero();
	}

	@Test
	void productionRetryReusesFrozenPublicInputsAndNeverExpandsOrRegeneratesSuccessfulTargets() {
		service = productionService();
		addStrictAuthors(2);
		UUID campaign = service.create(ACTOR, command()).block().id();
		service.startPersonalization(campaign, context(), null, 2).block();
		sql("UPDATE paper_author_contacts SET human_verified = false, verification_status = 'UNVERIFIED' "
				+ "WHERE contact_id = '" + entity("60000000", 102) + "'");
		sql("UPDATE paper_author_contacts SET confidence = 'MEDIUM' "
				+ "WHERE contact_id = '" + entity("60000000", 103) + "'");
		String originalRows = text("SELECT string_agg(id::text || contact_id::text || paper_id::text || author_id::text || encode(email_hmac, 'hex'), ',' ORDER BY id) FROM campaign_recipients");
		sql("UPDATE campaigns SET generation_status = 'PARTIALLY_FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'GENERATED', personalized_at = now(), rendered_subject = 'Preserved successful subject'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED' WHERE paper_id = '" + entity("30000000", 2) + "'");
		sql("UPDATE papers SET title = 'Changed paper', abstract_text = 'Changed abstract'");
		sql("UPDATE paper_authors SET raw_name = 'Changed author'");
		sql("UPDATE contacts SET email_ciphertext = decode('99', 'hex'), email_domain = 'changed.example.test'");
		addPaper(4);
		linkContact(4, 104);
		addEvidence();
		var retry = service.startPersonalization(campaign, context()).block();
		assertThat(retry.queuedRecipients()).isEqualTo(1);
		assertThat(text("SELECT string_agg(id::text || contact_id::text || paper_id::text || author_id::text || encode(email_hmac, 'hex'), ',' ORDER BY id) FROM campaign_recipients")).isEqualTo(originalRows);
		String retryPayload = text("SELECT payload::text FROM outbox_messages WHERE idempotency_key = 'personalization:"
				+ campaign + ":" + retry.jobId() + "'");
		assertThat(retryPayload).contains("Agent Study 2", "Public abstract for agent study 2", "Researcher 102")
				.doesNotContain("Changed paper", "Changed abstract", "Changed author", "Agent Study 4");
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE personalization_status = 'GENERATED' AND rendered_subject = 'Preserved successful subject'")).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE email_domain = 'research.edu' AND encode(email_ciphertext, 'hex') <> '99'")).isEqualTo(2);
	}

	@Test
	void productionRetryRejectsFrozenRecipientWhoseLatestSourceEvidenceWasDowngraded() {
		service = productionService();
		addStrictAuthors(1);
		UUID campaign = service.create(ACTOR, command()).block().id();
		service.startPersonalization(campaign, context(), null, 1).block();
		sql("UPDATE campaigns SET generation_status = 'FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		UUID latestRun = entity("50000000", 999);
		sql("""
				INSERT INTO extraction_runs(id, paper_id, parser_version, status, started_at, completed_at)
				VALUES ('%s', '%s', '2.0', 'SUCCEEDED', now(), now() + interval '1 second')
				""".formatted(latestRun, entity("30000000", 2)));
		sql("""
				INSERT INTO paper_author_contacts(paper_author_id, paper_id, contact_id, extraction_run_id,
				  confidence, corresponding_author, human_verified, verification_status)
				SELECT paper_author_id, paper_id, contact_id, '%s', 'LOW', true, false, 'UNVERIFIED'
				FROM paper_author_contacts WHERE extraction_run_id = '%s'
				""".formatted(latestRun, entity("50000000", 2)));

		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block())
				.isInstanceOf(CampaignValidationException.class)
				.hasMessageContaining("selection changed or is not retryable");
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		assertThat(text("SELECT generation_status FROM campaigns WHERE id = '" + campaign + "'"))
				.isEqualTo("FAILED");
		assertThat(text("SELECT personalization_status FROM campaign_recipients WHERE campaign_id = '"
				+ campaign + "'")).isEqualTo("FAILED");
	}

	@Test
	void productionRetryRejectsFrozenRecipientWhosePaperWasSoftDeleted() {
		service = productionService();
		addStrictAuthors(1);
		UUID campaign = service.create(ACTOR, command()).block().id();
		service.startPersonalization(campaign, context(), null, 1).block();
		sql("UPDATE campaigns SET generation_status = 'FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		sql("UPDATE papers SET deleted_at = now() WHERE id = '" + entity("30000000", 2) + "'");

		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block())
				.isInstanceOf(CampaignValidationException.class)
				.hasMessageContaining("selection changed or is not retryable");
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		assertThat(text("SELECT generation_status FROM campaigns WHERE id = '" + campaign + "'"))
				.isEqualTo("FAILED");
		assertThat(text("SELECT personalization_status FROM campaign_recipients WHERE campaign_id = '"
				+ campaign + "'")).isEqualTo("FAILED");
	}

	@Test
	void productionModeCannotChangeLimitOrSwitchToPaperSelectionOrAdoptAnExistingLegacyBatch() {
		service = productionService();
		addStrictAuthors(3);
		UUID campaign = service.create(ACTOR, command()).block().id();
		service.startPersonalization(campaign, context(), null, 2).block();
		assertThatThrownBy(() -> service.startPersonalization(campaign, context(), null, 3).block()).isInstanceOf(CampaignValidationException.class);
		assertThatThrownBy(() -> service.startPersonalization(campaign, context(), List.of(entity("30000000", 2))).block()).isInstanceOf(CampaignValidationException.class);
		UUID legacy = service.create(ACTOR, command()).block().id();
		service.startPersonalization(legacy, context()).block();
		assertThatThrownBy(() -> service.startPersonalization(legacy, context(), null, 2).block()).isInstanceOf(CampaignValidationException.class);
		UUID paper = service.create(ACTOR, command()).block().id();
		service.startPersonalization(paper, context(), List.of(entity("30000000", 2))).block();
		assertThatThrownBy(() -> service.startPersonalization(paper, context(), null, 1).block()).isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void concurrentProductionModeChangesAreSerializedAndOnlyOneCompleteSelectionCommits() {
		service = productionService();
		addStrictAuthors(3);
		UUID campaign = service.create(ACTOR, command()).block().id();
		var outcomes = reactor.core.publisher.Flux.merge(
				service.startPersonalization(campaign, context(), null, 2).materialize(),
				service.startPersonalization(campaign, context(), null, 3).materialize()).collectList().block();
		assertThat(outcomes.stream().filter(reactor.core.publisher.Signal::isOnNext).count()).isEqualTo(1);
		assertThat(outcomes.stream().filter(reactor.core.publisher.Signal::isOnError).count()).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients"))
				.isEqualTo(longValue("SELECT personalization_recipient_limit::bigint FROM campaigns"));
	}

	@Test
	void aNewerUnassignedOrDifferentAuthorMappingCannotResurrectOldConfirmedEvidence() {
		service = productionService();
		addStrictAuthors(1);
		UUID campaign = service.create(ACTOR, command()).block().id();
		UUID latestRun = entity("50000000", 999);
		sql("""
				INSERT INTO extraction_runs(id, paper_id, parser_version, status, started_at, completed_at)
				VALUES ('%s', '%s', '2.0', 'SUCCEEDED', now(), now() + interval '1 second')
				""".formatted(latestRun, entity("30000000", 2)));
		sql("""
				INSERT INTO paper_author_contacts(paper_id, contact_id, extraction_run_id, confidence,
				  corresponding_author, human_verified, verification_status)
				VALUES ('%s', '%s', '%s', 'LOW', true, false, 'UNVERIFIED')
				""".formatted(entity("30000000", 2), entity("60000000", 102), latestRun));
		assertThatThrownBy(() -> service.startPersonalization(campaign, context(), null, 1).block())
				.isInstanceOf(CampaignValidationException.class);
		linkContact(2, 103);
		sql("UPDATE paper_author_contacts SET paper_author_id = '" + entity("41000000", 2103)
				+ "' WHERE extraction_run_id = '" + latestRun + "'");
		assertThatThrownBy(() -> service.startPersonalization(campaign, context(), null, 1).block())
				.isInstanceOf(CampaignValidationException.class);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isZero();
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isZero();
	}

	@Test
	void concurrentPaperAndProductionSelectorsCannotMergeTheirMembershipOrModes() {
		service = productionService();
		addStrictAuthors(3);
		UUID campaign = service.create(ACTOR, command()).block().id();
		var outcomes = reactor.core.publisher.Flux.merge(
				service.startPersonalization(campaign, context(), null, 3).materialize(),
				service.startPersonalization(campaign, context(), List.of(entity("30000000", 2))).materialize())
				.collectList().block();
		assertThat(outcomes.stream().filter(reactor.core.publisher.Signal::isOnNext).count()).isEqualTo(1);
		assertThat(outcomes.stream().filter(reactor.core.publisher.Signal::isOnError).count()).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients"))
				.isEqualTo(longValue("SELECT coalesce(personalization_recipient_limit, cardinality(personalization_paper_ids))::bigint FROM campaigns"));
	}

	@Test
	void missingFrozenRecipientIsRejectedRatherThanReplacedFromNewCandidates() {
		service = productionService();
		addStrictAuthors(3);
		UUID campaign = service.create(ACTOR, command()).block().id();
		service.startPersonalization(campaign, context(), null, 2).block();
		sql("UPDATE campaigns SET generation_status = 'FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		sql("DELETE FROM campaign_recipients WHERE id = (SELECT id FROM campaign_recipients ORDER BY id LIMIT 1)");
		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block())
				.isInstanceOf(CampaignValidationException.class).hasMessageContaining("no recipients were added or replaced");
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
	}

	private void addStrictAuthors(int count) {
		for (int paper = 2; paper < count + 2; paper++) {
			addPaper(paper);
			linkContact(paper, paper + 100);
		}
		addEvidence();
	}

	@Test
	void paperContactModePreservesLowAndUnverifiedEvidenceAndFreezesRetries() {
		service = productionService();
		addPaper(2);
		linkContact(2, 102);
		linkContact(2, 103);
		linkContact(2, 104);
		addEvidence();
		sql("UPDATE paper_author_contacts SET confidence = 'LOW', paper_author_id = NULL, human_verified = false, verification_status = 'UNVERIFIED' WHERE contact_id = '" + entity("60000000", 103) + "'");
		sql("UPDATE paper_author_contacts SET confidence = 'MEDIUM', human_verified = false, verification_status = 'UNVERIFIED' WHERE contact_id = '" + entity("60000000", 104) + "'");
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThat(paperContacts(campaign, List.of(entity("30000000", 2))).queuedRecipients()).isEqualTo(3);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients WHERE author_id IS NULL AND author_name_snapshot IS NULL")).isEqualTo(2);
		assertThat(text("SELECT confidence FROM campaign_recipients WHERE contact_id = '" + entity("60000000", 103) + "'")).isEqualTo("LOW");
		assertThat(longValue("SELECT count(*) FROM paper_author_contacts WHERE confidence = 'LOW' AND NOT human_verified AND paper_author_id IS NULL")).isEqualTo(1);
		sql("UPDATE campaigns SET generation_status = 'FAILED' WHERE id = '" + campaign + "'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		sql("UPDATE papers SET title = 'Changed public title'");
		linkContact(2, 105);
		addEvidence();
		assertThat(paperContacts(campaign, List.of(entity("30000000", 2))).queuedRecipients()).isEqualTo(3);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(3);
		assertThat(longValue("SELECT count(*) FROM outbox_messages WHERE payload::text LIKE '%Changed public title%'")).isZero();
	}

	@Test
	void paperContactsDeduplicateAcrossPapersAndReserveAcrossDrafts() {
		service = productionService();
		addPaper(2); addPaper(3);
		linkContact(2, 102); linkContact(3, 102); addEvidence();
		UUID first = service.create(ACTOR, command()).block().id();
		UUID second = service.create(ACTOR, command()).block().id();
		assertThat(paperContacts(first, List.of(entity("30000000", 2), entity("30000000", 3))).queuedRecipients()).isEqualTo(1);
		assertThatThrownBy(() -> paperContacts(second, List.of(entity("30000000", 3)))).isInstanceOf(CampaignValidationException.class);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(1);
	}

	@Test
	void paperContactsRejectOverflowWithoutTruncationOrWrites() {
		service = productionService(); addPaper(2);
		for (int contact = 102; contact < 223; contact++) linkContact(2, contact);
		addEvidence();
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> paperContacts(campaign, List.of(entity("30000000", 2)))).isInstanceOf(CampaignValidationException.class);
		assertNoQueuedGeneration(campaign);
	}

	private CampaignService.GenerationStart paperContacts(UUID campaign, List<UUID> papers) {
		return service.startPaperContactPersonalization(campaign, context(), papers).block();
	}

	@Test
	void paperContactFrozenRetryRejectsMissingMemberRatherThanShrinkingBatch() {
		service = productionService(); addPaper(2);
		linkContact(2, 102); linkContact(2, 103); addEvidence();
		UUID campaign = service.create(ACTOR, command()).block().id();
		paperContacts(campaign, List.of(entity("30000000", 2)));
		sql("UPDATE campaigns SET generation_status = 'FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		sql("DELETE FROM campaign_recipients WHERE contact_id = '" + entity("60000000", 103) + "'");
		assertThatThrownBy(() -> paperContacts(campaign, List.of(entity("30000000", 2)))).isInstanceOf(CampaignValidationException.class);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
	}

	@Test
	void legacyGenerationRejectsUnknownIdentityInsteadOfEmittingNullableAuthor() {
		sql("UPDATE paper_author_contacts SET paper_author_id = NULL, confidence = 'HIGH'");
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block()).isInstanceOf(CampaignValidationException.class);
		assertNoQueuedGeneration(campaign);
	}

	@Test
	void paperContactRetryRejectsStaleNamedIdentityWhenLatestMappingBecomesUnreliable() {
		service = productionService(); addStrictAuthors(1);
		UUID campaign = service.create(ACTOR, command()).block().id();
		paperContacts(campaign, List.of(entity("30000000", 2)));
		sql("UPDATE campaigns SET generation_status = 'FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		sql("UPDATE paper_author_contacts SET paper_author_id = NULL, confidence = 'LOW', human_verified = false, verification_status = 'UNVERIFIED'");
		assertThatThrownBy(() -> paperContacts(campaign, List.of(entity("30000000", 2)))).isInstanceOf(CampaignValidationException.class);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"SMTP_ACCEPTED", "PERMANENT_FAILURE", "SUPPRESSED", "CANCELED"})
	void terminalPaperContactHistoryDoesNotPermanentlyReserveAddresses(String deliveryStatus) {
		service = productionService(); addStrictAuthors(1);
		sql("UPDATE contacts SET email_hmac = digest(email_hmac, 'sha256')");
		UUID previous = service.create(ACTOR, command()).block().id();
		paperContacts(previous, List.of(entity("30000000", 2)));
		sql("UPDATE campaigns SET status = 'COMPLETED' WHERE id = '" + previous + "'");
		sql("UPDATE campaign_recipients SET status = '" + deliveryStatus + "'");
		if ("SMTP_ACCEPTED".equals(deliveryStatus)) {
			sql("INSERT INTO recipient_delivery_cooldowns(email_hmac, last_smtp_accepted_at) SELECT email_hmac, now() - interval '181 days' FROM campaign_recipients");
		}
		UUID next = service.create(ACTOR, command()).block().id();
		assertThat(paperContacts(next, List.of(entity("30000000", 2))).queuedRecipients()).isEqualTo(1);
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"COOLDOWN", "CONNECTING", "OUTCOME_UNKNOWN"})
	void terminalCampaignDoesNotBypassCooldownOrUnresolvedOutcome(String blocker) {
		service = productionService(); addStrictAuthors(1);
		sql("UPDATE contacts SET email_hmac = digest(email_hmac, 'sha256')");
		UUID previous = service.create(ACTOR, command()).block().id();
		paperContacts(previous, List.of(entity("30000000", 2)));
		sql("UPDATE campaigns SET status = 'COMPLETED' WHERE id = '" + previous + "'");
		if ("COOLDOWN".equals(blocker)) {
			sql("UPDATE campaign_recipients SET status = 'SMTP_ACCEPTED'");
			sql("INSERT INTO recipient_delivery_cooldowns(email_hmac, last_smtp_accepted_at) SELECT email_hmac, now() - interval '179 days' FROM campaign_recipients");
		} else sql("UPDATE campaign_recipients SET status = '" + blocker + "'");
		UUID next = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> paperContacts(next, List.of(entity("30000000", 2)))).isInstanceOf(CampaignValidationException.class);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
	}

	@Test
	void concurrentPaperContactDraftsReserveSharedEmailOnlyOnce() {
		service = productionService(); addStrictAuthors(1);
		UUID first = service.create(ACTOR, command()).block().id();
		UUID second = service.create(ACTOR, command()).block().id();
		var results = reactor.core.publisher.Flux.merge(
				service.startPaperContactPersonalization(first, context(), List.of(entity("30000000", 2))).materialize(),
				service.startPaperContactPersonalization(second, context(), List.of(entity("30000000", 2))).materialize()).collectList().block();
		assertThat(results.stream().filter(reactor.core.publisher.Signal::isOnNext).count()).isEqualTo(1);
		assertThat(results.stream().filter(reactor.core.publisher.Signal::isOnError).count()).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
	}

	@Test
	void paperContactFrozenRetryRejectsNewlyRejectedEvidenceAndModeSwitches() {
		service = productionService(); addStrictAuthors(1);
		UUID campaign = service.create(ACTOR, command()).block().id();
		paperContacts(campaign, List.of(entity("30000000", 2)));
		sql("UPDATE campaigns SET generation_status = 'FAILED'");
		sql("UPDATE campaign_recipients SET personalization_status = 'FAILED'");
		assertThatThrownBy(() -> service.startPersonalization(campaign, context()).block()).isInstanceOf(CampaignValidationException.class);
		sql("UPDATE paper_author_contacts SET verification_status = 'REJECTED'");
		assertThatThrownBy(() -> paperContacts(campaign, List.of(entity("30000000", 2)))).isInstanceOf(CampaignValidationException.class);
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isEqualTo(1);
		assertThat(text("SELECT generation_status FROM campaigns")).isEqualTo("FAILED");
	}

	@Test
	void oversizedNonAsciiProductionMessageRollsBackUsingUtf8BytesNotCharacterCount() {
		service = productionService();
		addStrictAuthors(16);
		// 318,400 abstract characters, but 955,200 UTF-8 bytes: exceeds the 900 KiB envelope.
		sql("UPDATE papers SET abstract_text = repeat('研', 19900)");
		UUID campaign = service.create(ACTOR, command()).block().id();
		assertThatThrownBy(() -> service.startPersonalization(campaign, context(), null, 16).block())
				.isInstanceOf(CampaignValidationException.class)
				.hasMessage("Production personalization message is too large; use a smaller batch on a new draft");
		assertThat(longValue("SELECT count(*) FROM campaign_recipients")).isZero();
		assertThat(longValue("SELECT count(*) FROM outbox_messages")).isZero();
		assertThat(longValue("SELECT count(*) FROM campaigns WHERE personalization_recipient_limit IS NOT NULL")).isZero();
		assertThat(text("SELECT generation_status FROM campaigns")).isEqualTo("NOT_REQUESTED");
	}

	private void addEvidence() {
		sql("""
				INSERT INTO extraction_evidence(paper_author_contact_id, source_relative_path, rule_name, masked_context)
				SELECT pac.id, 'main.tex', 'explicit_author_email', 'Author and masked address in source'
				FROM paper_author_contacts pac
				WHERE pac.contact_id <> '60000000-0000-0000-0000-000000000001'
				AND NOT EXISTS (SELECT 1 FROM extraction_evidence ee WHERE ee.paper_author_contact_id = pac.id)
				""");
	}

	private ContactCrypto testCrypto() {
		byte[] encryptionKey = new byte[32];
		byte[] hmacKey = new byte[32];
		java.util.Arrays.fill(encryptionKey, (byte) 11);
		java.util.Arrays.fill(hmacKey, (byte) 22);
		return new ContactCrypto(new ContactDataProtectionProperties(
				Base64.getEncoder().encodeToString(encryptionKey), Base64.getEncoder().encodeToString(hmacKey)));
	}

	private CampaignService productionService() {
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		return new CampaignService(new CampaignRepository(databaseClient), new SegmentRepository(databaseClient, mapper),
				new PersonalizationProperties(true, "openai", "test-model", 20), mapper,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)), new CampaignPublicContentRedactor(),
				new CampaignProductionSelectionPolicy(testCrypto(), new CampaignSafetyProperties(false, "Owner@example.test", 20,
						Map.of("qq", "qq@example.test", "gmail", "gmail@example.test")), Duration.ofDays(180)));
	}

	private UUID entity(String prefix, int number) {
		return UUID.fromString("%s-0000-0000-0000-%012d".formatted(prefix, number));
	}

	private void addPaper(int number) {
		String arxivId = "2608.%05d".formatted(number);
		sql("""
				INSERT INTO papers (id, arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at, pdf_url)
				VALUES ('%s', '%s', 'Agent Study %d', 'Public abstract for agent study %d',
				        '21000000-0000-0000-0000-000000000001', now(), now(), 'https://arxiv.org/pdf/%s')
				""".formatted(entity("30000000", number), arxivId, number, number, arxivId));
		sql("""
				INSERT INTO extraction_runs(id, paper_id, parser_version, status, started_at, completed_at)
				VALUES ('%s', '%s', '1.0', 'SUCCEEDED', now(), now())
				""".formatted(entity("50000000", number), entity("30000000", number)));
	}

	private void linkContact(int paper, int contact) {
		UUID paperId = entity("30000000", paper);
		UUID authorId = entity("40000000", contact);
		UUID contactId = entity("60000000", contact);
		UUID paperAuthorId = entity("41000000", paper * 1000 + contact);
		String hex = "%04x".formatted(contact);
		sql("""
				INSERT INTO authors(id, normalized_name, display_name)
				VALUES ('%s', 'researcher %d', 'Researcher %d') ON CONFLICT (id) DO NOTHING
				""".formatted(authorId, contact, contact));
		sql("""
				INSERT INTO contacts (id, email_ciphertext, email_nonce, email_hmac, email_domain,
				                      display_ciphertext, display_nonce, syntax_valid, example_address)
				VALUES ('%s', decode('%s','hex'), decode('11','hex'), decode('%s','hex'), 'research.edu',
				        decode('22','hex'), decode('33','hex'), true, false) ON CONFLICT (id) DO NOTHING
				""".formatted(contactId, hex, hex));
		sql("""
				INSERT INTO paper_authors (id, paper_id, author_id, author_order, corresponding_author, raw_name)
				VALUES ('%s', '%s', '%s', %d, true, 'Researcher %d')
				""".formatted(paperAuthorId, paperId, authorId, contact, contact));
		sql("""
				INSERT INTO paper_author_contacts (paper_author_id, paper_id, contact_id, extraction_run_id,
				                                 confidence, corresponding_author, human_verified, verification_status)
				VALUES ('%s', '%s', '%s', '%s', 'HIGH', true, true, 'CONFIRMED')
				""".formatted(paperAuthorId, paperId, contactId, entity("50000000", paper)));
	}

	private CampaignService.CampaignCommand command() {
		return new CampaignService.CampaignCommand(
				"Paper-aware outreach", "Invite the author to discuss safe distributed AI.",
				TEMPLATE, SEGMENT, SMTP);
	}

	private void seedCampaignDependencies() {
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
				INSERT INTO email_template_versions
				(id, template_id, version_number, subject_template, from_name_template, reply_to,
				 html_content, text_content, content_size_bytes, validation_result, created_by)
				VALUES ('70100000-0000-0000-0000-000000000001',
				        '70000000-0000-0000-0000-000000000001', 1, 'About {{paper_title}}',
				        'Research Team', 'reply@example.org',
				        '<p>Hello {{author_name}}</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
				        'Hello {{author_name}} {{unsubscribe_url}}', 120, '{"valid":true}',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO smtp_accounts
				(id, name, host, port, tls_mode, from_email, default_from_name, reply_to,
				 per_minute_limit, per_hour_limit, per_day_limit, per_domain_hour_limit, enabled,
				 last_tested_at, last_test_status, created_by)
				VALUES ('72000000-0000-0000-0000-000000000001', 'Internal Mailpit', 'mailpit', 1025,
				        'PLAIN_LOCAL_ONLY', 'research@example.org', 'Research Team', 'reply@example.org',
				        10, 100, 500, 50, true, now(), 'SUCCEEDED',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO segments (id, name, description, created_by)
				VALUES ('71000000-0000-0000-0000-000000000001', 'Confirmed AI', 'Safe contacts',
				        '10000000-0000-0000-0000-000000000001')
				""");
		sql("""
				INSERT INTO segment_rules (segment_id, rule_order, field_name, operator, value_data)
				VALUES
				('71000000-0000-0000-0000-000000000001', 1, 'primaryCategory', 'equals', '"cs.AI"'),
				('71000000-0000-0000-0000-000000000001', 2, 'confidence', 'equals', '"HIGH"'),
				('71000000-0000-0000-0000-000000000001', 3, 'verificationStatus', 'equals', '"CONFIRMED"'),
				('71000000-0000-0000-0000-000000000001', 4, 'corresponding', 'equals', 'true')
				""");
		sql("""
				INSERT INTO arxiv_groups (id, group_id, group_name)
				VALUES ('20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science')
				""");
		sql("""
				INSERT INTO arxiv_categories
				(id, group_ref_id, group_id, group_name, category_id, category_name)
				VALUES ('21000000-0000-0000-0000-000000000001',
				        '20000000-0000-0000-0000-000000000001', 'cs', 'Computer Science',
				        'cs.AI', 'Artificial Intelligence')
				""");
		sql("""
				INSERT INTO papers
				(id, arxiv_id, title, abstract_text, primary_category_id, submitted_at, updated_at, pdf_url)
				VALUES ('30000000-0000-0000-0000-000000000001', '2608.00001',
				        'Safe Distributed Intelligence', 'A public abstract about distributed intelligence.',
				        '21000000-0000-0000-0000-000000000001', now() - interval '1 day', now(),
				        'https://arxiv.org/pdf/2608.00001')
				""");
		sql("""
				INSERT INTO authors (id, normalized_name, display_name)
				VALUES ('40000000-0000-0000-0000-000000000001', 'ada lovelace', 'Ada Lovelace')
				""");
		sql("""
				INSERT INTO paper_authors
				(id, paper_id, author_id, author_order, corresponding_author, raw_name, affiliation_text)
				VALUES ('41000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001',
				        '40000000-0000-0000-0000-000000000001', 1, true,
				        'Ada Lovelace', 'Analytical Engine University')
				""");
		sql("""
				INSERT INTO extraction_runs
				(id, paper_id, parser_version, status, started_at, completed_at)
				VALUES ('50000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001', '1.0', 'SUCCEEDED', now(), now())
				""");
		insertContact("60000000-0000-0000-0000-000000000001", "01", "university.edu", false, "HIGH");
		insertContact("60000000-0000-0000-0000-000000000002", "02", "example.com", true, "HIGH");
		insertContact("60000000-0000-0000-0000-000000000003", "03", "blocked.edu", false, "HIGH");
		sql("""
				INSERT INTO suppression_entries (email_hmac, email_domain, reason, source)
				VALUES (decode('03', 'hex'), 'blocked.edu', 'MANUAL', 'TEST')
				""");
	}

	private void insertContact(String id, String hex, String domain, boolean example, String confidence) {
		sql("""
				INSERT INTO contacts
				(id, email_ciphertext, email_nonce, email_hmac, email_domain, display_ciphertext,
				 display_nonce, syntax_valid, example_address)
				VALUES ('%s', decode('%s', 'hex'), decode('11', 'hex'), decode('%s', 'hex'),
				        '%s', decode('22', 'hex'), decode('33', 'hex'), true, %s)
				""".formatted(id, hex, hex, domain, example));
		sql("""
				INSERT INTO paper_author_contacts
				(paper_author_id, paper_id, contact_id, extraction_run_id, confidence,
				 corresponding_author, human_verified, verification_status)
				VALUES ('41000000-0000-0000-0000-000000000001',
				        '30000000-0000-0000-0000-000000000001', '%s',
				        '50000000-0000-0000-0000-000000000001', '%s', true, true, 'CONFIRMED')
				""".formatted(id, confidence));
	}

	private void sql(String statement) {
		databaseClient.sql(statement).fetch().rowsUpdated().block();
	}

	private String text(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private long longValue(String statement) {
		return databaseClient.sql(statement).map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
