package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryExecutor;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import com.camel_hub.advertisement.campaign.delivery.CampaignOutboundPreparer;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.campaign.tracking.CampaignCallbackNamespace;
import com.camel_hub.advertisement.campaign.tracking.CampaignTrackingSigner;
import com.camel_hub.advertisement.campaign.tracking.CampaignUnsubscribeController;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.smtp.SmtpPolicy;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailClickController;
import com.camel_hub.advertisement.email.tracking.MailOpenController;
import com.camel_hub.advertisement.email.tracking.MailTrackingModels;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import com.camel_hub.advertisement.email.tracking.MailTrackingService;
import com.camel_hub.advertisement.email.tracking.MailTrackingSigner;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CampaignSafetyIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("campaign_safety_test").withUsername("camel").withPassword("camel-test-only");
	private static final Instant NOW = Instant.parse("2030-04-05T10:15:30Z");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID SMTP = UUID.fromString("72000000-0000-0000-0000-000000000001");
	private static final UUID CAMPAIGN = UUID.fromString("50000000-0000-0000-0000-000000000001");
	private static final String SAFETY_KEY = Base64.getEncoder().encodeToString(
			"campaign-safety-test-key-32-bytes!!".getBytes(StandardCharsets.US_ASCII));
	private static final String SMTP_ENCRYPTION_KEY = Base64.getEncoder().encodeToString(
			"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
	private static DatabaseClient database;
	private static TransactionalOperator transactions;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		var factory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
				+ POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName());
		database = DatabaseClient.create(factory);
		transactions = TransactionalOperator.create(new R2dbcTransactionManager(factory));
	}

	@BeforeEach
	void reset() {
		database.sql("TRUNCATE campaign_safety_events, campaign_safety_links, campaign_safety_attempts, "
				+ "campaign_safety_messages, campaign_safety_runs, campaign_recipients, campaigns, smtp_accounts, "
				+ "email_template_versions, email_templates, outbox_messages, audit_logs, users CASCADE")
				.fetch().rowsUpdated().block();
		seed();
	}

	@Test
	void atomicallyMaterializesOrderedGeneratedSnapshotsWithoutDestinationPlaintext() {
		UUID later = insertRecipient("ffffffff-0000-0000-0000-000000000002", "Second");
		UUID first = insertRecipient("00000000-0000-0000-0000-000000000001", "First");
		insertRecipient("11111111-0000-0000-0000-000000000003", "Not generated", "PENDING");
		byte[] destinationHmac = sha256("campaign-safety-destination:v1:fixed@example.test");
		CampaignSafetyRepository repository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());

		CampaignSafetyRepository.MaterializedRun run = repository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(
						CAMPAIGN, ACTOR, 0, 2, destinationHmac, "f***@example.test", NOW, "safetytrace1"))
				.block();

		assertThat(run).isNotNull();
		assertThat(run.recipientIds()).containsExactly(first, later);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_messages")).isEqualTo(2);
		assertThat(text("SELECT string_agg(rendered_subject, '|' ORDER BY campaign_recipient_id) "
				+ "FROM campaign_safety_messages")).contains("[SAFETY TEST]", "First", "Second");
		assertThat(text("SELECT encode(destination_hmac, 'hex') FROM campaign_safety_runs"))
				.isEqualTo(java.util.HexFormat.of().formatHex(destinationHmac));
		assertThat(text("SELECT destination_masked FROM campaign_safety_runs")).isEqualTo("f***@example.test");
		assertThat(text("SELECT payload::text FROM outbox_messages WHERE message_type = 'CAMPAIGN_DELIVERY_WAKEUP'"))
				.contains("SAFETY_START", run.id().toString()).doesNotContain("fixed@example.test", "First", "Second");
		assertThat(integer("SELECT count(*)::int FROM delivery_attempts")).isZero();
		assertThat(integer("SELECT count(*)::int FROM tracking_tokens")).isZero();
	}

	@Test
	void smtpHealthUpdateAndSafetyStartShareOneAtomicCampaignThenAccountLockBoundary() throws Exception {
		insertRecipient("00000000-0000-0000-0000-000000000004", "Atomic SMTP health");
		SafetyRuntime runtime = safetyRuntime();
		try (Connection gate = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			gate.setAutoCommit(false);
			try (var statement = gate.createStatement()) {
				statement.execute("SELECT id FROM smtp_accounts WHERE id = '" + SMTP + "' FOR UPDATE");
				statement.execute("UPDATE smtp_accounts SET last_test_status = 'FAILED' WHERE id = '" + SMTP + "'");
			}
			CompletableFuture<CampaignSafetyRepository.MaterializedRun> start = reactor.core.publisher.Mono.defer(
					() -> runtime.repository().materialize(new CampaignSafetyRepository.MaterializeCommand(
							CAMPAIGN, ACTOR, 0, 1, runtime.policy().requireReady().hmac(),
							runtime.policy().requireReady().masked(), NOW, "safetyatomicsmtp")))
					.subscribeOn(Schedulers.boundedElastic()).toFuture();

			assertThat(awaitDatabaseLockWait()).isTrue();
			gate.commit();
			assertThatThrownBy(() -> start.get(10, TimeUnit.SECONDS))
					.hasCauseInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
		}
		assertNoSafetyStartSideEffects();
	}

	@Test
	void concurrentSafetyStartsCreateExactlyOneActiveAggregateAndReturnAnExplicitConflict() throws Exception {
		insertRecipient("00000000-0000-0000-0000-000000000005", "Concurrent safety start");
		SafetyRuntime runtime = safetyRuntime();
		CampaignSafetyRepository secondRepository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignSafetyRuntimePolicy.Destination destination = runtime.policy().requireReady();
		CountDownLatch subscribed = new CountDownLatch(2);
		CompletableFuture<Void> release = new CompletableFuture<>();
		List<CampaignSafetyRepository> repositories = List.of(runtime.repository(), secondRepository);
		List<CompletableFuture<Object>> starts = new ArrayList<>();
		for (int index = 0; index < repositories.size(); index++) {
			CampaignSafetyRepository repository = repositories.get(index);
			String traceId = "safetyconcurrent" + index;
			starts.add(reactor.core.publisher.Mono.defer(() -> {
				subscribed.countDown();
				return reactor.core.publisher.Mono.fromFuture(release)
						.then(repository.materialize(new CampaignSafetyRepository.MaterializeCommand(
								CAMPAIGN, ACTOR, 0, 1, destination.hmac(), destination.masked(), NOW, traceId)))
						.cast(Object.class);
			}).onErrorResume(error -> reactor.core.publisher.Mono.just(error))
					.subscribeOn(Schedulers.boundedElastic()).toFuture());
		}
		assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

		release.complete(null);
		List<Object> results = List.of(
				starts.get(0).get(10, TimeUnit.SECONDS), starts.get(1).get(10, TimeUnit.SECONDS));

		assertThat(results).filteredOn(CampaignSafetyRepository.MaterializedRun.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(com.camel_hub.advertisement.campaign.CampaignConflictException.class::isInstance)
				.hasSize(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_runs WHERE status IN ('QUEUED','RUNNING')"))
				.isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_messages")).isEqualTo(1);
	}

	@Test
	void executorDispatchesSafetyClaimWithoutProductionPreparationOrContactDecryption() {
		UUID source = insertRecipient("00000000-0000-0000-0000-000000000011", "Safety dispatch");
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyRepository safetyRepository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignSafetyRepository.MaterializedRun run = safetyRepository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(
						CAMPAIGN, ACTOR, 0, 1, signer.destinationHmac("fixed@example.test"),
						"f***@example.test", NOW, "safetytrace2")).block();
		CampaignDeliveryRepository deliveryRepository = new CampaignDeliveryRepository(
				database, transactions,
				new CampaignDeliveryProperties(true, 10, Duration.ofMinutes(2), Duration.ofDays(180),
						3, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)),
				new CampaignSafetyProperties(true, "fixed@example.test", 20),
				new CampaignSafetyRuntimePolicy(
						new CampaignSafetyProperties(true, "fixed@example.test", 20),
						new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
								Duration.ofSeconds(2), Duration.ofSeconds(2), ""),
						new MailTrackingProperties(true, "https://tracking.example.test", SAFETY_KEY,
								Duration.ofDays(30), Duration.ofMinutes(15)), signer, Duration.ofMinutes(2)));
		CampaignOutboundPreparer productionPreparer = claim ->
				reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
		ContactCrypto contactCrypto = mock(ContactCrypto.class);
		AtomicReference<SmtpTransport.OutboundMessage> sent = new AtomicReference<>();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				deliveryRepository, productionPreparer, safetyRepository,
				claim -> reactor.core.publisher.Mono.just(new CampaignSafetyOutboundPreparer.PreparedSafetyOutbound(
						"fixed@example.test", claim.renderedSubject(), claim.renderedHtml(), claim.renderedText(), Map.of())),
				contactCrypto, (account, message) -> {
					sent.set(message);
					return new SmtpTransport.SmtpOutcome(
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
							250, "250 queued");
				}, Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(sent.get().recipient()).isEqualTo("fixed@example.test");
		assertThat(sent.get().html()).contains("SAFETY TEST");
		verifyNoInteractions(contactCrypto);
		assertThat(text("SELECT status FROM campaign_safety_messages WHERE run_id = '" + run.id() + "'"))
				.isEqualTo("SMTP_ACCEPTED");
		assertThat(text("SELECT status FROM campaign_recipients WHERE id = '" + source + "'"))
				.isEqualTo("QUEUED");
		assertThat(integer("SELECT count(*)::int FROM delivery_attempts")).isZero();
		assertThat(integer("SELECT count(*)::int FROM recipient_delivery_cooldowns")).isZero();
	}

	@Test
	void realSafetyPreparationIsDigestOnlyAndByteStableAcrossExplicitFourHundredRetry() {
		UUID source = insertRecipient("00000000-0000-0000-0000-000000000021", "Tracked safety dispatch");
		database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
				+ "WHERE id = '" + CAMPAIGN + "'").fetch().rowsUpdated().block();
		database.sql("UPDATE campaign_recipients SET rendered_html = '<p>Generated body</p>"
				+ "<a href=\"https://papers.example.test/abs/1\">Paper</a>"
				+ "<a href=\"https://papers.example.test/abs/1\">Paper again</a>"
				+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>' WHERE id = '" + source + "'")
				.fetch().rowsUpdated().block();
		Map<String, String> productionBefore = productionSnapshot();
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY, Duration.ofDays(30), Duration.ofMinutes(15));
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety, new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
		CampaignSafetyRepository safetyRepository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignSafetyRepository.MaterializedRun run = safetyRepository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(CAMPAIGN, ACTOR, 0, 1,
						policy.requireReady().hmac(), policy.requireReady().masked(), NOW, "safetytrace3"))
				.block();
		List<SmtpTransport.OutboundMessage> attempts = new ArrayList<>();
		CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
				true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		CampaignOutboundPreparer productionPreparer = claim ->
				reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
		CampaignDeliveryRepository firstRepository = new CampaignDeliveryRepository(
				database, transactions, deliveryProperties, safety, policy);
		CampaignSafetyTrackingService firstTracking = new CampaignSafetyTrackingService(
				safetyRepository, policy, tracking, signer, new MailOpenClassifier(),
				Clock.fixed(NOW, ZoneOffset.UTC), transactions);
		CampaignDeliveryExecutor firstExecutor = new CampaignDeliveryExecutor(
				firstRepository, productionPreparer, safetyRepository, firstTracking, mock(ContactCrypto.class),
				(account, message) -> {
					attempts.add(message);
					throw new SmtpTransportException(
							SmtpTransportException.FailureCategory.SMTP_REJECTED,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.RCPT_TO,
							450, "450 temporary", true);
				}, Clock.fixed(NOW, ZoneOffset.UTC));

		CampaignDeliveryExecutor.PumpResult firstResult = firstExecutor.pumpOnce().block();
		assertThat(firstResult)
				.as("safety attempt: %s", text("SELECT status || ':' || COALESCE(failure_category, '') || ':' "
						+ "|| COALESCE(smtp_response_summary, '') FROM campaign_safety_attempts ORDER BY attempt_number LIMIT 1"))
				.isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_links WHERE safety_message_id IN "
				+ "(SELECT id FROM campaign_safety_messages WHERE run_id = '" + run.id() + "')")).isEqualTo(3);
		assertThat(text("SELECT rendered_html FROM campaign_safety_messages WHERE run_id = '" + run.id() + "'"))
				.contains("campaign-safety-open:v1.", "campaign-safety-click:v1.",
						"campaign-safety-unsubscribe:v1.").doesNotContain("fixed@example.test");

		Instant retryAt = NOW.plus(Duration.ofMinutes(1));
		CampaignDeliveryRepository retryRepository = new CampaignDeliveryRepository(
				database, transactions, deliveryProperties, safety, policy);
		CampaignSafetyTrackingService retryTracking = new CampaignSafetyTrackingService(
				safetyRepository, policy, tracking, signer, new MailOpenClassifier(),
				Clock.fixed(retryAt, ZoneOffset.UTC), transactions);
		CampaignDeliveryExecutor retryExecutor = new CampaignDeliveryExecutor(
				retryRepository, productionPreparer, safetyRepository, retryTracking, mock(ContactCrypto.class),
				(account, message) -> {
					attempts.add(message);
					return new SmtpTransport.SmtpOutcome(
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
							250, "250 queued");
				}, Clock.fixed(retryAt, ZoneOffset.UTC));

		assertThat(retryExecutor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(attempts).hasSize(2);
		assertThat(attempts.get(1).recipient()).isEqualTo("fixed@example.test");
		assertThat(attempts.get(1).subject()).isEqualTo(attempts.get(0).subject());
		assertThat(attempts.get(1).html()).isEqualTo(attempts.get(0).html());
		assertThat(attempts.get(1).text()).isEqualTo(attempts.get(0).text());
		assertThat(attempts.get(1).headers()).isEqualTo(attempts.get(0).headers());
		assertThat(attempts.get(1).rfcMessageId()).isEqualTo(attempts.get(0).rfcMessageId());
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + run.id() + "'"))
				.isEqualTo("COMPLETED");
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_attempts WHERE idempotency_key LIKE 'safety:%'"))
				.isEqualTo(2);
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void expiredSafetyCapabilitiesRotateOnlyAfterRateDeferredExplicitFourHundredRetry() {
		insertRecipient("00000000-0000-0000-0000-000000000022", "Rotate after shared-rate defer");
		insertRecipient("00000000-0000-0000-0000-000000000023", "Consume shared rate capacity");
		database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
				+ "WHERE id = '" + CAMPAIGN + "'").fetch().rowsUpdated().block();
		database.sql("UPDATE campaign_recipients SET rendered_html = '<p>Generated body</p>"
				+ "<a href=\"https://papers.example.test/abs/1\">Paper</a>"
				+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>'")
				.fetch().rowsUpdated().block();
		database.sql("UPDATE smtp_accounts SET per_minute_limit = 100, per_hour_limit = 1, "
				+ "per_day_limit = 100, per_domain_hour_limit = 100 WHERE id = '" + SMTP + "'")
				.fetch().rowsUpdated().block();
		Map<String, String> productionBefore = productionSnapshot();

		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY,
				Duration.ofMinutes(3), Duration.ofMinutes(15));
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety, new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofSeconds(30));
		CampaignSafetyRepository safetyRepository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignSafetyRepository.MaterializedRun run = safetyRepository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(CAMPAIGN, ACTOR, 0, 2,
						policy.requireReady().hmac(), policy.requireReady().masked(), NOW, "safetyrotate1"))
				.block();
		CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
				true, 10, Duration.ofSeconds(30), Duration.ofDays(180), 2,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		CampaignOutboundPreparer productionPreparer = claim ->
				reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
		AtomicInteger smtpCalls = new AtomicInteger();
		List<SmtpTransport.OutboundMessage> attempts = new ArrayList<>();
		AtomicReference<UUID> preRotationEventLink = new AtomicReference<>();
		CampaignDeliveryExecutor.CampaignSmtpSender sender = (account, message) -> {
			attempts.add(message);
			if (smtpCalls.getAndIncrement() == 0) {
				String openUrl = callbackUrl(message.html(), "t/o", "campaign-safety-open");
				String openToken = openUrl.substring(openUrl.lastIndexOf('/') + 1);
				CampaignSafetyRepository.ResolvedCallback callback = safetyRepository.resolveCallback(
						signer.digest(openToken), "OPEN", NOW).block();
				assertThat(callback).isNotNull();
				preRotationEventLink.set(callback.linkId());
				assertThat(safetyRepository.observeCallback(
						callback, "OPEN", new CampaignSafetyRepository.Observation(
								"LIKELY_HUMAN", "test", sha256("rotation-event")), NOW).block()).isTrue();
				throw new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.RCPT_TO,
						450, "450 temporary", true);
			}
			return new SmtpTransport.SmtpOutcome(
					com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
					com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
					250, "250 queued");
		};

		assertThat(safetyExecutor(safetyRepository, policy, tracking, signer, deliveryProperties,
				productionPreparer, sender, NOW).pumpOnce().block())
				.isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		UUID messageId = uuid("SELECT safety_message_id FROM campaign_safety_attempts "
				+ "ORDER BY started_at, id LIMIT 1");
		String oldHtml = text("SELECT rendered_html FROM campaign_safety_messages WHERE id = '" + messageId + "'");
		String oldText = text("SELECT rendered_text FROM campaign_safety_messages WHERE id = '" + messageId + "'");
		String oldRfcMessageId = text("SELECT rfc_message_id FROM campaign_safety_messages WHERE id = '"
				+ messageId + "'");
		String oldTopology = safetyLinkTopology(messageId);
		String oldTokenDigests = safetyTokenDigests(messageId);
		String oldOpen = callbackUrl(oldHtml, "t/o", "campaign-safety-open");
		String oldClick = callbackUrl(oldHtml, "t/c", "campaign-safety-click");
		String oldUnsubscribe = callbackUrl(oldHtml, "u", "campaign-safety-unsubscribe");

		Instant capacityAttempt = NOW.plusSeconds(1);
		assertThat(safetyExecutor(safetyRepository, policy, tracking, signer, deliveryProperties,
				productionPreparer, sender, capacityAttempt).pumpOnce().block())
				.isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		Instant retryDue = NOW.plus(Duration.ofMinutes(1));
		assertThat(safetyExecutor(safetyRepository, policy, tracking, signer, deliveryProperties,
				productionPreparer, sender, retryDue).pumpOnce().block())
				.isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
		Instant deferredUntil = instant("SELECT next_attempt_at FROM campaign_safety_messages WHERE id = '"
				+ messageId + "'");
		assertThat(deferredUntil).isAfter(NOW.plus(Duration.ofMinutes(3)));
		assertThat(smtpCalls).hasValue(2);

		CampaignDeliveryExecutor.PumpResult rotatedResult = safetyExecutor(
				safetyRepository, policy, tracking, signer, deliveryProperties,
				productionPreparer, sender, deferredUntil).pumpOnce().block();
		assertThat(rotatedResult).as(text("SELECT string_agg(attempt_number || ':' || status || ':' || "
				+ "COALESCE(failure_category, ''), '|' ORDER BY attempt_number) FROM campaign_safety_attempts "
				+ "WHERE safety_message_id = '" + messageId + "'"))
				.isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		String newHtml = text("SELECT rendered_html FROM campaign_safety_messages WHERE id = '" + messageId + "'");
		String newText = text("SELECT rendered_text FROM campaign_safety_messages WHERE id = '" + messageId + "'");
		String newOpen = callbackUrl(newHtml, "t/o", "campaign-safety-open");
		String newClick = callbackUrl(newHtml, "t/c", "campaign-safety-click");
		String newUnsubscribe = callbackUrl(newHtml, "u", "campaign-safety-unsubscribe");

		assertThat(smtpCalls).hasValue(3);
		assertThat(newHtml).isNotEqualTo(oldHtml);
		assertThat(newText).isNotEqualTo(oldText);
		assertThat(List.of(newOpen, newClick, newUnsubscribe))
				.doesNotContain(oldOpen, oldClick, oldUnsubscribe);
		assertThat(safetyLinkTopology(messageId)).isEqualTo(oldTopology);
		assertThat(safetyTokenDigests(messageId)).isNotEqualTo(oldTokenDigests);
		assertThat(text("SELECT rfc_message_id FROM campaign_safety_messages WHERE id = '" + messageId + "'"))
				.isEqualTo(oldRfcMessageId);
		assertThat(attempts.get(2).correlationId()).isEqualTo(attempts.get(0).correlationId());
		assertThat(attempts.get(2).rfcMessageId()).isEqualTo(attempts.get(0).rfcMessageId());
		assertThat(attempts.get(2).recipient()).isEqualTo(attempts.get(0).recipient());
		assertThat(attempts.get(2).subject()).isEqualTo(attempts.get(0).subject());
		assertThat(attempts.get(2).fromName()).isEqualTo(attempts.get(0).fromName());
		assertThat(attempts.get(2).fromEmail()).isEqualTo(attempts.get(0).fromEmail());
		assertThat(attempts.get(2).replyTo()).isEqualTo(attempts.get(0).replyTo());
		assertThat(normalizeCapabilities(attempts.get(0).html(), oldOpen, oldClick, oldUnsubscribe))
				.isEqualTo(normalizeCapabilities(attempts.get(2).html(), newOpen, newClick, newUnsubscribe));
		assertThat(normalizeCapabilities(attempts.get(0).text(), oldOpen, oldClick, oldUnsubscribe))
				.isEqualTo(normalizeCapabilities(attempts.get(2).text(), newOpen, newClick, newUnsubscribe));
		assertThat(normalizeCapabilities(attempts.get(0).headers().toString(), oldOpen, oldClick, oldUnsubscribe))
				.isEqualTo(normalizeCapabilities(
						attempts.get(2).headers().toString(), newOpen, newClick, newUnsubscribe));
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_links WHERE safety_message_id = '"
				+ messageId + "'")).isEqualTo(3);
		assertThat(uuid("SELECT safety_link_id FROM campaign_safety_events WHERE safety_message_id = '"
				+ messageId + "' LIMIT 1")).isEqualTo(preRotationEventLink.get());
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_links WHERE token_hash IN "
				+ "(digest('" + oldOpen.substring(oldOpen.lastIndexOf('/') + 1) + "','sha256'), "
				+ " digest('" + oldClick.substring(oldClick.lastIndexOf('/') + 1) + "','sha256'), "
				+ " digest('" + oldUnsubscribe.substring(oldUnsubscribe.lastIndexOf('/') + 1) + "','sha256'))"))
				.isZero();
		CampaignSafetyTrackingService callbacks = new CampaignSafetyTrackingService(
				safetyRepository, policy, tracking, signer, new MailOpenClassifier(),
				Clock.fixed(deferredUntil, ZoneOffset.UTC), transactions);
		AuthenticationRequestContext request = new AuthenticationRequestContext(
				"198.51.100.5", "Human Browser", "safety-rotation-callback");
		assertThat(callbacks.observeOpen(oldOpen.substring(oldOpen.lastIndexOf('/') + 1),
				HttpHeaders.EMPTY, request).block()).isFalse();
		assertThat(callbacks.observeOpen(newOpen.substring(newOpen.lastIndexOf('/') + 1),
				HttpHeaders.EMPTY, request).block()).isTrue();
		assertThat(callbacks.click(oldClick.substring(oldClick.lastIndexOf('/') + 1),
				HttpHeaders.EMPTY, request, false).block()).isNull();
		assertThat(callbacks.click(newClick.substring(newClick.lastIndexOf('/') + 1),
				HttpHeaders.EMPTY, request, false).block()).extracting(
				com.camel_hub.advertisement.campaign.tracking.CampaignCallbackNamespace.ResolvedClick::targetUrl)
				.isEqualTo("https://papers.example.test/abs/1");
		assertThat(callbacks.unsubscribe(oldUnsubscribe.substring(oldUnsubscribe.lastIndexOf('/') + 1),
				request).block()).isFalse();
		assertThat(callbacks.unsubscribe(newUnsubscribe.substring(newUnsubscribe.lastIndexOf('/') + 1),
				request).block()).isTrue();
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + run.id() + "'"))
				.isEqualTo("COMPLETED");
	}

	@Test
	void expiredSafetyRotationRejectsInvalidProvenanceAndArtifactSetsWithoutPartialMutation() {
		for (String invalid : List.of(
				"previous-status", "previous-retryable", "previous-code", "previous-category",
				"missing-previous", "mixed-expiry", "corrupt-token-hash", "missing-artifact",
				"extra-artifact")) {
			reset();
			insertRecipient("00000000-0000-0000-0000-000000000024", "Reject rotation " + invalid);
			database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
					+ "WHERE id = '" + CAMPAIGN + "'").fetch().rowsUpdated().block();
			database.sql("UPDATE campaign_recipients SET rendered_html = '<p>Generated body</p>"
					+ "<a href=\"https://papers.example.test/abs/1\">Paper</a>"
					+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>'")
					.fetch().rowsUpdated().block();
			Map<String, String> productionBefore = productionSnapshot();

			CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
			CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
			MailTrackingProperties tracking = new MailTrackingProperties(
					true, "https://tracking.example.test", SAFETY_KEY,
					Duration.ofMinutes(3), Duration.ofMinutes(15));
			CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
					safety, new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofSeconds(30));
			CampaignSafetyRepository repository = new CampaignSafetyRepository(
					database, transactions, new ObjectMapper().findAndRegisterModules());
			repository.materialize(new CampaignSafetyRepository.MaterializeCommand(
					CAMPAIGN, ACTOR, 0, 1, policy.requireReady().hmac(), policy.requireReady().masked(),
					NOW, "safetybadrotation")).block();
			CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
					true, 10, Duration.ofSeconds(30), Duration.ofDays(180), 2,
					Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
			CampaignOutboundPreparer productionPreparer = claim ->
					reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
			AtomicInteger smtpCalls = new AtomicInteger();
			CampaignDeliveryExecutor.CampaignSmtpSender sender = (account, message) -> {
				smtpCalls.incrementAndGet();
				throw new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.RCPT_TO,
						450, "450 temporary", true);
			};
			assertThat(safetyExecutor(repository, policy, tracking, signer, deliveryProperties,
					productionPreparer, sender, NOW).pumpOnce().block()).as(invalid)
					.isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
			UUID messageId = uuid("SELECT id FROM campaign_safety_messages");
			UUID previousAttempt = uuid("SELECT id FROM campaign_safety_attempts WHERE safety_message_id = '"
					+ messageId + "'");
			Instant expiry = instant("SELECT expires_at FROM campaign_safety_links WHERE safety_message_id = '"
					+ messageId + "' LIMIT 1");

			switch (invalid) {
				case "previous-status" -> database.sql("UPDATE campaign_safety_attempts "
						+ "SET status = 'PERMANENT_FAILURE' WHERE id = '" + previousAttempt + "'")
						.fetch().rowsUpdated().block();
				case "previous-retryable" -> database.sql("UPDATE campaign_safety_attempts "
						+ "SET retryable = false WHERE id = '" + previousAttempt + "'")
						.fetch().rowsUpdated().block();
				case "previous-code" -> database.sql("UPDATE campaign_safety_attempts "
						+ "SET smtp_response_code = 500 WHERE id = '" + previousAttempt + "'")
						.fetch().rowsUpdated().block();
				case "previous-category" -> database.sql("UPDATE campaign_safety_attempts "
						+ "SET failure_category = 'CONNECTION_TIMEOUT' WHERE id = '" + previousAttempt + "'")
						.fetch().rowsUpdated().block();
				case "missing-previous" -> database.sql("DELETE FROM campaign_safety_attempts WHERE id = '"
						+ previousAttempt + "'").fetch().rowsUpdated().block();
				case "mixed-expiry" -> database.sql("UPDATE campaign_safety_links SET expires_at = expires_at "
						+ "+ INTERVAL '1 second' WHERE safety_message_id = '" + messageId
						+ "' AND token_type = 'OPEN'").fetch().rowsUpdated().block();
				case "corrupt-token-hash" -> database.sql("UPDATE campaign_safety_links "
						+ "SET token_hash = digest('corrupt-safety-token','sha256') WHERE safety_message_id = '"
						+ messageId + "' AND token_type = 'OPEN'").fetch().rowsUpdated().block();
				case "missing-artifact" -> database.sql("DELETE FROM campaign_safety_links "
						+ "WHERE safety_message_id = '" + messageId + "' AND token_type = 'OPEN'")
						.fetch().rowsUpdated().block();
				case "extra-artifact" -> database.sql("""
						INSERT INTO campaign_safety_links (
						    id, safety_message_id, target_url, target_url_hash,
						    token_type, token_hash, expires_at, created_at
						) VALUES (
						    gen_random_uuid(), :message, 'https://papers.example.test/extra',
						    digest('https://papers.example.test/extra','sha256'), 'CLICK',
						    digest('extra-safety-token','sha256'), :expiry, :now
						)
						""").bind("message", messageId).bind("expiry", expiry).bind("now", NOW)
						.fetch().rowsUpdated().block();
				default -> throw new IllegalArgumentException(invalid);
			}
			Map<String, String> frozenBefore = safetyFrozenSnapshot(messageId);

			CampaignDeliveryExecutor.PumpResult result = safetyExecutor(
					repository, policy, tracking, signer, deliveryProperties, productionPreparer,
					(account, message) -> {
						smtpCalls.incrementAndGet();
						return new SmtpTransport.SmtpOutcome(
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
								250, "250 queued");
					}, NOW.plus(Duration.ofMinutes(4))).pumpOnce().block();
			assertThat(result).as(invalid).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
			assertThat(smtpCalls).as(invalid).hasValue(1);
			assertThat(safetyFrozenSnapshot(messageId)).as(invalid).isEqualTo(frozenBefore);
			assertThat(productionSnapshot()).as(invalid).isEqualTo(productionBefore);
		}
	}

	@Test
	void leaseLossAfterSafetyArtifactCasRollsBackEveryRotatedCapabilityAndBody() {
		insertRecipient("00000000-0000-0000-0000-000000000025", "Rollback expired safety rotation");
		database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
				+ "WHERE id = '" + CAMPAIGN + "'").fetch().rowsUpdated().block();
		database.sql("UPDATE campaign_recipients SET rendered_html = '<p>Generated body</p>"
				+ "<a href=\"https://papers.example.test/abs/1\">Paper</a>"
				+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>'")
				.fetch().rowsUpdated().block();
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY,
				Duration.ofMinutes(3), Duration.ofMinutes(15));
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety, new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofSeconds(30));
		CampaignSafetyRepository repository = spy(new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules()));
		repository.materialize(new CampaignSafetyRepository.MaterializeCommand(
				CAMPAIGN, ACTOR, 0, 1, policy.requireReady().hmac(), policy.requireReady().masked(),
				NOW, "safetyleaserollback")).block();
		CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
				true, 10, Duration.ofSeconds(30), Duration.ofDays(180), 2,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		CampaignOutboundPreparer productionPreparer = claim ->
				reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
		assertThat(safetyExecutor(repository, policy, tracking, signer, deliveryProperties,
				productionPreparer, (account, message) -> { throw new SmtpTransportException(
						SmtpTransportException.FailureCategory.SMTP_REJECTED,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.RCPT_TO,
						450, "450 temporary", true); }, NOW).pumpOnce().block())
				.isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		UUID messageId = uuid("SELECT id FROM campaign_safety_messages");
		Map<String, String> frozenBefore = safetyFrozenSnapshot(messageId);
		Map<String, String> productionBefore = productionSnapshot();

		Instant retryAt = NOW.plus(Duration.ofMinutes(4));
		CampaignDeliveryRepository delivery = new CampaignDeliveryRepository(
				database, transactions, deliveryProperties, safety, policy);
		CampaignSafetyTrackingService trackingService = new CampaignSafetyTrackingService(
				repository, policy, tracking, signer, new MailOpenClassifier(),
				new SequencedClock(retryAt, retryAt, retryAt.plus(Duration.ofMinutes(2)).plusSeconds(1)),
				transactions);
		AtomicInteger smtpCalls = new AtomicInteger();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, productionPreparer, repository, trackingService, mock(ContactCrypto.class),
				(account, message) -> {
					smtpCalls.incrementAndGet();
					return new SmtpTransport.SmtpOutcome(
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
							250, "250 queued");
				}, Clock.fixed(retryAt, ZoneOffset.UTC));

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(smtpCalls).hasValue(0);
		verify(repository, times(3)).rotateFrozenLink(eq(messageId), any(), any(), any());
		assertThat(safetyFrozenSnapshot(messageId)).isEqualTo(frozenBefore);
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void explicitCancelStaysCanceledAcrossLateAcceptAndCanceledLeaseExpiry() {
		insertRecipient("00000000-0000-0000-0000-000000000031", "Cancel first");
		insertRecipient("00000000-0000-0000-0000-000000000032", "Cancel second");
		Map<String, String> productionBefore = productionSnapshot();
		SafetyRuntime runtime = safetyRuntime();
		CampaignSafetyRepository.MaterializedRun firstRun = materialize(runtime, 2, "safetycancel1");
		CampaignDeliveryRepository.SafetyClaim acceptedClaim = (CampaignDeliveryRepository.SafetyClaim)
				runtime.delivery().claimNext(NOW).block();
		assertThat(acceptedClaim).isNotNull();
		long runningVersion = longValue("SELECT lock_version FROM campaign_safety_runs WHERE id = '"
				+ firstRun.id() + "'");

		assertThat(runtime.repository().cancel(
				CAMPAIGN, firstRun.id(), runningVersion, ACTOR, NOW.plusSeconds(1), "safetycancel2").block()).isTrue();
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + firstRun.id() + "'"))
				.isEqualTo("CANCELED");
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_messages WHERE run_id = '"
				+ firstRun.id() + "' AND status = 'CANCELED'" )).isEqualTo(1);

		assertThat(runtime.repository().completeAccepted(
				acceptedClaim.messageId(), acceptedClaim.attemptId(), acceptedClaim.leaseDigest(),
				new SmtpTransport.SmtpOutcome(
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
						250, "250 queued"), NOW.plusSeconds(2)).block()).isTrue();
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + firstRun.id() + "'"))
				.isEqualTo("CANCELED");

		CampaignSafetyRepository.MaterializedRun secondRun = materialize(runtime, 1, "safetycancel3");
		CampaignDeliveryRepository.SafetyClaim expiringClaim = (CampaignDeliveryRepository.SafetyClaim)
				runtime.delivery().claimNext(NOW.plusSeconds(3)).block();
		assertThat(expiringClaim).isNotNull();
		long secondVersion = longValue("SELECT lock_version FROM campaign_safety_runs WHERE id = '"
				+ secondRun.id() + "'");
		assertThat(runtime.repository().cancel(
				CAMPAIGN, secondRun.id(), secondVersion, ACTOR, NOW.plusSeconds(4), "safetycancel4").block()).isTrue();

		Instant expiredAt = NOW.plus(Duration.ofMinutes(3));
		assertThat(runtime.repository().reconcileExpiredLeases(expiredAt, 20).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_messages WHERE id = '"
				+ expiringClaim.messageId() + "'" )).isEqualTo("OUTCOME_UNKNOWN");
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + secondRun.id() + "'"))
				.isEqualTo("CANCELED");
		assertThat(runtime.repository().completeAccepted(
				expiringClaim.messageId(), expiringClaim.attemptId(), expiringClaim.leaseDigest(),
				new SmtpTransport.SmtpOutcome(
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
						250, "250 too late"), expiredAt.plusSeconds(1)).block()).isFalse();
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void disabledServiceRejectsStartButRetainsRealListGetAndManualCancel() {
		insertRecipient("00000000-0000-0000-0000-000000000033", "Disabled API continuity");
		SafetyRuntime runtime = safetyRuntime();
		CampaignSafetyRepository.MaterializedRun run = materialize(runtime, 1, "safetydisabledapi");
		CampaignSafetyService disabled = new CampaignSafetyService(
				runtime.repository(), null, 20, Clock.fixed(NOW, ZoneOffset.UTC));
		AuthenticationRequestContext request = new AuthenticationRequestContext(
				"198.51.100.7", "Safety Admin", "safetydisabledapi2");
		long runCount = longValue("SELECT count(*) FROM campaign_safety_runs");

		assertThatThrownBy(() -> disabled.start(
				CAMPAIGN, ACTOR, request,
				new CampaignSafetyService.StartCommand(0, 1, CampaignSafetyService.CONFIRMATION)).block())
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class)
				.hasMessage("Campaign safety mode is unavailable");
		assertThat(longValue("SELECT count(*) FROM campaign_safety_runs")).isEqualTo(runCount);
		assertThat(disabled.list(CAMPAIGN).block()).singleElement()
				.extracting(CampaignSafetyService.SafetyRunView::id).isEqualTo(run.id());
		CampaignSafetyService.SafetyRunView current = disabled.get(CAMPAIGN, run.id()).block();
		assertThat(current).isNotNull();
		assertThat(disabled.cancel(
				CAMPAIGN, run.id(), ACTOR, request, current.lockVersion()).block().status())
				.isEqualTo("CANCELED");
	}

	@Test
	void disabledReconciliationIsStickyAcrossConnectingLateAcceptAndKeepsAcceptedCallbacks() {
		insertRecipient("00000000-0000-0000-0000-000000000034", "Accepted before disable");
		insertRecipient("00000000-0000-0000-0000-000000000035", "Connecting across disable");
		database.sql("UPDATE campaign_recipients SET rendered_html = "
				+ "'<p>Generated body</p><a href=\"https://papers.example.test/disabled-callback\">Paper</a>"
				+ "<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>' WHERE campaign_id = '" + CAMPAIGN + "'")
				.fetch().rowsUpdated().block();
		database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
				+ "WHERE id = '" + CAMPAIGN + "'")
				.fetch().rowsUpdated().block();
		Map<String, String> productionBefore = productionSnapshot();
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyProperties enabledSafety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY,
				Duration.ofDays(30), Duration.ofMinutes(15));
		CampaignSafetyRuntimePolicy enabledPolicy = new CampaignSafetyRuntimePolicy(
				enabledSafety, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
		CampaignSafetyRepository repository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignSafetyRepository.MaterializedRun run = repository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(
						CAMPAIGN, ACTOR, 0, 2, enabledPolicy.requireReady().hmac(),
						enabledPolicy.requireReady().masked(), NOW, "safetydisabledsticky"))
				.block();
		CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
				true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
		CampaignOutboundPreparer productionPreparer = claim ->
				reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
		AtomicReference<SmtpTransport.OutboundMessage> accepted = new AtomicReference<>();
		assertThat(safetyExecutor(repository, enabledPolicy, tracking, signer, deliveryProperties,
				productionPreparer, (account, message) -> {
					accepted.set(message);
					return new SmtpTransport.SmtpOutcome(
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
							250, "250 queued");
				}, NOW).pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		CampaignDeliveryRepository enabledDelivery = new CampaignDeliveryRepository(
				database, transactions, deliveryProperties, enabledSafety, enabledPolicy);
		CampaignDeliveryRepository.SafetyClaim connecting =
				(CampaignDeliveryRepository.SafetyClaim) enabledDelivery.claimNext(NOW.plusSeconds(1)).block();
		assertThat(connecting).isNotNull();

		assertThat(repository.cancelActiveRunsBecauseDisabled(NOW.plusSeconds(2), 10).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + run.id() + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT status FROM campaign_safety_messages WHERE id = '"
				+ connecting.messageId() + "'")).isEqualTo("CONNECTING");
		String audit = text("SELECT to_jsonb(audit_logs)::text FROM audit_logs "
				+ "WHERE action = 'CAMPAIGN_SAFETY_DISABLED_CANCELED'");
		assertThat(audit).contains("SAFETY_DISABLED", "CANCELED")
				.doesNotContain("fixed@example.test", "Accepted before disable", "campaign-safety-open:v1.");

		CampaignSafetyProperties disabledSafety = new CampaignSafetyProperties(false, "", 20);
		CampaignSafetyRuntimePolicy disabledPolicy = new CampaignSafetyRuntimePolicy(
				disabledSafety, new SmtpProperties(false, Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
		CampaignSafetyTrackingService disabledCallbacks = new CampaignSafetyTrackingService(
				repository, disabledPolicy, tracking, signer, new MailOpenClassifier(),
				Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC), transactions);
		String openUrl = callbackUrl(accepted.get().html(), "t/o", "campaign-safety-open");
		String clickUrl = callbackUrl(accepted.get().html(), "t/c", "campaign-safety-click");
		String unsubscribeUrl = callbackUrl(accepted.get().html(), "u", "campaign-safety-unsubscribe");
		String openToken = openUrl.substring(openUrl.lastIndexOf('/') + 1);
		String clickToken = clickUrl.substring(clickUrl.lastIndexOf('/') + 1);
		String unsubscribeToken = unsubscribeUrl.substring(unsubscribeUrl.lastIndexOf('/') + 1);
		AuthenticationRequestContext callbackRequest = new AuthenticationRequestContext(
				"198.51.100.8", "Human Browser", "safetydisabledcallback");
		assertThat(disabledCallbacks.observeOpen(
				openToken, HttpHeaders.EMPTY, callbackRequest).block()).isTrue();
		assertThat(disabledCallbacks.click(
				clickToken, HttpHeaders.EMPTY, callbackRequest, true).block())
				.isNotNull()
				.extracting(CampaignCallbackNamespace.ResolvedClick::targetUrl)
				.isEqualTo("https://papers.example.test/disabled-callback");
		assertThat(disabledCallbacks.unsubscribe(unsubscribeToken, callbackRequest).block()).isTrue();
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events WHERE event_type = 'OPEN'"))
				.isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events WHERE event_type = 'CLICK'"))
				.isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events WHERE event_type = 'UNSUBSCRIBE'"))
				.isEqualTo(1);
		assertThat(repository.completeAccepted(
				connecting.messageId(), connecting.attemptId(), connecting.leaseDigest(),
				new SmtpTransport.SmtpOutcome(
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
						250, "250 queued"), NOW.plusSeconds(4)).block()).isTrue();
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + run.id() + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT status FROM campaign_safety_messages WHERE id = '"
				+ connecting.messageId() + "'")).isEqualTo("SMTP_ACCEPTED");
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void terminalAggregationUsesCompletedFailedAndPartiallyFailedTruthTable() {
		insertRecipient("00000000-0000-0000-0000-000000000041", "Truth one");
		insertRecipient("00000000-0000-0000-0000-000000000042", "Truth two");
		insertRecipient("00000000-0000-0000-0000-000000000043", "Truth three");
		SafetyRuntime runtime = safetyRuntime();

		CampaignSafetyRepository.MaterializedRun completed = materialize(runtime, 3, "safetytruth1");
		database.sql("UPDATE campaign_safety_messages SET status = 'SMTP_ACCEPTED' WHERE run_id = '"
				+ completed.id() + "'").fetch().rowsUpdated().block();
		assertThat(runtime.repository().reconcileAggregates(NOW.plusSeconds(1), 20).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + completed.id() + "'"))
				.isEqualTo("COMPLETED");

		CampaignSafetyRepository.MaterializedRun failed = materialize(runtime, 3, "safetytruth2");
		database.sql("UPDATE campaign_safety_messages SET status = CASE "
				+ "WHEN campaign_recipient_id = '00000000-0000-0000-0000-000000000041' THEN 'PERMANENT_FAILURE' "
				+ "WHEN campaign_recipient_id = '00000000-0000-0000-0000-000000000042' THEN 'CANCELED' "
				+ "ELSE 'OUTCOME_UNKNOWN' END WHERE run_id = '" + failed.id() + "'")
				.fetch().rowsUpdated().block();
		assertThat(runtime.repository().reconcileAggregates(NOW.plusSeconds(2), 20).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + failed.id() + "'"))
				.isEqualTo("FAILED");

		CampaignSafetyRepository.MaterializedRun partial = materialize(runtime, 2, "safetytruth3");
		database.sql("UPDATE campaign_safety_messages SET status = CASE "
				+ "WHEN campaign_recipient_id = '00000000-0000-0000-0000-000000000041' THEN 'SMTP_ACCEPTED' "
				+ "ELSE 'PERMANENT_FAILURE' END WHERE run_id = '" + partial.id() + "'")
				.fetch().rowsUpdated().block();
		assertThat(runtime.repository().reconcileAggregates(NOW.plusSeconds(3), 20).block()).isEqualTo(1);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + partial.id() + "'"))
				.isEqualTo("PARTIALLY_FAILED");
	}

	@Test
	void generatedDraftsCanBeTestedBeforeProductionApprovalButTerminalCampaignsCannot() {
		insertRecipient("00000000-0000-0000-0000-000000000051", "Pre-approval safety");
		SafetyRuntime runtime = safetyRuntime();
		for (String status : List.of("DRAFT", "READY_FOR_REVIEW", "REJECTED")) {
			database.sql("UPDATE campaigns SET status = '" + status + "' WHERE id = '" + CAMPAIGN + "'")
					.fetch().rowsUpdated().block();
			CampaignSafetyRepository.MaterializedRun run = materialize(
					runtime, 1, "safetystate" + status.toLowerCase(java.util.Locale.ROOT).replace("_", ""));
			assertThat(run).isNotNull();
			database.sql("UPDATE campaign_safety_messages SET status = 'CANCELED' WHERE run_id = '"
					+ run.id() + "'").fetch().rowsUpdated().block();
			database.sql("UPDATE campaign_safety_runs SET status = 'CANCELED' WHERE id = '"
					+ run.id() + "'").fetch().rowsUpdated().block();
		}
		for (String terminal : List.of("COMPLETED", "CANCELED")) {
			database.sql("UPDATE campaigns SET status = '" + terminal + "' WHERE id = '" + CAMPAIGN + "'")
					.fetch().rowsUpdated().block();
			assertThatThrownBy(() -> materialize(runtime, 1, "safetyterminal" + terminal.toLowerCase()))
					.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
		}
	}

	@Test
	void startFailsClosedForStaleCampaignEmptySnapshotConfiguredOverflowAndAnActiveRun() {
		insertRecipient("00000000-0000-0000-0000-000000000052", "Stale source");
		SafetyRuntime stale = safetyRuntime();
		assertThatThrownBy(() -> stale.repository().materialize(new CampaignSafetyRepository.MaterializeCommand(
				CAMPAIGN, ACTOR, 1, 1, stale.policy().requireReady().hmac(),
				stale.policy().requireReady().masked(), NOW, "safetystaleversion")).block())
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignConflictException.class);
		assertNoSafetyStartSideEffects();

		reset();
		insertRecipient("00000000-0000-0000-0000-000000000053", "Not generated", "PENDING");
		SafetyRuntime empty = safetyRuntime();
		assertThatThrownBy(() -> materialize(empty, 1, "safetyempty"))
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
		assertNoSafetyStartSideEffects();

		reset();
		insertRecipient("00000000-0000-0000-0000-000000000054", "Configured cap one");
		CampaignSafetyService capped = new CampaignSafetyService(
				safetyRuntime().repository(), safetyRuntime().policy(), 1, Clock.fixed(NOW, ZoneOffset.UTC));
		assertThatThrownBy(() -> capped.start(CAMPAIGN, ACTOR,
				new AuthenticationRequestContext("198.51.100.30", "Safety Browser", "safetyconfiguredcap"),
				new CampaignSafetyService.StartCommand(0, 2, CampaignSafetyService.CONFIRMATION)).block())
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
		assertNoSafetyStartSideEffects();

		reset();
		insertRecipient("00000000-0000-0000-0000-000000000055", "Only active run");
		SafetyRuntime active = safetyRuntime();
		materialize(active, 1, "safetyactiveone");
		assertThatThrownBy(() -> materialize(active, 1, "safetyactivetwo"))
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignConflictException.class);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_runs")).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_messages")).isEqualTo(1);
		assertThat(integer("SELECT count(*)::int FROM outbox_messages WHERE message_type = 'CAMPAIGN_DELIVERY_WAKEUP'"))
				.isEqualTo(1);
	}

	@Test
	void listingSafetyRunsForAnUnknownCampaignFailsClosed() {
		SafetyRuntime runtime = safetyRuntime();

		assertThatThrownBy(() -> new CampaignSafetyService(
				runtime.repository(), runtime.policy(), 20, Clock.fixed(NOW, ZoneOffset.UTC))
				.list(UUID.randomUUID()).block())
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignNotFoundException.class);
	}

	@Test
	void unsafeOrNonImmutableDraftFailsBeforeAnySafetyArtifactOutboxOrAudit() {
		UUID source = insertRecipient("00000000-0000-0000-0000-000000000061", "Unsafe source");
		SafetyRuntime runtime = safetyRuntime();
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		String token = signer.issueOpen(source, NOW.plus(Duration.ofDays(1)));
		for (String injected : List.of(
				token,
				java.net.URLEncoder.encode(token, StandardCharsets.UTF_8),
				"campaign-safety-open&#58;v1." + token.substring(token.indexOf('.') + 1),
				"logical-author%40research.example")) {
			database.sql("UPDATE campaign_recipients SET rendered_html = :html WHERE id = :id")
					.bind("html", "<p>" + injected + "</p><a href=\"{{unsubscribe_url}}\">Stop</a>")
					.bind("id", source).fetch().rowsUpdated().block();
			assertThatThrownBy(() -> materialize(runtime, 1, "safetyunsafe1"))
					.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
			assertNoSafetyStartSideEffects();
		}
		database.sql("UPDATE campaign_recipients SET rendered_html = '<a href=\"{{unsubscribe_url}}\">Stop</a>', "
				+ "personalization_status = 'GENERATED', attempt_count = 1 WHERE id = '" + source + "'")
				.fetch().rowsUpdated().block();
		assertThatThrownBy(() -> materialize(runtime, 1, "safetyunsafe2"))
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
		assertNoSafetyStartSideEffects();
	}

	@Test
	void staleSmtpHealthOrDestinationDriftTerminatesWithoutClaimingSmtp() {
		insertRecipient("00000000-0000-0000-0000-000000000071", "Configuration drift");
		SafetyRuntime runtime = safetyRuntime();
		database.sql("UPDATE smtp_accounts SET updated_at = :updated WHERE id = :id")
				.bind("updated", NOW.plusSeconds(1)).bind("id", SMTP).fetch().rowsUpdated().block();
		assertThatThrownBy(() -> materialize(runtime, 1, "safetystale1"))
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
		assertNoSafetyStartSideEffects();

		database.sql("UPDATE smtp_accounts SET updated_at = :updated, last_tested_at = :tested WHERE id = :id")
				.bind("updated", NOW.minusSeconds(2)).bind("tested", NOW.minusSeconds(1)).bind("id", SMTP)
				.fetch().rowsUpdated().block();
		Map<String, String> productionBefore = productionSnapshot();
		CampaignSafetyRepository.MaterializedRun run = materialize(runtime, 1, "safetydrift2");
		CampaignSafetyProperties changed = new CampaignSafetyProperties(true, "changed@example.test", 20);
		CampaignSafetyRuntimePolicy changedPolicy = new CampaignSafetyRuntimePolicy(
				changed, new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""),
				new MailTrackingProperties(true, "https://tracking.example.test", SAFETY_KEY,
						Duration.ofDays(30), Duration.ofMinutes(15)), new CampaignSafetySigner(SAFETY_KEY),
				Duration.ofMinutes(2));
		CampaignDeliveryRepository changedDelivery = new CampaignDeliveryRepository(
				database, transactions, new CampaignDeliveryProperties(true, 20, Duration.ofMinutes(2),
				Duration.ofDays(180), 3, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)),
				changed, changedPolicy);

		assertThat(changedDelivery.claimNext(NOW).block()).isNull();
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + run.id() + "'"))
				.isEqualTo("FAILED");
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_attempts WHERE safety_message_id IN "
				+ "(SELECT id FROM campaign_safety_messages WHERE run_id = '" + run.id() + "')")).isZero();
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void destinationDriftAfterPreparationIsRecheckedAtTheFinalSendBoundary() {
		insertRecipient("00000000-0000-0000-0000-000000000072", "Final destination drift");
		Map<String, String> productionBefore = productionSnapshot();
		SafetyRuntime runtime = safetyRuntime();
		CampaignSafetyRepository.MaterializedRun run = materialize(runtime, 1, "safetyfinaldrift");
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY,
				Duration.ofDays(30), Duration.ofMinutes(15));
		CampaignSafetyTrackingService preparation = new CampaignSafetyTrackingService(
				runtime.repository(), runtime.policy(), tracking, signer, new MailOpenClassifier(),
				Clock.fixed(NOW, ZoneOffset.UTC), transactions);
		CampaignSafetyProperties changedProperties = new CampaignSafetyProperties(
				true, "changed@example.test", 20);
		CampaignSafetyRuntimePolicy changedPolicy = new CampaignSafetyRuntimePolicy(
				changedProperties, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
		CampaignSafetyTrackingService finalValidation = new CampaignSafetyTrackingService(
				runtime.repository(), changedPolicy, tracking, signer, new MailOpenClassifier(),
				Clock.fixed(NOW, ZoneOffset.UTC), transactions);
		CampaignSafetyOutboundPreparer driftBetweenPhases = new CampaignSafetyOutboundPreparer() {
			@Override
			public reactor.core.publisher.Mono<PreparedSafetyOutbound> prepare(
					CampaignDeliveryRepository.SafetyClaim claim
			) {
				return preparation.prepare(claim);
			}

			@Override
			public PreparedSafetyOutbound validateForSend(
					CampaignDeliveryRepository.SafetyClaim claim, PreparedSafetyOutbound prepared
			) {
				return finalValidation.validateForSend(claim, prepared);
			}
		};
		AtomicInteger smtpCalls = new AtomicInteger();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				runtime.delivery(), claim -> reactor.core.publisher.Mono.error(
						new AssertionError("production preparer must not run")),
				runtime.repository(), driftBetweenPhases, mock(ContactCrypto.class), (account, message) -> {
				smtpCalls.incrementAndGet();
				return new SmtpTransport.SmtpOutcome(
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
						com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
						250, "250 queued");
			}, Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
		assertThat(smtpCalls).hasValue(0);
		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + run.id() + "'"))
				.isEqualTo("FAILED");
		assertThat(text("SELECT failure_category FROM campaign_safety_attempts WHERE safety_message_id IN "
				+ "(SELECT id FROM campaign_safety_messages WHERE run_id = '" + run.id() + "')"))
				.isEqualTo("PREPARATION_FAILED");
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void everyEncodedCallbackNamespaceInFromDisplayNameFailsBeforeSmtp() {
		CampaignSafetySigner safetySigner = new CampaignSafetySigner(SAFETY_KEY);
		CampaignTrackingSigner productionSigner = new CampaignTrackingSigner(SAFETY_KEY);
		MailTrackingSigner testSigner = new MailTrackingSigner(SAFETY_KEY);
		Instant expiry = NOW.plus(Duration.ofDays(30));
		String safetyToken = safetySigner.issueOpen(UUID.randomUUID(), expiry);
		String productionToken = productionSigner.issueOpen(UUID.randomUUID(), expiry);
		String testToken = testSigner.issue(UUID.randomUUID(), expiry);
		List<String> forbiddenNames = List.of(
				safetyToken,
				productionToken,
				testToken,
				safetyToken.replace(":", "%3A").replace(".", "%2E"),
				compatibilityPrefixEncoded(safetyToken),
				productionToken.replace(":", "&#58;"),
				safetyToken.replace(":", "："),
				"=?UTF-8?Q?" + testToken.replace("_", "=5F") + "?=",
				"X" + testToken);
		for (int index = 0; index < forbiddenNames.size(); index++) {
			reset();
			insertRecipient("00000000-0000-0000-0000-00000000004" + index,
					"Reject encoded From capability " + index);
			Map<String, String> productionBefore = productionSnapshot();
			CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
			MailTrackingProperties tracking = new MailTrackingProperties(
					true, "https://tracking.example.test", SAFETY_KEY,
					Duration.ofDays(30), Duration.ofMinutes(15));
			CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
					safety, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, safetySigner,
					Duration.ofMinutes(2));
			CampaignSafetyRepository repository = new CampaignSafetyRepository(
					database, transactions, new ObjectMapper().findAndRegisterModules());
			CampaignSafetyRepository.MaterializedRun run = repository.materialize(
					new CampaignSafetyRepository.MaterializeCommand(
							CAMPAIGN, ACTOR, 0, 1, policy.requireReady().hmac(),
							policy.requireReady().masked(), NOW, "safetyfromname" + index)).block();
			database.sql("UPDATE campaign_safety_runs SET from_name_snapshot = :name WHERE id = :run")
					.bind("name", forbiddenNames.get(index)).bind("run", run.id()).fetch().rowsUpdated().block();
			CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
					true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
					Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
			AtomicInteger smtpCalls = new AtomicInteger();

			CampaignDeliveryExecutor.PumpResult result = safetyExecutor(
					repository, policy, tracking, safetySigner, deliveryProperties,
					claim -> reactor.core.publisher.Mono.error(
							new AssertionError("production preparer must not run")),
					(account, message) -> {
						smtpCalls.incrementAndGet();
						return new SmtpTransport.SmtpOutcome(
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
								250, "250 queued");
					}, NOW).pumpOnce().block();

			assertThat(result).as("encoded From variant " + index)
					.isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
			assertThat(smtpCalls).as("encoded From variant " + index).hasValue(0);
			assertThat(integer("SELECT count(*)::int FROM campaign_safety_links")).as("variant " + index)
					.isZero();
			assertThat(text("SELECT failure_category FROM campaign_safety_attempts"))
					.isEqualTo("PREPARATION_FAILED");
			assertThat(productionSnapshot()).as("variant " + index).isEqualTo(productionBefore);
		}
	}

	@Test
	void sourceCampaignFromNameCapabilityIsRejectedBeforeSafetyMaterialization() {
		insertRecipient("00000000-0000-0000-0000-000000000049", "Materialization From boundary");
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		String capability = new CampaignTrackingSigner(SAFETY_KEY)
				.issueOpen(UUID.randomUUID(), NOW.plus(Duration.ofDays(30)));
		database.sql("UPDATE campaigns SET from_name = :name WHERE id = :campaign")
				.bind("name", capability).bind("campaign", CAMPAIGN).fetch().rowsUpdated().block();
		Map<String, String> productionBefore = productionSnapshot();
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY,
				Duration.ofDays(30), Duration.ofMinutes(15));
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
		CampaignSafetyRepository repository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());

		assertThatThrownBy(() -> repository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(
						CAMPAIGN, ACTOR, 0, 1, policy.requireReady().hmac(),
						policy.requireReady().masked(), NOW, "safetymaterialfrom")).block())
				.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class)
				.hasMessage("Campaign safety sender metadata is invalid");
		assertNoSafetyStartSideEffects();
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void sourceCampaignComposedOrWrappedFromCapabilitiesAreRejectedBeforeMaterialization() {
		CampaignSafetySigner safetySigner = new CampaignSafetySigner(SAFETY_KEY);
		String safetyToken = safetySigner.issueOpen(UUID.randomUUID(), NOW.plus(Duration.ofDays(30)));
		String testToken = new MailTrackingSigner(SAFETY_KEY)
				.issue(UUID.randomUUID(), NOW.plus(Duration.ofDays(30)));
		for (String forbiddenName : List.of(compatibilityPrefixEncoded(safetyToken), "X" + testToken)) {
			reset();
			insertRecipient("00000000-0000-0000-0000-000000000052", "Composed From boundary");
			database.sql("UPDATE campaigns SET from_name = :name WHERE id = :campaign")
					.bind("name", forbiddenName).bind("campaign", CAMPAIGN).fetch().rowsUpdated().block();
			Map<String, String> productionBefore = productionSnapshot();
			CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
			MailTrackingProperties tracking = new MailTrackingProperties(
					true, "https://tracking.example.test", SAFETY_KEY,
					Duration.ofDays(30), Duration.ofMinutes(15));
			CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
					safety, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, safetySigner,
					Duration.ofMinutes(2));
			CampaignSafetyRepository repository = new CampaignSafetyRepository(
					database, transactions, new ObjectMapper().findAndRegisterModules());

			assertThatThrownBy(() -> repository.materialize(
					new CampaignSafetyRepository.MaterializeCommand(
							CAMPAIGN, ACTOR, 0, 1, policy.requireReady().hmac(),
							policy.requireReady().masked(), NOW, "safetycomposedfrom")).block())
					.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class)
					.hasMessage("Campaign safety sender metadata is invalid");
			assertNoSafetyStartSideEffects();
			assertThat(productionSnapshot()).isEqualTo(productionBefore);
		}
	}

	@Test
	void sourceCampaignMailboxMetadataIsStrictlyRejectedBeforeSafetyMaterialization() {
		for (String field : List.of("from_email", "reply_to")) {
			reset();
			insertRecipient("00000000-0000-0000-0000-000000000051", "Materialization mailbox boundary");
			CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
			String capability = signer.issueOpen(UUID.randomUUID(), NOW.plus(Duration.ofDays(30)));
			String capabilityMailbox = "\"" + capability + "\"@example.invalid";
			database.sql("UPDATE campaigns SET " + field + " = :mailbox WHERE id = :campaign")
					.bind("mailbox", capabilityMailbox).bind("campaign", CAMPAIGN)
					.fetch().rowsUpdated().block();
			Map<String, String> productionBefore = productionSnapshot();
			CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
			MailTrackingProperties tracking = new MailTrackingProperties(
					true, "https://tracking.example.test", SAFETY_KEY,
					Duration.ofDays(30), Duration.ofMinutes(15));
			CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
					safety, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
			CampaignSafetyRepository repository = new CampaignSafetyRepository(
					database, transactions, new ObjectMapper().findAndRegisterModules());

			assertThatThrownBy(() -> repository.materialize(
					new CampaignSafetyRepository.MaterializeCommand(
							CAMPAIGN, ACTOR, 0, 1, policy.requireReady().hmac(),
							policy.requireReady().masked(), NOW, "safetymaterialmailbox")).block())
					.as(field)
					.isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class)
					.hasMessage("Campaign safety sender metadata is invalid");
			assertNoSafetyStartSideEffects();
			assertThat(productionSnapshot()).as(field).isEqualTo(productionBefore);
		}
	}

	@Test
	void capabilityInsideAnyFinalMailboxHeaderFailsClosedBeforeSmtp() {
		for (String field : List.of("from_email_snapshot", "reply_to_snapshot")) {
			reset();
			insertRecipient("00000000-0000-0000-0000-000000000050", "Mailbox header boundary");
			Map<String, String> productionBefore = productionSnapshot();
			CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
			CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
			MailTrackingProperties tracking = new MailTrackingProperties(
					true, "https://tracking.example.test", SAFETY_KEY,
					Duration.ofDays(30), Duration.ofMinutes(15));
			CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
					safety, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
			CampaignSafetyRepository repository = new CampaignSafetyRepository(
					database, transactions, new ObjectMapper().findAndRegisterModules());
			CampaignSafetyRepository.MaterializedRun run = repository.materialize(
					new CampaignSafetyRepository.MaterializeCommand(
							CAMPAIGN, ACTOR, 0, 1, policy.requireReady().hmac(),
							policy.requireReady().masked(), NOW, "safetymailboxheader")).block();
			String capability = signer.issueOpen(UUID.randomUUID(), NOW.plus(Duration.ofDays(30)));
			String mailbox = "\"" + capability + "\"@example.invalid";
			database.sql("UPDATE campaign_safety_runs SET " + field + " = :mailbox WHERE id = :run")
					.bind("mailbox", mailbox).bind("run", run.id()).fetch().rowsUpdated().block();
			CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
					true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
					Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
			AtomicInteger smtpCalls = new AtomicInteger();

			assertThat(safetyExecutor(repository, policy, tracking, signer, deliveryProperties,
					claim -> reactor.core.publisher.Mono.error(
							new AssertionError("production preparer must not run")),
					(account, message) -> {
						smtpCalls.incrementAndGet();
						return new SmtpTransport.SmtpOutcome(
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
								250, "250 queued");
					}, NOW).pumpOnce().block()).as(field)
					.isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
			assertThat(smtpCalls).as(field).hasValue(0);
			assertThat(integer("SELECT count(*)::int FROM campaign_safety_links")).isZero();
			assertThat(productionSnapshot()).isEqualTo(productionBefore);
		}
	}

	@Test
	void twentyMessageLocalSmtpRunUsesOnlyTheFixedEnvelopeAndLeavesProductionStateByteStable(
			CapturedOutput output
	) throws Exception {
		int messageCount = 20;
		List<String> logicalAddresses = new ArrayList<>();
		for (int index = 0; index < messageCount; index++) {
			String id = "00000000-0000-0000-0001-" + String.format("%012d", index);
			UUID recipient = insertRecipient(id, "Safety paper " + index);
			String logical = "author" + index + "@logical-research.example";
			logicalAddresses.add(logical);
			database.sql("UPDATE campaign_recipients SET email_ciphertext = :cipher, rendered_html = "
						+ "'<p>Generated paper " + index + "</p>"
						+ "<a href=\"https://papers.example.test/abs/" + index + "\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>' WHERE id = :id")
					.bind("cipher", logical.getBytes(StandardCharsets.UTF_8)).bind("id", recipient)
					.fetch().rowsUpdated().block();
		}
		database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
				+ "WHERE id = '" + CAMPAIGN + "'").fetch().rowsUpdated().block();
		Map<String, String> productionBefore = productionSnapshot();

		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY, Duration.ofDays(30), Duration.ofMinutes(15));
		SmtpProperties liveSafety = new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), SMTP_ENCRYPTION_KEY);
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety, liveSafety, tracking, signer, Duration.ofMinutes(2));
		CampaignSafetyRepository repository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignSafetyRepository.MaterializedRun run = repository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(CAMPAIGN, ACTOR, 0, messageCount,
						policy.requireReady().hmac(), policy.requireReady().masked(), NOW, "safety20mime"))
				.block();

		try (var peers = Executors.newVirtualThreadPerTaskExecutor();
				ServerSocket listener = new ServerSocket(0, 20, InetAddress.getLoopbackAddress())) {
			List<CapturedSmtp> captured = new CopyOnWriteArrayList<>();
			var peer = peers.submit(() -> {
				for (int index = 0; index < messageCount; index++) serveSafetySmtpAttempt(listener, captured);
				return null;
			});
			database.sql("UPDATE smtp_accounts SET port = :port WHERE id = :id")
					.bind("port", listener.getLocalPort()).bind("id", SMTP).fetch().rowsUpdated().block();
			CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
					true, 20, Duration.ofMinutes(2), Duration.ofDays(180), 3,
					Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
			CampaignDeliveryRepository delivery = new CampaignDeliveryRepository(
					database, transactions, deliveryProperties, safety, policy);
			CampaignSafetyTrackingService preparer = new CampaignSafetyTrackingService(
					repository, policy, tracking, signer, new MailOpenClassifier(),
					Clock.fixed(NOW, ZoneOffset.UTC), transactions);
			ContactCrypto contactCrypto = mock(ContactCrypto.class);
			CampaignOutboundPreparer productionPreparer = claim ->
					reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
			SmtpProperties localSmtp = new SmtpProperties(false, Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), SMTP_ENCRYPTION_KEY);
			SmtpTransport transport = new SmtpTransport(
					new SmtpSecretCrypto(SMTP_ENCRYPTION_KEY), new SmtpPolicy(localSmtp), localSmtp);
			CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
					delivery, productionPreparer, repository, preparer, contactCrypto,
					transport::sendDetailed, Clock.fixed(NOW, ZoneOffset.UTC));

			for (int index = 0; index < messageCount; index++) {
				assertThat(executor.pumpOnce().block()).as("safety MIME " + index)
						.isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
			}
			assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
			peer.get(10, TimeUnit.SECONDS);
			verifyNoInteractions(contactCrypto);

			assertThat(captured).hasSize(messageCount);
			Set<String> messageIds = new java.util.HashSet<>();
			Set<String> correlations = new java.util.HashSet<>();
			for (CapturedSmtp exchange : captured) {
				assertThat(exchange.envelopeRecipient()).isEqualTo("RCPT TO:<fixed@example.test>");
				MimeMessage message = parseMime(exchange.data());
				assertThat(InternetAddress.toString(message.getRecipients(Message.RecipientType.TO)))
						.isEqualTo("fixed@example.test");
				InternetAddress from = (InternetAddress) message.getFrom()[0];
				assertThat(from.getAddress()).isEqualTo("sender@example.invalid");
				assertThat(from.getPersonal()).isEqualTo("Team");
				assertThat(InternetAddress.toString(message.getReplyTo()))
						.isEqualTo("reply@example.invalid");
				assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
				assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
				assertThat(message.getSubject()).startsWith("[SAFETY TEST] Safety paper ");
				assertThat(message.isMimeType("multipart/alternative")).isTrue();
				assertThat(mimePart(message, "text/html")).contains(
						"<strong>SAFETY TEST</strong>", "/t/o/campaign-safety-open:v1.",
						"/t/c/campaign-safety-click:v1.", "/u/campaign-safety-unsubscribe:v1.");
				assertThat(mimePart(message, "text/plain")).startsWith("[SAFETY TEST")
						.contains("/u/campaign-safety-unsubscribe:v1.");
				assertThat(message.getHeader("List-Unsubscribe", null))
						.startsWith("<https://tracking.example.test/u/campaign-safety-unsubscribe:v1.");
				assertThat(message.getHeader("List-Unsubscribe-Post", null))
						.isEqualTo("List-Unsubscribe=One-Click");
				messageIds.add(message.getHeader("Message-ID", null));
				correlations.add(message.getHeader("X-CaMel-Correlation-Id", null));
				for (String logical : logicalAddresses) assertThat(exchange.data()).doesNotContain(logical);
			}
			assertThat(messageIds).hasSize(messageCount);
			assertThat(correlations).hasSize(messageCount);
		}

		assertThat(text("SELECT status FROM campaign_safety_runs WHERE id = '" + run.id() + "'"))
				.isEqualTo("COMPLETED");
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_attempts")).isEqualTo(messageCount);
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_links")).isEqualTo(messageCount * 3);
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
		String publicView = new ObjectMapper().findAndRegisterModules().writeValueAsString(
				new CampaignSafetyService(repository, policy, 20, Clock.fixed(NOW, ZoneOffset.UTC))
						.get(CAMPAIGN, run.id()).block());
		assertThat(publicView).contains("f***@example.test").doesNotContain(
				"fixed@example.test", "renderedHtml", "renderedText", "campaign-safety-open:v1",
				"campaign-safety-click:v1", "campaign-safety-unsubscribe:v1");
		String outbox = text("SELECT payload::text FROM outbox_messages WHERE aggregate_id = '" + run.id() + "'");
		assertThat(outbox).doesNotContain("fixed@example.test", "logical-research.example", "rendered");
		assertThat(output.getAll()).doesNotContain("logical-research.example", "campaign-safety-open:v1",
				"campaign-safety-click:v1", "campaign-safety-unsubscribe:v1");
	}

	@Test
	void publicControllersRouteSafetyCallbacksWithThreeByThreeIsolationAndNoProductionMutation() {
		UUID source = insertRecipient("00000000-0000-0000-0002-000000000001", "Callback isolation");
		database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
				+ "WHERE id = '" + CAMPAIGN + "'").fetch().rowsUpdated().block();
		database.sql("UPDATE campaign_recipients SET rendered_html = '<p>Generated body</p>"
				+ "<a href=\"https://papers.example.test/abs/callback\">Paper</a>"
				+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>' WHERE id = '" + source + "'")
				.fetch().rowsUpdated().block();
		Map<String, String> productionBefore = productionSnapshot();

		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY, Duration.ofDays(30), Duration.ofMinutes(15));
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety, new SmtpProperties(true, Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), SMTP_ENCRYPTION_KEY), tracking, signer,
				Duration.ofMinutes(2));
		CampaignSafetyRepository repository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignSafetyRepository.MaterializedRun run = repository.materialize(
				new CampaignSafetyRepository.MaterializeCommand(CAMPAIGN, ACTOR, 0, 1,
						policy.requireReady().hmac(), policy.requireReady().masked(), NOW, "safetycallbacks"))
				.block();
		CampaignDeliveryRepository delivery = new CampaignDeliveryRepository(
				database, transactions, new CampaignDeliveryProperties(
				true, 20, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)), safety, policy);
		CampaignSafetyTrackingService namespace = new CampaignSafetyTrackingService(
				repository, policy, tracking, signer, new MailOpenClassifier(),
				Clock.fixed(NOW, ZoneOffset.UTC), transactions);
		AtomicReference<SmtpTransport.OutboundMessage> captured = new AtomicReference<>();
		CampaignDeliveryExecutor executor = new CampaignDeliveryExecutor(
				delivery, claim -> reactor.core.publisher.Mono.error(
						new AssertionError("production preparer must not run")), repository, namespace,
				mock(ContactCrypto.class), (account, message) -> {
					captured.set(message);
					return new SmtpTransport.SmtpOutcome(
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
							250, "250 queued");
				}, Clock.fixed(NOW, ZoneOffset.UTC));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		String openUrl = callbackUrl(captured.get().html(), "t/o", "campaign-safety-open");
		String clickUrl = callbackUrl(captured.get().html(), "t/c", "campaign-safety-click");
		String unsubscribeUrl = callbackUrl(captured.get().html(), "u", "campaign-safety-unsubscribe");
		String open = openUrl.substring(openUrl.lastIndexOf('/') + 1);
		String click = clickUrl.substring(clickUrl.lastIndexOf('/') + 1);
		String unsubscribe = unsubscribeUrl.substring(unsubscribeUrl.lastIndexOf('/') + 1);

		MailTrackingService testMail = mock(MailTrackingService.class);
		when(testMail.observe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenReturn(reactor.core.publisher.Mono.empty());
		when(testMail.click(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(reactor.core.publisher.Mono.empty());
		when(testMail.status()).thenReturn(new MailTrackingModels.TrackingStatus(
				true, tracking.publicBaseUrl(), MailTrackingModels.CallbackScope.PUBLIC_HTTPS_CONFIGURED,
				tracking.tokenTtl().getSeconds()));
		WebTestClient callbacks = WebTestClient.bindToController(
				new MailOpenController(testMail, namespace), new MailClickController(testMail, namespace),
				new CampaignUnsubscribeController(namespace)).build();

		callbacks.head().uri("/t/o/{token}", open).exchange().expectStatus().isOk();
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events")).isZero();
		callbacks.get().uri("/t/o/{token}", open).header(HttpHeaders.USER_AGENT, "Human Browser")
				.exchange().expectStatus().isOk();
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events")).isEqualTo(1);
		callbacks.head().uri("/t/c/{token}", click).exchange().expectStatus().isFound()
				.expectHeader().valueEquals(HttpHeaders.LOCATION, "https://papers.example.test/abs/callback");
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events")).isEqualTo(1);
		callbacks.get().uri("/t/c/{token}", click).header(HttpHeaders.USER_AGENT, "Human Browser")
				.exchange().expectStatus().isFound()
				.expectHeader().valueEquals(HttpHeaders.LOCATION, "https://papers.example.test/abs/callback");
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events")).isEqualTo(2);
		callbacks.get().uri("/u/{token}", unsubscribe).exchange().expectStatus().isOk();
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events")).isEqualTo(2);
		callbacks.post().uri("/u/{token}", unsubscribe).exchange().expectStatus().isOk();
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events")).isEqualTo(3);

		AuthenticationRequestContext request = new AuthenticationRequestContext(
				"198.51.100.44", "Human Browser", "safety-callback-cross");
		assertThat(namespace.observeOpen(click, HttpHeaders.EMPTY, request).block()).isFalse();
		assertThat(namespace.observeOpen(unsubscribe, HttpHeaders.EMPTY, request).block()).isFalse();
		assertThat(namespace.click(open, HttpHeaders.EMPTY, request, true).block()).isNull();
		assertThat(namespace.click(unsubscribe, HttpHeaders.EMPTY, request, true).block()).isNull();
		assertThat(namespace.unsubscribe(open, request).block()).isFalse();
		assertThat(namespace.unsubscribe(click, request).block()).isFalse();
		CampaignTrackingSigner productionSigner = new CampaignTrackingSigner(SAFETY_KEY);
		MailTrackingSigner testSigner = new MailTrackingSigner(SAFETY_KEY);
		assertThat(namespace.observeOpen(productionSigner.issueOpen(source, NOW.plusSeconds(600)),
				HttpHeaders.EMPTY, request).block()).isFalse();
		assertThat(namespace.observeOpen(testSigner.issue(UUID.randomUUID(), NOW.plusSeconds(600)),
				HttpHeaders.EMPTY, request).block()).isFalse();
		callbacks.get().uri("/t/o/{token}", open).header(HttpHeaders.USER_AGENT, "Human Browser")
				.exchange().expectStatus().isOk();
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_events")).isEqualTo(3);

		CampaignSafetyService.SafetyRunView view = new CampaignSafetyService(
				repository, policy, 20, Clock.fixed(NOW, ZoneOffset.UTC)).get(CAMPAIGN, run.id()).block();
		assertThat(view.events()).isEqualTo(new CampaignSafetyService.SafetyEventCounts(1, 1, 1, 0, 0, 0));
		assertThat(productionSnapshot()).isEqualTo(productionBefore);
	}

	@Test
	void frozenRetryRejectsEveryCapabilityStructureTamperBeforeSmtp() {
		for (String tamper : List.of(
				"cross-message-unsubscribe", "attacker-query", "wrong-origin", "wrong-path",
				"open-in-anchor", "click-in-image", "custom-attribute", "bare-token",
				"duplicate-token-orphan", "span-split-unsubscribe", "comment-split-open",
				"span-split-click")) {
			reset();
			UUID source = insertRecipient("00000000-0000-0000-0000-000000000081", "Frozen tamper " + tamper);
			database.sql("UPDATE campaigns SET tracking_opens_enabled = true, tracking_clicks_enabled = true "
					+ "WHERE id = '" + CAMPAIGN + "'").fetch().rowsUpdated().block();
			database.sql("UPDATE campaign_recipients SET rendered_html = '<p>Generated body</p>"
					+ "<a href=\"https://papers.example.test/abs/1\">Paper</a>"
					+ "<a href=\"https://papers.example.test/abs/2\">Other paper</a>"
					+ "<a href=\"{{unsubscribe_url}}\">unsubscribe</a>' WHERE id = '" + source + "'")
					.fetch().rowsUpdated().block();
			Map<String, String> productionBefore = productionSnapshot();

			CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
			CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
			MailTrackingProperties tracking = new MailTrackingProperties(
					true, "https://tracking.example.test", SAFETY_KEY, Duration.ofDays(30), Duration.ofMinutes(15));
			CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
					safety, new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
			CampaignSafetyRepository repository = new CampaignSafetyRepository(
					database, transactions, new ObjectMapper().findAndRegisterModules());
			CampaignSafetyRepository.MaterializedRun run = repository.materialize(
					new CampaignSafetyRepository.MaterializeCommand(CAMPAIGN, ACTOR, 0, 1,
							policy.requireReady().hmac(), policy.requireReady().masked(), NOW, "safetytamper1"))
					.block();
			CampaignDeliveryProperties deliveryProperties = new CampaignDeliveryProperties(
					true, 10, Duration.ofMinutes(2), Duration.ofDays(180), 3,
					Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
			CampaignOutboundPreparer productionPreparer = claim ->
					reactor.core.publisher.Mono.error(new AssertionError("production preparer must not run"));
			CampaignDeliveryRepository initialDelivery = new CampaignDeliveryRepository(
					database, transactions, deliveryProperties, safety, policy);
			CampaignSafetyTrackingService initialTracking = new CampaignSafetyTrackingService(
					repository, policy, tracking, signer, new MailOpenClassifier(),
					Clock.fixed(NOW, ZoneOffset.UTC), transactions);
			CampaignDeliveryExecutor initialExecutor = new CampaignDeliveryExecutor(
					initialDelivery, productionPreparer, repository, initialTracking, mock(ContactCrypto.class),
					(account, message) -> { throw new SmtpTransportException(
							SmtpTransportException.FailureCategory.SMTP_REJECTED,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,
							com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.RCPT_TO,
							450, "450 temporary", true); }, Clock.fixed(NOW, ZoneOffset.UTC));
			assertThat(initialExecutor.pumpOnce().block()).as(tamper)
					.isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);

			UUID messageId = uuid("SELECT id FROM campaign_safety_messages WHERE run_id = '" + run.id() + "'");
			String frozenHtml = text("SELECT rendered_html FROM campaign_safety_messages WHERE id = '" + messageId + "'");
			String frozenText = text("SELECT rendered_text FROM campaign_safety_messages WHERE id = '" + messageId + "'");
			RenderedBodies tampered = tamper(tamper, messageId, frozenHtml, frozenText, signer);
			database.sql("UPDATE campaign_safety_messages SET rendered_html = :html, rendered_text = :text WHERE id = :id")
					.bind("html", tampered.html()).bind("text", tampered.text()).bind("id", messageId)
					.fetch().rowsUpdated().block();
			int linkCount = integer("SELECT count(*)::int FROM campaign_safety_links WHERE safety_message_id = '"
					+ messageId + "'");

			Instant retryAt = NOW.plus(Duration.ofMinutes(1));
			CampaignDeliveryRepository retryDelivery = new CampaignDeliveryRepository(
					database, transactions, deliveryProperties, safety, policy);
			CampaignSafetyTrackingService retryTracking = new CampaignSafetyTrackingService(
					repository, policy, tracking, signer, new MailOpenClassifier(),
					Clock.fixed(retryAt, ZoneOffset.UTC), transactions);
			AtomicInteger smtpCalls = new AtomicInteger();
			CampaignDeliveryExecutor retryExecutor = new CampaignDeliveryExecutor(
					retryDelivery, productionPreparer, repository, retryTracking, mock(ContactCrypto.class),
					(account, message) -> {
						smtpCalls.incrementAndGet();
						return new SmtpTransport.SmtpOutcome(
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,
								com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage.POST_DATA,
								250, "250 queued");
					}, Clock.fixed(retryAt, ZoneOffset.UTC));

			assertThat(retryExecutor.pumpOnce().block()).as(tamper)
					.isEqualTo(CampaignDeliveryExecutor.PumpResult.PERMANENT_FAILURE);
			assertThat(smtpCalls).as(tamper).hasValue(0);
			assertThat(integer("SELECT count(*)::int FROM campaign_safety_links WHERE safety_message_id = '"
					+ messageId + "'")).as(tamper).isEqualTo(linkCount);
			assertThat(productionSnapshot()).as(tamper).isEqualTo(productionBefore);
		}
	}

	private RenderedBodies tamper(
			String kind, UUID messageId, String html, String plainText, CampaignSafetySigner signer
	) {
		String unsubscribe = callbackUrl(html, "u", "campaign-safety-unsubscribe");
		String open = callbackUrl(html, "t/o", "campaign-safety-open");
		String click = callbackUrl(html, "t/c", "campaign-safety-click");
		List<String> clicks = callbackUrls(html, "t/c", "campaign-safety-click");
		return switch (kind) {
			case "cross-message-unsubscribe" -> {
				String foreign = "https://tracking.example.test/u/"
						+ signer.issueUnsubscribe(UUID.randomUUID(), NOW.plus(Duration.ofDays(30)));
				yield new RenderedBodies(html + "<a href=\"" + foreign + "\">foreign</a>", plainText);
			}
			case "attacker-query" -> new RenderedBodies(
					html.replace(unsubscribe, "https://attacker.example/collect?next=" + unsubscribe),
					plainText.replace(unsubscribe, "https://attacker.example/collect?next=" + unsubscribe));
			case "wrong-origin" -> new RenderedBodies(
					html.replace("https://tracking.example.test", "https://attacker.example"),
					plainText.replace("https://tracking.example.test", "https://attacker.example"));
			case "wrong-path" -> new RenderedBodies(
					html.replace(unsubscribe, unsubscribe.replace("/u/", "/t/c/")),
					plainText.replace(unsubscribe, unsubscribe.replace("/u/", "/t/c/")));
			case "open-in-anchor" -> new RenderedBodies(
					html.replace("<img src=\"" + open, "<a href=\"" + open), plainText);
			case "click-in-image" -> new RenderedBodies(
					html.replace("<a href=\"" + click, "<img src=\"" + click), plainText);
			case "custom-attribute" -> new RenderedBodies(
					html.replace("href=\"" + unsubscribe, "data-callback=\"" + unsubscribe), plainText);
			case "bare-token" -> new RenderedBodies(
					html, plainText.replace(unsubscribe, unsubscribe.substring(unsubscribe.lastIndexOf('/') + 1)));
			case "duplicate-token-orphan" -> new RenderedBodies(
					html.replace(clicks.get(1), clicks.get(0)), plainText);
			case "span-split-unsubscribe" -> new RenderedBodies(
					html + "<p>campaign-safety-unsubscribe:<span>"
							+ tokenSuffix(unsubscribe) + "</span></p>", plainText);
			case "comment-split-open" -> new RenderedBodies(
					html + "<p>campaign-safety-open:<!-- split -->"
							+ tokenSuffix(open) + "</p>", plainText);
			case "span-split-click" -> new RenderedBodies(
					html + "<p>campaign-safety-click:<span>"
							+ tokenSuffix(click) + "</span></p>", plainText);
			default -> throw new IllegalArgumentException("Unknown tamper " + kind + " for " + messageId);
		};
	}

	private String tokenSuffix(String callbackUrl) {
		String token = callbackUrl.substring(callbackUrl.lastIndexOf('/') + 1);
		return token.substring(token.indexOf(':') + 1);
	}

	private String callbackUrl(String value, String path, String prefix) {
		return callbackUrls(value, path, prefix).getFirst();
	}

	private List<String> callbackUrls(String value, String path, String prefix) {
		Matcher matcher = Pattern.compile("https://tracking\\.example\\.test/" + Pattern.quote(path)
				+ "/" + Pattern.quote(prefix) + ":[^\\s\\\"'<>]+").matcher(value);
		List<String> urls = new ArrayList<>();
		while (matcher.find()) urls.add(matcher.group());
		if (urls.isEmpty()) throw new AssertionError("Missing callback " + path);
		return List.copyOf(urls);
	}

	private SafetyRuntime safetyRuntime() {
		CampaignSafetySigner signer = new CampaignSafetySigner(SAFETY_KEY);
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		MailTrackingProperties tracking = new MailTrackingProperties(
				true, "https://tracking.example.test", SAFETY_KEY, Duration.ofDays(30), Duration.ofMinutes(15));
		CampaignSafetyRuntimePolicy policy = new CampaignSafetyRuntimePolicy(
				safety, new SmtpProperties(true, java.util.Set.of("localhost"), Duration.ofSeconds(2),
				Duration.ofSeconds(2), Duration.ofSeconds(2), ""), tracking, signer, Duration.ofMinutes(2));
		CampaignSafetyRepository repository = new CampaignSafetyRepository(
				database, transactions, new ObjectMapper().findAndRegisterModules());
		CampaignDeliveryRepository delivery = new CampaignDeliveryRepository(
				database, transactions, new CampaignDeliveryProperties(
				true, 20, Duration.ofMinutes(2), Duration.ofDays(180), 3,
				Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)), safety, policy);
		return new SafetyRuntime(repository, delivery, policy);
	}

	private CampaignSafetyRepository.MaterializedRun materialize(
			SafetyRuntime runtime, int limit, String traceId
	) {
		CampaignSafetyRuntimePolicy.Destination destination = runtime.policy().requireReady();
		return runtime.repository().materialize(new CampaignSafetyRepository.MaterializeCommand(
				CAMPAIGN, ACTOR, 0, limit, destination.hmac(), destination.masked(), NOW, traceId)).block();
	}

	private UUID insertRecipient(String id, String subject) {
		return insertRecipient(id, subject, "GENERATED");
	}

	private UUID insertRecipient(String id, String subject, String personalization) {
		UUID recipient = UUID.fromString(id);
		database.sql("""
				INSERT INTO campaign_recipients (
				    id, campaign_id, email_ciphertext, email_nonce, email_hmac, email_domain,
				    confidence, status, personalization_status, rendered_subject,
				    rendered_html, rendered_text, personalized_at, next_attempt_at
				) VALUES (
				    :id, :campaign, decode('aabb','hex'), decode('ccdd','hex'), digest(CAST(:id AS text),'sha256'),
				    'logical.example', 'HIGH', 'QUEUED', :personalization, :subject,
				    '<p>Generated body</p><p>{{unsubscribe_url}}</p>',
				    'Generated body {{unsubscribe_url}}', :now, :now
				)
				""").bind("id", recipient).bind("campaign", CAMPAIGN).bind("personalization", personalization)
				.bind("subject", subject).bind("now", NOW).fetch().rowsUpdated().block();
		return recipient;
	}

	private void seed() {
		database.sql("INSERT INTO users (id, username, email, password_hash, display_name) VALUES ('" + ACTOR
				+ "','safety-admin','admin@example.invalid','hash','Safety Admin')").fetch().rowsUpdated().block();
		database.sql("INSERT INTO email_templates (id,name,status,created_by,updated_by) VALUES "
				+ "('70000000-0000-0000-0000-000000000001','Safety','ACTIVE','" + ACTOR + "','" + ACTOR + "')")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO email_template_versions (id,template_id,version_number,subject_template,from_name_template,"
				+ "reply_to,html_content,text_content,content_size_bytes,created_by) VALUES "
				+ "('70100000-0000-0000-0000-000000000001','70000000-0000-0000-0000-000000000001',1,'Subject',"
				+ "'Team','reply@example.invalid','<p>body</p>','body',4,'" + ACTOR + "')")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO smtp_accounts (id,name,host,port,tls_mode,from_email,default_from_name,reply_to,"
				+ "per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,last_tested_at,last_test_status,"
				+ "created_by,updated_at) VALUES ('" + SMTP + "','Safety SMTP','localhost',1025,'PLAIN_LOCAL_ONLY',"
				+ "'sender@example.invalid','Team','reply@example.invalid',100,100,1000,100,true,TIMESTAMPTZ '" + NOW
				+ "','SUCCEEDED','" + ACTOR + "',TIMESTAMPTZ '" + NOW.minusSeconds(1) + "')")
				.fetch().rowsUpdated().block();
		database.sql("INSERT INTO campaigns (id,name,purpose,status,template_id,template_version_id,smtp_account_id,"
				+ "from_name,from_email,reply_to,unsubscribe_enabled,created_by,updated_by) VALUES ('" + CAMPAIGN
				+ "','Safety source','Approved purpose','APPROVED','70000000-0000-0000-0000-000000000001',"
				+ "'70100000-0000-0000-0000-000000000001','" + SMTP + "','Team','sender@example.invalid',"
				+ "'reply@example.invalid',true,'" + ACTOR + "','" + ACTOR + "')").fetch().rowsUpdated().block();
	}

	private int integer(String sql) {
		return database.sql(sql).map((row, metadata) -> row.get(0, Integer.class)).one().block();
	}

	private long longValue(String sql) {
		return database.sql(sql).map((row, metadata) -> row.get(0, Number.class).longValue()).one().block();
	}

	private void assertNoSafetyStartSideEffects() {
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_runs")).isZero();
		assertThat(integer("SELECT count(*)::int FROM campaign_safety_messages")).isZero();
		assertThat(integer("SELECT count(*)::int FROM outbox_messages WHERE message_type = 'CAMPAIGN_DELIVERY_WAKEUP'"))
				.isZero();
		assertThat(integer("SELECT count(*)::int FROM audit_logs WHERE action = 'CAMPAIGN_SAFETY_STARTED'"))
				.isZero();
	}

	private boolean awaitDatabaseLockWait() throws Exception {
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

	private String text(String sql) {
		return database.sql(sql).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private Instant instant(String sql) {
		return database.sql(sql).map((row, metadata) -> row.get(0, Instant.class)).one().block();
	}

	private UUID uuid(String sql) {
		return database.sql(sql).map((row, metadata) -> row.get(0, UUID.class)).one().block();
	}

	private String safetyLinkTopology(UUID messageId) {
		return text("SELECT COALESCE(string_agg(id::text || ':' || token_type || ':' || COALESCE(target_url, ''), "
				+ "'|' ORDER BY token_type, id), '') FROM campaign_safety_links WHERE safety_message_id = '"
				+ messageId + "'");
	}

	private String safetyTokenDigests(UUID messageId) {
		return text("SELECT COALESCE(string_agg(encode(token_hash, 'hex'), '|' ORDER BY token_type, id), '') "
				+ "FROM campaign_safety_links WHERE safety_message_id = '" + messageId + "'");
	}

	private Map<String, String> safetyFrozenSnapshot(UUID messageId) {
		return Map.of(
				"subject", text("SELECT rendered_subject FROM campaign_safety_messages WHERE id = '"
						+ messageId + "'"),
				"html", text("SELECT rendered_html FROM campaign_safety_messages WHERE id = '"
						+ messageId + "'"),
				"text", text("SELECT rendered_text FROM campaign_safety_messages WHERE id = '"
						+ messageId + "'"),
				"links", text("SELECT COALESCE(string_agg(to_jsonb(link_row)::text, E'\\n' "
						+ "ORDER BY to_jsonb(link_row)::text), '') FROM campaign_safety_links link_row "
						+ "WHERE safety_message_id = '" + messageId + "'"));
	}

	private CampaignDeliveryExecutor safetyExecutor(
			CampaignSafetyRepository safetyRepository, CampaignSafetyRuntimePolicy policy,
			MailTrackingProperties tracking, CampaignSafetySigner signer,
			CampaignDeliveryProperties deliveryProperties, CampaignOutboundPreparer productionPreparer,
			CampaignDeliveryExecutor.CampaignSmtpSender sender, Instant now
	) {
		CampaignSafetyProperties safety = new CampaignSafetyProperties(true, "fixed@example.test", 20);
		CampaignDeliveryRepository delivery = new CampaignDeliveryRepository(
				database, transactions, deliveryProperties, safety, policy);
		CampaignSafetyTrackingService safetyTracking = new CampaignSafetyTrackingService(
				safetyRepository, policy, tracking, signer, new MailOpenClassifier(),
				Clock.fixed(now, ZoneOffset.UTC), transactions);
		return new CampaignDeliveryExecutor(
				delivery, productionPreparer, safetyRepository, safetyTracking, mock(ContactCrypto.class),
				sender, Clock.fixed(now, ZoneOffset.UTC));
	}

	private Map<String, String> productionSnapshot() {
		Map<String, String> snapshot = new java.util.LinkedHashMap<>();
		for (String table : List.of(
				"campaigns", "campaign_recipients", "delivery_attempts", "campaign_links",
				"tracking_tokens", "tracking_events", "unsubscribe_records", "suppression_entries",
				"recipient_delivery_cooldowns", "campaign_exclusions", "contacts")) {
			snapshot.put(table, text("SELECT encode(digest(COALESCE(string_agg(to_jsonb(row_value)::text, "
					+ "E'\\n' ORDER BY to_jsonb(row_value)::text), ''), 'sha256'), 'hex') FROM "
					+ table + " row_value"));
		}
		return Map.copyOf(snapshot);
	}

	private void serveSafetySmtpAttempt(ServerSocket listener, List<CapturedSmtp> captured) {
		try (var socket = listener.accept();
				var reader = new BufferedReader(new InputStreamReader(
						socket.getInputStream(), StandardCharsets.US_ASCII));
				var writer = new PrintWriter(new OutputStreamWriter(
						socket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
			socket.setSoTimeout(5_000);
			writer.print("220 localhost campaign safety SMTP fixture\r\n");
			writer.flush();
			String envelope = null;
			String command;
			while ((command = reader.readLine()) != null) {
				if (command.regionMatches(true, 0, "RCPT TO:", 0, 8)) envelope = command;
				if (command.equals("DATA")) {
					writer.print("354 End with a dot\r\n");
					writer.flush();
					StringBuilder data = new StringBuilder();
					while ((command = reader.readLine()) != null && !command.equals(".")) {
						data.append(command).append("\r\n");
					}
					captured.add(new CapturedSmtp(envelope, data.toString()));
					writer.print("250 2.0.0 queued\r\n");
					writer.flush();
				}
				else if (command.equals("QUIT")) {
					writer.print("221 bye\r\n");
					writer.flush();
					return;
				}
				else {
					writer.print("250 localhost\r\n");
					writer.flush();
				}
			}
		}
		catch (Exception error) {
			throw new AssertionError(error);
		}
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

	private byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private String compatibilityPrefixEncoded(String value) {
		return value.replace(":v1.", ":％76％31.");
	}

	private String normalizeCapabilities(String value, String open, String click, String unsubscribe) {
		return value.replace(open, "{{open_capability}}")
				.replace(click, "{{click_capability}}")
				.replace(unsubscribe, "{{unsubscribe_capability}}");
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
	}

	private record SafetyRuntime(
			CampaignSafetyRepository repository, CampaignDeliveryRepository delivery,
			CampaignSafetyRuntimePolicy policy
	) { }

	private record RenderedBodies(String html, String text) { }

	private record CapturedSmtp(String envelopeRecipient, String data) { }
}
