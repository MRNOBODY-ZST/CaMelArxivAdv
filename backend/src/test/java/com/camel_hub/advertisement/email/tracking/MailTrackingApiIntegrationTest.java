package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.common.security.SecurityConfiguration;
import com.camel_hub.advertisement.common.security.SecurityErrorResponseWriter;
import com.camel_hub.advertisement.email.EmailConfiguration;
import com.camel_hub.advertisement.email.smtp.SmtpController;
import com.camel_hub.advertisement.email.smtp.SmtpService;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpPolicy;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.email.template.TemplateController;
import com.camel_hub.advertisement.email.template.TemplateModels;
import com.camel_hub.advertisement.email.template.TemplateService;
import com.camel_hub.advertisement.email.template.TemplateRepository;
import com.camel_hub.advertisement.email.template.TemplateAssetSigner;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.identity.config.IdentityConfiguration;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(classes = MailTrackingApiIntegrationTest.TestApplication.class, properties = {
		"app.auth.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.auth.fingerprint-hmac-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.smtp.encryption-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.template.assets.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.mail-tracking.enabled=true",
		"app.mail-tracking.public-base-url=http://localhost:8080",
		"app.mail-tracking.signing-key-base64=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
		"app.mail-tracking.token-ttl=PT720H"
})
@ActiveProfiles("api")
class MailTrackingApiIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_mail_tracking_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final AuthenticationRequestContext CONTEXT =
			new AuthenticationRequestContext("127.0.0.1", "JUnit", "mail-tracking-test");
	private static final String TRACKING_KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";
	static {
		POSTGRES.start();
	}

	@Autowired private ApplicationContext applicationContext;
	@Autowired private DatabaseClient database;
	@Autowired private SmtpService smtp;
	@Autowired private TemplateService templates;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private MailTrackingService tracking;
	@Autowired private MailTrackingRepository trackingRepository;
	@MockitoBean private SmtpTransport transport;

	private final List<SmtpTransport.OutboundMessage> outbound = new CopyOnWriteArrayList<>();
	private WebTestClient anonymous;
	private WebTestClient manager;
	private UUID accountId;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
		registry.add("spring.flyway.user", POSTGRES::getUsername);
		registry.add("spring.flyway.password", POSTGRES::getPassword);
		registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
				+ POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
		registry.add("spring.r2dbc.username", POSTGRES::getUsername);
		registry.add("spring.r2dbc.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void setUp() {
		database.sql("TRUNCATE users, smtp_accounts, email_templates, audit_logs CASCADE")
				.fetch().rowsUpdated().block();
		database.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'tracking-admin',
				        'tracking-admin@example.invalid', 'hash', 'Tracking Admin')
				""").fetch().rowsUpdated().block();
		outbound.clear();
		doAnswer(invocation -> {
			outbound.add(invocation.getArgument(1));
			return null;
		}).when(transport).send(any(), any());
		accountId = smtp.create(ACTOR, new SmtpService.SmtpCommand(
				"Local Mailpit", "mailpit", 1025, "PLAIN_LOCAL_ONLY", null, null,
				"sender@example.invalid", "Research Team", "reply@example.invalid",
				10, 100, 1_000, 50, true), CONTEXT).block().id();
		anonymous = WebTestClient.bindToApplicationContext(applicationContext).apply(springSecurity())
				.configureClient().responseTimeout(Duration.ofSeconds(15)).build();
		manager = withPermissions("smtp:read", "smtp:manage", "template:read", "template:manage");
	}

	@Test
	void diagnosticOptInReachesTheActualHtmlAndItsCallbackUpdatesOnlyTheSendRecord() throws Exception {
		JsonNode result = diagnostic(Map.of("recipient", "qa@example.invalid", "subject", "QA",
				"body", "Diagnostic <body>", "trackOpens", true));
		SmtpTransport.OutboundMessage message = outbound.getFirst();
		assertThat(message.html()).contains("/t/o/").doesNotContain("qa@example.invalid/t/o");
		assertThat(message.text()).isEqualTo("Diagnostic <body>");
		assertThat(message.subject()).isEqualTo("QA");
		assertThat(result.path("status").asText()).isEqualTo("SMTP_ACCEPTED");
		assertThat(result.size()).isEqualTo(3);
		String id = result.path("correlationId").asText();
		assertThat(id).isEqualTo(message.correlationId());
		assertThat(detail(id).at("/record/rawOpenCount").asInt()).isZero();

		anonymous.get().uri(pixelPath(message.html())).exchange().expectStatus().isOk()
				.expectHeader().contentType("image/gif")
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*");
		JsonNode detail = detail(id);
		assertThat(detail.at("/record/rawOpenCount").asInt()).isEqualTo(1);
		assertThat(detail.at("/record/status").asText()).isEqualTo("SMTP_ACCEPTED");
		assertThat(detail.at("/record/recipientMasked").asText()).isEqualTo("q***@example.invalid");
		assertThat(detail.at("/record/source").asText()).isEqualTo("SMTP_DIAGNOSTIC");
		assertThat(detail.at("/events/0/classification").asText()).isEqualTo("UNCLASSIFIED");
		assertThat(count("campaign_recipients")).isZero();
		assertThat(count("tracking_events")).isZero();
	}

	@Test
	void templateOptInTracksOnlyTheTransmittedHtmlWithoutChangingPreviewOrSavedContent() throws Exception {
		var command = new TemplateService.TemplateCommand("Tracking template", "QA", "DRAFT",
				new TemplateModels.TemplateDraft("Paper {{paper_title}}", "Research Team", "reply@example.invalid",
						"<p>{{paper_title}}</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
						"Paper {{paper_title}}\nUnsubscribe {{unsubscribe_url}}", false));
		var variables = Map.of("paper_title", "A < B", "unsubscribe_url", "https://example.invalid/unsubscribe");
		var template = templates.create(ACTOR, command, CONTEXT).block();
		var before = templates.preview(command, variables);
		String body = manager.post().uri("/api/v1/templates/{id}/test-send", template.id())
				.bodyValue(Map.of("smtpAccountId", accountId, "recipient", "qa@example.invalid",
						"variables", variables, "trackOpens", true))
				.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		SmtpTransport.OutboundMessage message = outbound.getFirst();
		assertThat(message.html()).contains("/t/o/").contains("A &lt; B");
		assertThat(message.subject()).isEqualTo("Paper A < B");
		assertThat(message.text()).isEqualTo(before.rendered().text());
		assertThat(templates.preview(command, variables)).isEqualTo(before);
		assertThat(templates.get(template.id()).block().htmlContent()).doesNotContain("/t/o/");
		assertThat(templates.versions(template.id()).block()).hasSize(1);
		assertThat(detail(objectMapper.readTree(body).path("correlationId").asText())
				.at("/record/source").asText()).isEqualTo("TEMPLATE_TEST");
		manager.post().uri("/api/v1/templates/{id}/test-send", template.id())
				.bodyValue(Map.of("smtpAccountId", accountId, "recipient", "qa@example.invalid", "variables", variables))
				.exchange().expectStatus().isOk();
		assertThat(outbound.get(1).html()).doesNotContain("/t/o/");
		assertThat(outbound.get(1).text()).isEqualTo(message.text());
		assertThat(outbound.get(1).subject()).isEqualTo(message.subject());
		assertThat(detail(outbound.get(1).correlationId()).at("/record/trackingEnabled").asBoolean()).isFalse();
	}

	@Test
	void signedTemplateAssetsRemainAbsoluteInTheRealOutboundMessageWithTrackingEnabled() throws Exception {
		TemplateRepository repository = applicationContext.getBean(TemplateRepository.class);
		UUID templateId = repository.createTemplate("With image", null, TemplateRepository.TemplateStatus.DRAFT, ACTOR).block().id();
		String assetPath = applicationContext.getBean(TemplateAssetSigner.class).path(templateId, UUID.randomUUID());
		var prepared = applicationContext.getBean(TemplateEngine.class).prepare(new TemplateModels.TemplateDraft(
				"Subject", "Research Team", "reply@example.invalid", "<p><img src=\"" + assetPath + "\"></p>"
						+ "<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "Unsubscribe {{unsubscribe_url}}", false));
		repository.insertVersion(templateId, 1, prepared, ACTOR).block();
		manager.post().uri("/api/v1/templates/{id}/test-send", templateId)
				.bodyValue(Map.of("smtpAccountId", accountId, "recipient", "qa@example.invalid", "trackOpens", true,
						"variables", Map.of("unsubscribe_url", "https://example.invalid/unsubscribe")))
				.exchange().expectStatus().isOk();
		assertThat(outbound.getFirst().html()).contains("src=\"http://localhost:8080/api/v1/template-assets/")
				.contains("/t/o/").doesNotContain("src=\"/api/v1/template-assets/");
		assertThat(detail(outbound.getFirst().correlationId()).at("/record/status").asText()).isEqualTo("SMTP_ACCEPTED");
	}

	@Test
	void trackedSendRewritesEligibleLinksAndRedirectsWithoutTrustingARequestTarget() throws Exception {
		String target = "https://example.invalid/paper?id=42";
		String html = "<p><a href=\"" + target + "\">Paper details</a>"
				+ "<a href=\"" + target + "\">Repeated target</a>"
				+ "<a href=\"mailto:author@example.invalid\">Mail author</a>"
				+ "<a href=\"/relative\">Relative</a>"
				+ "<a href=\"https://user:secret@example.invalid/private\">Unsafe</a></p>";
		String correlationId = UUID.randomUUID().toString();
		tracking.send(ACTOR, accountId, MailTrackingModels.Source.TEMPLATE_TEST,
				new SmtpTransport.OutboundMessage("qa@example.invalid", "Tracked links", "Research Team",
						"reply@example.invalid", html, "Plain text unchanged", correlationId), true, outbound::add).block();

		var links = org.jsoup.Jsoup.parseBodyFragment(outbound.getFirst().html()).select("a[href]");
		assertThat(links).hasSize(5);
		assertThat(links.get(0).attr("href")).startsWith("http://localhost:8080/t/c/")
				.isEqualTo(links.get(1).attr("href"));
		assertThat(links.get(2).attr("href")).isEqualTo("mailto:author@example.invalid");
		assertThat(links.get(3).attr("href")).isEqualTo("/relative");
		assertThat(links.get(4).attr("href")).isEqualTo("https://user:secret@example.invalid/private");
		assertThat(outbound.getFirst().text()).isEqualTo("Plain text unchanged");

		String clickPath = URI.create(links.getFirst().attr("href")).getPath();
		anonymous.head().uri(clickPath).exchange().expectStatus().isFound()
				.expectHeader().valueEquals("Location", target).expectBody().isEmpty();
		assertThat(detail(correlationId).at("/record/rawClickCount").asLong()).isZero();
		anonymous.get().uri(uriBuilder -> uriBuilder.path(clickPath)
				.queryParam("url", "https://attacker.example.invalid").build()).exchange().expectStatus().isFound()
				.expectHeader().valueEquals("Location", target)
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer");
		assertThat(detail(correlationId).at("/record/rawClickCount").asLong()).isEqualTo(1);
		anonymous.post().uri(clickPath).exchange().expectStatus().isEqualTo(405).expectHeader().valueEquals("Allow", "GET,HEAD");
		assertThat(detail(correlationId).at("/record/rawClickCount").asLong()).isEqualTo(1);

		String altered = clickPath.substring(0, clickPath.length() - 1)
				+ (clickPath.endsWith("A") ? "B" : "A");
		String body = anonymous.get().uri(altered).exchange().expectStatus().isNotFound()
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(body).isNullOrEmpty();
	}

	@Test
	void clickCallbacksDeduplicateAndKeepAutomationClassifications() throws Exception {
		String correlationId = UUID.randomUUID().toString();
		String clickPath = trackedClickPath(correlationId, "https://example.invalid/paper/42");
		String token = clickPath.substring("/t/c/".length());
		HttpHeaders unknown = new HttpHeaders();
		unknown.set(HttpHeaders.USER_AGENT, "Unknown click client");
		reactor.core.publisher.Flux.range(0, 24)
				.flatMap(ignored -> tracking.click(token, unknown, true), 24).blockLast();
		anonymous.get().uri(clickPath).header("User-Agent", "Unknown click client")
				.header("Sec-Purpose", "prefetch").exchange().expectStatus().isFound();
		anonymous.get().uri(clickPath).header("User-Agent", "GoogleImageProxy")
				.exchange().expectStatus().isFound();
		anonymous.get().uri(clickPath).header("User-Agent", "Proofpoint Scanner")
				.exchange().expectStatus().isFound();

		JsonNode detail = detail(correlationId);
		assertThat(detail.at("/record/rawClickCount").asLong()).isEqualTo(4);
		assertThat(detail.at("/record/automatedClickCount").asLong()).isEqualTo(3);
		assertThat(detail.path("clickEvents").findValuesAsText("classification"))
				.containsExactlyInAnyOrder("UNCLASSIFIED", "PREFETCH", "IMAGE_PROXY", "BOT");
		String rows = database.sql("SELECT jsonb_agg(to_jsonb(event))::text AS value FROM mail_click_events event")
				.map((row, metadata) -> row.get("value", String.class)).one().block();
		assertThat(rows).doesNotContain("Unknown click client", "GoogleImageProxy", "Proofpoint Scanner", "127.0.0.1");
	}

	@Test
	void invalidExpiredUnknownAndFailedClickTokensNeverRedirect() throws Exception {
		String correlationId = UUID.randomUUID().toString();
		String clickPath = trackedClickPath(correlationId, "https://example.invalid/paper/42");
		String token = clickPath.substring("/t/c/".length());
		MailTrackingSigner signer = new MailTrackingSigner(TRACKING_KEY);
		Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		List<String> invalid = List.of(
				"/t/c",
				clickPath + "/extra",
				"/t/c/" + token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A"),
				"/t/c/" + signer.issueClick(UUID.randomUUID(), UUID.randomUUID(), now.minusSeconds(1)),
				"/t/c/" + signer.issueClick(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(3600)));
		for (String path : invalid) {
			anonymous.get().uri(path).exchange().expectStatus().isNotFound()
					.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
					.expectHeader().valueEquals("Referrer-Policy", "no-referrer");
		}
		database.sql("""
				UPDATE mail_send_records SET status = 'FAILED', failure_category = 'SMTP_REJECTED', completed_at = now()
				WHERE id = :id
				""").bind("id", UUID.fromString(correlationId)).fetch().rowsUpdated().block();
		anonymous.get().uri(clickPath).exchange().expectStatus().isNotFound();
		assertThat(detail(correlationId).at("/record/rawClickCount").asLong()).isZero();
	}

	@Test
	void clickObservationStorageFailureDoesNotBreakAResolvedRedirect() throws Exception {
		String correlationId = UUID.randomUUID().toString();
		String target = "https://example.invalid/paper/42";
		String clickPath = trackedClickPath(correlationId, target);
		withRejectedWrites("mail_click_events", "INSERT", () -> anonymous.get().uri(clickPath).exchange()
				.expectStatus().isFound().expectHeader().valueEquals("Location", target));
		assertThat(detail(correlationId).at("/record/rawClickCount").asLong()).isZero();
	}

	@Test
	void omittedOptInStillCreatesAnUntrackedRecordWithoutChangingTheOutboundMessage() throws Exception {
		JsonNode result = diagnostic(Map.of("recipient", "qa@example.invalid", "subject", "QA", "body", "body"));
		assertThat(outbound.getFirst().html()).isEqualTo("<p>body</p>");
		JsonNode record = detail(result.path("correlationId").asText()).path("record");
		assertThat(record.path("trackingEnabled").asBoolean()).isFalse();
		assertThat(record.path("trackingExpiresAt").isNull()).isTrue();
		assertThat(record.path("status").asText()).isEqualTo("SMTP_ACCEPTED");
	}

	@ParameterizedTest
	@EnumSource(MailTrackingModels.Source.class)
	void recipientMaskOverflowIsRejectedBeforePersistenceOrSmtp(MailTrackingModels.Source source) {
		String jamo = "\u1100\u1161\u11A8";
		String domain = String.join(".", jamo.repeat(20), jamo.repeat(20), jamo.repeat(20),
				jamo.repeat(20), jamo.repeat(20), jamo.repeat(4) + "a");
		String recipient = "a@" + domain;
		assertThat(recipient).hasSize(320);
		WebTestClient.RequestHeadersSpec<?> request;
		if (source == MailTrackingModels.Source.SMTP_DIAGNOSTIC) {
			request = manager.post().uri("/api/v1/smtp-accounts/{id}/test-email", accountId)
					.bodyValue(Map.of("recipient", recipient, "subject", "QA", "body", "body"));
		}
		else {
			var command = new TemplateService.TemplateCommand("Recipient boundary", "QA", "DRAFT",
					new TemplateModels.TemplateDraft("QA", "Research Team", "reply@example.invalid",
							"<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "Unsubscribe {{unsubscribe_url}}", false));
			var template = templates.create(ACTOR, command, CONTEXT).block();
			request = manager.post().uri("/api/v1/templates/{id}/test-send", template.id())
					.bodyValue(Map.of("smtpAccountId", accountId, "recipient", recipient,
							"variables", Map.of("unsubscribe_url", "https://example.invalid/unsubscribe")));
		}
		request.exchange().expectStatus().isBadRequest().expectBody()
				.jsonPath("$.type").isEqualTo("invalid_mail_tracking")
				.jsonPath("$.detail").value(detail -> assertThat(detail.toString()).doesNotContain(recipient, domain));
		assertThat(outbound).isEmpty();
		assertThat(count("mail_send_records")).isZero();
		assertThat(count("mail_open_events")).isZero();
	}

	@Test
	void recipientMaskAtStorageLimitPreservesTheActualRecipient() throws Exception {
		String jamo = "\u1100\u1161\u11A8";
		String domain = String.join(".", jamo.repeat(20), jamo.repeat(20), jamo.repeat(20),
				jamo.repeat(20), jamo.repeat(20), jamo.repeat(3) + "a");
		String recipient = "a@" + domain;
		assertThat(recipient).hasSize(317);
		JsonNode result = diagnostic(Map.of("recipient", recipient, "subject", "QA", "body", "body"));
		assertThat(outbound.getFirst().recipient()).isEqualTo(recipient);
		assertThat(detail(result.path("correlationId").asText()).at("/record/recipientMasked").asText())
				.isEqualTo("a***@" + domain).hasSize(320).doesNotContain(recipient);
	}

	@Test
	void anonymousInvalidTokenReturnsTheSamePrivateGifResponse() {
		anonymous.get().uri("/t/o/invalid-token").exchange().expectStatus().isOk()
				.expectHeader().contentType("image/gif")
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*no-cache.*")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer")
				.expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
				.expectBody(byte[].class).value(bytes -> assertThat(bytes).startsWith((byte) 'G', (byte) 'I', (byte) 'F'));
	}

	@Test
	void permissionsProtectRecordsWhileEitherReadPermissionCanInspectConfiguration() throws Exception {
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		for (String path : List.of("/api/v1/mail-tracking/status", "/api/v1/mail-send-records",
				"/api/v1/mail-send-records/" + id)) {
			anonymous.get().uri(path).exchange().expectStatus().isUnauthorized();
			withPermissions("paper:read").get().uri(path).exchange().expectStatus().isForbidden();
		}
		for (String permission : List.of("smtp:read", "template:read")) {
			withPermissions(permission).get().uri("/api/v1/mail-tracking/status").exchange().expectStatus().isOk()
					.expectBody().jsonPath("$.enabled").isEqualTo(true)
					.jsonPath("$.callbackBaseUrl").isEqualTo("http://localhost:8080")
					.jsonPath("$.callbackScope").isEqualTo("LOCAL_ONLY")
					.jsonPath("$.tokenTtlSeconds").isEqualTo(2_592_000)
					.jsonPath("$.signingKeyBase64").doesNotExist();
		}
		withPermissions("template:read").get().uri("/api/v1/mail-send-records").exchange().expectStatus().isForbidden();
		withPermissions("template:read").get().uri("/api/v1/mail-send-records/{id}", id).exchange().expectStatus().isForbidden();
		withPermissions("smtp:read").get().uri("/api/v1/mail-send-records").exchange().expectStatus().isOk();
		withPermissions("smtp:read").get().uri("/api/v1/mail-send-records/{id}", id).exchange().expectStatus().isOk();
	}

	@Test
	void invalidPaginationAndIdsUseTheExistingApiErrorContract() {
		for (String query : List.of("page=0", "page=-1", "page=100001", "pageSize=0", "pageSize=101", "page=no")) {
			manager.get().uri("/api/v1/mail-send-records?" + query).exchange().expectStatus().isBadRequest()
					.expectBody().jsonPath("$.traceId").exists().jsonPath("$.status").isEqualTo(400);
		}
		for (String id : List.of("not-a-uuid", "1-1-1-1-1")) {
			manager.get().uri("/api/v1/mail-send-records/{id}", id).exchange().expectStatus().isBadRequest();
		}
		manager.get().uri("/api/v1/mail-send-records/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound()
				.expectBody().jsonPath("$.type").isEqualTo("mail_send_record_not_found");
	}

	@Test
	void tokensAreUniqueAndOnlyTheirDigestsAndMaskedRecipientAreStored() throws Exception {
		String first = diagnostic(trackedPayload()).path("correlationId").asText();
		String second = diagnostic(trackedPayload()).path("correlationId").asText();
		String firstToken = tokenFrom(outbound.get(0));
		String secondToken = tokenFrom(outbound.get(1));
		assertThat(first).isNotEqualTo(second);
		assertThat(firstToken).isNotEqualTo(secondToken).doesNotContain("qa", "example.invalid");
		byte[] stored = database.sql("SELECT token_hash FROM mail_send_records WHERE id = :id")
				.bind("id", UUID.fromString(first)).map((row, metadata) -> row.get("token_hash", byte[].class)).one().block();
		assertThat(stored).containsExactly(MessageDigest.getInstance("SHA-256").digest(firstToken.getBytes(StandardCharsets.UTF_8)));
		String rowJson = database.sql("SELECT to_jsonb(r)::text AS value FROM mail_send_records r WHERE id = :id")
				.bind("id", UUID.fromString(first)).map((row, metadata) -> row.get("value", String.class)).one().block();
		assertThat(rowJson).doesNotContain(firstToken, "qa@example.invalid", "<p>");
		JsonNode response = detail(first);
		assertThat(response.toString()).doesNotContain(firstToken, "token_hash", "tokenHash", "/t/o/", "qa@example.invalid");
		assertThat(response.at("/record/rawClickCount").asLong()).isZero();
		assertThat(response.at("/record/automatedClickCount").asLong()).isZero();
		assertThat(response.at("/record/firstClickAt").isNull()).isTrue();
		assertThat(response.at("/record/lastClickAt").isNull()).isTrue();
		assertThat(response.path("links")).isEmpty();
		assertThat(response.path("clickEvents")).isEmpty();
		manager.get().uri("/api/v1/mail-send-records").exchange().expectStatus().isOk().expectBody(String.class)
				.value(body -> assertThat(body).doesNotContain(firstToken, secondToken, "tokenHash", "/t/o/", "qa@example.invalid"));
	}

	@Test
	void callbackOriginDoesNotComeFromHostOrForwardingHeaders() {
		manager.post().uri("/api/v1/smtp-accounts/{id}/test-email", accountId).bodyValue(trackedPayload())
				.header("Host", "attacker.example.invalid").header("X-Forwarded-Host", "forwarded.example.invalid")
				.header("X-Forwarded-Proto", "https").exchange().expectStatus().isOk();
		assertThat(pixelPath(outbound.getFirst().html())).startsWith("/t/o/");
		assertThat(outbound.getFirst().html()).doesNotContain("attacker.example.invalid", "forwarded.example.invalid");
	}

	@Test
	void concurrentCallbacksDeduplicateAtomicallyAndTheNextMinuteAddsOneObservation() throws Exception {
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		String token = tokenFrom(outbound.getFirst());
		Instant firstMinute = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(10);
		MutableClock clock = new MutableClock(firstMinute);
		MailTrackingService collector = trackingAt(clock, true);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.USER_AGENT, "Unknown mail client");
		reactor.core.publisher.Flux.range(0, 24).flatMap(ignored -> collector.observe(token, headers), 24).blockLast();
		assertThat(detail(id).at("/record/rawOpenCount").asLong()).isEqualTo(1);
		assertThat(count("mail_open_events")).isEqualTo(1);
		clock.set(firstMinute.plusSeconds(60));
		collector.observe(token, headers).block();
		JsonNode result = detail(id);
		assertThat(result.at("/record/rawOpenCount").asLong()).isEqualTo(2);
		assertThat(Instant.parse(result.at("/record/firstOpenAt").asText())).isEqualTo(firstMinute);
		assertThat(Instant.parse(result.at("/record/lastOpenAt").asText())).isEqualTo(firstMinute.plusSeconds(60));
		assertThat(result.at("/record/automatedOpenCount").asLong()).isZero();
	}

	@Test
	void clickLinksAndEventsPersistWithAtomicMinuteDeduplication() throws Exception {
		String recordId = diagnostic(trackedPayload()).path("correlationId").asText();
		UUID linkId = UUID.randomUUID();
		Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		Instant expiresAt = createdAt.plus(Duration.ofDays(30));
		trackingRepository.insertLinks(UUID.fromString(recordId), List.of(new MailTrackingModels.PendingClickLink(
				linkId, "https://example.invalid/paper?id=42", "Paper details", 1,
				MailTrackingSigner.digest("test-click-token"), expiresAt)), createdAt).block();

		MutableClock clock = new MutableClock(createdAt.plusSeconds(10));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.USER_AGENT, "Unknown click client");
		MailOpenClassifier.Observation observation = new MailOpenClassifier().classify(headers);
		reactor.core.publisher.Flux.range(0, 24)
				.flatMap(ignored -> trackingRepository.observeClick(linkId, observation, clock.instant()), 24).blockLast();
		clock.set(clock.instant().plusSeconds(60));
		trackingRepository.observeClick(linkId, observation, clock.instant()).block();

		JsonNode result = detail(recordId);
		assertThat(result.at("/record/rawClickCount").asLong()).isEqualTo(2);
		assertThat(result.at("/record/automatedClickCount").asLong()).isZero();
		assertThat(result.path("links")).hasSize(1);
		assertThat(result.at("/links/0/id").asText()).isEqualTo(linkId.toString());
		assertThat(result.at("/links/0/targetUrl").asText()).isEqualTo("https://example.invalid/paper?id=42");
		assertThat(result.at("/links/0/rawClickCount").asLong()).isEqualTo(2);
		assertThat(result.path("clickEvents")).hasSize(2);
		assertThat(result.path("clickEvents").findValuesAsText("classification"))
				.containsOnly("UNCLASSIFIED");
		assertThat(count("mail_click_events")).isEqualTo(2);
	}

	@Test
	void prefetchProxyBotAndUnknownRequestsKeepDistinctClassificationsWithoutRawNetworkIdentity() throws Exception {
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		String pixel = pixelPath(outbound.getFirst().html());
		anonymous.get().uri(pixel).header("User-Agent", "Unrecognized mail client").exchange().expectStatus().isOk();
		anonymous.get().uri(pixel).header("User-Agent", "Unrecognized mail client").header("Sec-Purpose", "prefetch;anonymous-client-ip")
				.exchange().expectStatus().isOk();
		anonymous.get().uri(pixel).header("User-Agent", "GoogleImageProxy").exchange().expectStatus().isOk();
		anonymous.get().uri(pixel).header("User-Agent", "Proofpoint Scanner").exchange().expectStatus().isOk();
		JsonNode result = detail(id);
		assertThat(result.at("/record/rawOpenCount").asLong()).isEqualTo(4);
		assertThat(result.at("/record/automatedOpenCount").asLong()).isEqualTo(3);
		assertThat(result.path("events").findValuesAsText("classification"))
				.containsExactlyInAnyOrder("UNCLASSIFIED", "PREFETCH", "IMAGE_PROXY", "BOT");
		String rows = database.sql("SELECT jsonb_agg(to_jsonb(e))::text AS value FROM mail_open_events e")
				.map((row, metadata) -> row.get("value", String.class)).one().block();
		assertThat(rows).doesNotContain("Unrecognized mail client", "GoogleImageProxy", "Proofpoint Scanner", "127.0.0.1");
		assertThat(result.toString()).doesNotContain("fingerprintHash", "fingerprint_hash", "userAgent", "ipAddress");
	}

	@Test
	void headAndOtherMethodsNeverCountAndBearerHeadersCannotInterfereWithPublicPixels() throws Exception {
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		String pixel = pixelPath(outbound.getFirst().html());
		anonymous.head().uri(pixel).exchange().expectStatus().isOk().expectHeader().contentType("image/gif")
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*").expectBody().isEmpty();
		anonymous.post().uri(pixel).exchange().expectStatus().isEqualTo(405);
		assertThat(detail(id).at("/record/rawOpenCount").asLong()).isZero();
		anonymous.get().uri(pixel).headers(headers -> headers.setBearerAuth("malformed-authentication"))
				.exchange().expectStatus().isOk().expectHeader().contentType("image/gif");
		assertThat(detail(id).at("/record/rawOpenCount").asLong()).isEqualTo(1);
	}

	@Test
	void malformedAlteredExpiredUnknownAndDisabledTokensAllReturnAnIdenticalGifWithoutCounting() throws Exception {
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		String token = tokenFrom(outbound.getFirst());
		MailTrackingSigner signer = new MailTrackingSigner(TRACKING_KEY);
		Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		UUID expiredId = UUID.randomUUID();
		String expired = signer.issue(expiredId, now.minusSeconds(60));
		trackingRepository.insert(expiredId, ACTOR, accountId, MailTrackingModels.Source.SMTP_DIAGNOSTIC,
				"q***@example.invalid", "Expired", now.minusSeconds(120), now.minusSeconds(60), MailTrackingSigner.digest(expired)).block();
		byte[] expected = gif("/t/o/invalid-token");
		for (String invalid : List.of("bad", token.replace("v1.", "v2."), token.substring(0, 50) + "!" + token.substring(51),
				expired, signer.issue(UUID.randomUUID(), now.plusSeconds(3600)))) {
			assertThat(gif("/t/o/" + invalid)).containsExactly(expected);
		}
		assertThat(gif("/t/o/")).containsExactly(expected);
		assertThat(gif("/t/o/invalid/extra")).containsExactly(expected);
		WebTestClient disabled = WebTestClient.bindToController(new MailOpenController(trackingAt(Clock.systemUTC(), false))).build();
		disabled.get().uri("/t/o/{token}", token).exchange().expectStatus().isOk().expectBody(byte[].class)
				.value(value -> assertThat(value).containsExactly(expected));
		assertThat(detail(id).at("/record/rawOpenCount").asLong()).isZero();
		assertThat(count("mail_open_events")).isZero();
		assertThat(gif("/t/o/" + token)).containsExactly(expected);
		assertThat(detail(id).at("/record/rawOpenCount").asLong()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(SmtpTransportException.FailureCategory.class)
	void recordsDefiniteAndUncertainTransportOutcomesWithoutRetryingOrPromotingFromACallback(
			SmtpTransportException.FailureCategory category
	) throws Exception {
		doAnswer(invocation -> {
			outbound.add(invocation.getArgument(1));
			throw new SmtpTransportException(category);
		}).when(transport).send(any(), any());
		manager.post().uri("/api/v1/smtp-accounts/{id}/test-email", accountId).bodyValue(trackedPayload())
				.exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.type").isEqualTo("smtp_test_failed");
		assertThat(outbound).hasSize(1);
		String id = outbound.getFirst().correlationId();
		boolean uncertain = category == SmtpTransportException.FailureCategory.CONNECTION_TIMEOUT
				|| category == SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE;
		JsonNode record = detail(id).path("record");
		assertThat(record.path("status").asText()).isEqualTo(uncertain ? "UNKNOWN" : "FAILED");
		assertThat(record.path("failureCategory").asText()).isEqualTo(category.name());
		assertThat(record.path("completedAt").isTextual()).isTrue();
		gif(pixelPath(outbound.getFirst().html()));
		JsonNode after = detail(id).path("record");
		assertThat(after.path("status").asText()).isEqualTo(record.path("status").asText());
		assertThat(after.path("rawOpenCount").asLong()).isEqualTo(uncertain ? 1 : 0);
	}

	@Test
	void recordsPendingBeforeTheTransportStartsAndDoesNotTreatArrivalAsSmtpAcceptance() throws Exception {
		List<String> statuses = new CopyOnWriteArrayList<>();
		doAnswer(invocation -> {
			SmtpTransport.OutboundMessage message = invocation.getArgument(1);
			outbound.add(message);
			UUID id = UUID.fromString(message.correlationId());
			statuses.add(trackingRepository.find(id).block().status().name());
			tracking.observe(tokenFrom(message), new HttpHeaders()).block();
			statuses.add(trackingRepository.find(id).block().status().name());
			return null;
		}).when(transport).send(any(), any());
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		assertThat(statuses).containsExactly("SENDING", "SENDING");
		assertThat(detail(id).at("/record/status").asText()).isEqualTo("SMTP_ACCEPTED");
		assertThat(detail(id).at("/record/rawOpenCount").asLong()).isEqualTo(1);
	}

	@Test
	void recordInsertionFailurePreventsAnySmtpAttempt() {
		withRejectedWrites("mail_send_records", "INSERT", () -> {
			manager.post().uri("/api/v1/smtp-accounts/{id}/test-email", accountId).bodyValue(trackedPayload())
					.exchange().expectStatus().is5xxServerError();
			assertThat(outbound).isEmpty();
			assertThat(count("mail_send_records")).isZero();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"mail_send_records", "smtp_accounts", "audit_logs"})
	void persistenceOrAuditFailuresAfterAcceptanceCannotDowngradeOrRepeatTheSend(String table) throws Exception {
		withRejectedWrites(table, table.equals("audit_logs") ? "INSERT" : "UPDATE", () -> {
			JsonNode result = diagnostic(trackedPayload());
			assertThat(result.path("status").asText()).isEqualTo("SMTP_ACCEPTED");
			assertThat(outbound).hasSize(1);
			String expected = table.equals("mail_send_records") ? "SENDING" : "SMTP_ACCEPTED";
			assertThat(detail(result.path("correlationId").asText()).at("/record/status").asText()).isEqualTo(expected);
		});
	}

	@Test
	void callbackStorageFailureStillReturnsTheFixedGif() throws Exception {
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		byte[] expected = gif("/t/o/invalid-token");
		withRejectedWrites("mail_open_events", "INSERT", () ->
				assertThat(gif(pixelPath(outbound.getFirst().html()))).containsExactly(expected));
		assertThat(detail(id).at("/record/rawOpenCount").asLong()).isZero();
	}

	@Test
	void disabledTrackingRejectsExplicitOptInBeforeSmtpButAllowsUntrackedSends() {
		MailTrackingService disabledTracking = trackingAt(Clock.systemUTC(), false);
		SmtpService disabledSmtp = new SmtpService(applicationContext.getBean(SmtpRepository.class),
				applicationContext.getBean(SmtpSecretCrypto.class), applicationContext.getBean(SmtpPolicy.class),
				applicationContext.getBean(AuditService.class), applicationContext.getBean(SensitiveValueHasher.class),
				applicationContext.getBean(TransactionalOperator.class), transport, disabledTracking);
		assertThatThrownBy(() -> disabledSmtp.sendDiagnostic(ACTOR, accountId, "qa@example.invalid", "QA", "body", true, CONTEXT).block())
				.isInstanceOf(MailTrackingValidationException.class);
		assertThat(outbound).isEmpty();
		assertThat(count("mail_send_records")).isZero();
		var result = disabledSmtp.sendDiagnostic(ACTOR, accountId, "qa@example.invalid", "QA", "body", false, CONTEXT).block();
		assertThat(result.status()).isEqualTo("SMTP_ACCEPTED");
		assertThat(outbound.getFirst().html()).doesNotContain("/t/o/");
		assertThat(disabledTracking.status().enabled()).isFalse();
		assertThat(count("mail_send_records")).isEqualTo(1);
	}

	@Test
	void paginationAndLatestFiftyEventsAreStableAndBounded() throws Exception {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) ids.add(diagnostic(trackedPayload()).path("correlationId").asText());
		Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		database.sql("UPDATE mail_send_records SET created_at = :created").bind("created", now).fetch().rowsUpdated().block();
		ids.sort(Comparator.reverseOrder());
		manager.get().uri("/api/v1/mail-send-records?page=2&pageSize=2").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.page").isEqualTo(2).jsonPath("$.pageSize").isEqualTo(2)
				.jsonPath("$.total").isEqualTo(3).jsonPath("$.totalPages").isEqualTo(2)
				.jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].id").isEqualTo(ids.getLast());
		MutableClock clock = new MutableClock(now);
		MailTrackingService collector = trackingAt(clock, true);
		String token = tokenFrom(outbound.getFirst());
		for (int minute = 0; minute < 60; minute++) {
			clock.set(now.plusSeconds(minute * 60L));
			collector.observe(token, new HttpHeaders()).block();
		}
		JsonNode detail = detail(outbound.getFirst().correlationId());
		assertThat(detail.at("/record/rawOpenCount").asInt()).isEqualTo(60);
		assertThat(detail.path("events").size()).isEqualTo(50);
		List<Instant> times = detail.path("events").findValuesAsText("occurredAt").stream().map(Instant::parse).toList();
		assertThat(times).isSortedAccordingTo(Comparator.reverseOrder());
		assertThat(times.getFirst()).isEqualTo(now.plusSeconds(59 * 60));
		assertThat(times.getLast()).isEqualTo(now.plusSeconds(10 * 60));
	}

	@Test
	void deletingAnAccountPreservesTheRecordWithANullAccountName() throws Exception {
		String id = diagnostic(trackedPayload()).path("correlationId").asText();
		smtp.delete(ACTOR, accountId, 0, CONTEXT).block();
		assertThat(detail(id).at("/record/smtpAccountName").isNull()).isTrue();
		assertThat(detail(id).at("/record/status").asText()).isEqualTo("SMTP_ACCEPTED");
	}

	@Test
	void retentionCommandDeletesOnlyOlderSendRecordsAndTheirEventsWithAnExplicitPastCutoff() throws Exception {
		var resource = getClass().getResource("/db/maintenance/delete_mail_tracking_before.sql");
		assertThat(resource).as("executable retention maintenance command").isNotNull();
		POSTGRES.copyFileToContainer(org.testcontainers.utility.MountableFile.forClasspathResource(
				"db/maintenance/delete_mail_tracking_before.sql"), "/tmp/mail-tracking-retention.sql");
		String oldId = diagnostic(trackedPayload()).path("correlationId").asText();
		gif(pixelPath(outbound.getFirst().html()));
		String retainedId = diagnostic(trackedPayload()).path("correlationId").asText();
		Instant cutoff = Instant.now().minus(Duration.ofDays(90)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		database.sql("UPDATE mail_send_records SET created_at = :created WHERE id = :id")
				.bind("created", cutoff.minusSeconds(1)).bind("id", UUID.fromString(oldId)).fetch().rowsUpdated().block();
		database.sql("UPDATE mail_send_records SET created_at = :created WHERE id = :id")
				.bind("created", cutoff).bind("id", UUID.fromString(retainedId)).fetch().rowsUpdated().block();
		long auditCount = count("audit_logs");
		var result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
				"-v", "cutoff=" + cutoff, "-f", "/tmp/mail-tracking-retention.sql");
		assertThat(result.getExitCode()).as(result.getStderr()).isZero();
		assertThat(trackingRepository.find(UUID.fromString(oldId)).block()).isNull();
		assertThat(trackingRepository.find(UUID.fromString(retainedId)).block()).isNotNull();
		assertThat(count("mail_open_events")).isZero();
		assertThat(count("smtp_accounts")).isEqualTo(1);
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("audit_logs")).isEqualTo(auditCount);
		for (String invalidCutoff : List.of("not-a-date", Instant.now().plusSeconds(60).toString())) {
			var rejected = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
					"-v", "cutoff=" + invalidCutoff, "-f", "/tmp/mail-tracking-retention.sql");
			assertThat(rejected.getExitCode()).isNotZero();
			assertThat(count("mail_send_records")).isEqualTo(1);
		}
		var missingCutoff = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
				"-f", "/tmp/mail-tracking-retention.sql");
		assertThat(missingCutoff.getExitCode()).isNotZero();
		assertThat(count("mail_send_records")).isEqualTo(1);
	}

	private WebTestClient withPermissions(String... permissions) {
		return anonymous.mutateWith(mockUser(ACTOR.toString()).authorities(
				java.util.Arrays.stream(permissions).map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
						.toArray(org.springframework.security.core.GrantedAuthority[]::new)));
	}

	private JsonNode diagnostic(Map<String, Object> payload) throws Exception {
		String body = manager.post().uri("/api/v1/smtp-accounts/{id}/test-email", accountId).bodyValue(payload)
				.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		return objectMapper.readTree(body);
	}

	private Map<String, Object> trackedPayload() {
		return Map.of("recipient", "qa@example.invalid", "subject", "QA", "body", "body", "trackOpens", true);
	}

	private MailTrackingService trackingAt(Clock clock, boolean enabled) {
		return new MailTrackingService(trackingRepository,
				new MailTrackingProperties(enabled, "http://localhost:8080", enabled ? TRACKING_KEY : "", Duration.ofDays(30)),
				enabled ? new MailTrackingSigner(TRACKING_KEY) : null, new MailOpenClassifier(), clock);
	}

	private byte[] gif(String path) {
		return anonymous.get().uri(path).exchange().expectStatus().isOk().expectHeader().contentType("image/gif")
				.expectBody(byte[].class).returnResult().getResponseBody();
	}

	private String tokenFrom(SmtpTransport.OutboundMessage message) {
		return pixelPath(message.html()).substring("/t/o/".length());
	}

	private void withRejectedWrites(String table, String operation, CheckedAction action) {
		database.sql("CREATE FUNCTION reject_tracking_test_write() RETURNS trigger LANGUAGE plpgsql AS $$ "
				+ "BEGIN RAISE EXCEPTION 'test write unavailable'; END $$").fetch().rowsUpdated().block();
		try {
			database.sql("CREATE TRIGGER reject_tracking_test_write BEFORE " + operation + " ON " + table
					+ " FOR EACH ROW EXECUTE FUNCTION reject_tracking_test_write()").fetch().rowsUpdated().block();
			try {
				action.run();
			}
			catch (Exception error) {
				throw new AssertionError(error);
			}
			finally {
				database.sql("DROP TRIGGER reject_tracking_test_write ON " + table).fetch().rowsUpdated().block();
			}
		}
		finally {
			database.sql("DROP FUNCTION reject_tracking_test_write()").fetch().rowsUpdated().block();
		}
	}

	@FunctionalInterface
	private interface CheckedAction { void run() throws Exception; }

	private static final class MutableClock extends Clock {
		private Instant now;
		MutableClock(Instant now) { this.now = now; }
		void set(Instant now) { this.now = now; }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}

	private JsonNode detail(String id) throws Exception {
		String body = manager.get().uri("/api/v1/mail-send-records/{id}", id).exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();
		return objectMapper.readTree(body);
	}

	private String pixelPath(String html) {
		var image = org.jsoup.Jsoup.parseBodyFragment(html).selectFirst("img[src*='/t/o/']");
		assertThat(image).as("transmitted open pixel").isNotNull();
		URI uri = URI.create(image.attr("src"));
		assertThat(uri.getScheme()).isEqualTo("http");
		assertThat(uri.getAuthority()).isEqualTo("localhost:8080");
		return uri.getPath();
	}

	private String trackedClickPath(String correlationId, String target) {
		tracking.send(ACTOR, accountId, MailTrackingModels.Source.TEMPLATE_TEST,
				new SmtpTransport.OutboundMessage("qa@example.invalid", "Tracked click", "Research Team",
						"reply@example.invalid", "<a href=\"" + target + "\">Paper details</a>",
						"Paper details " + target, correlationId), true, outbound::add).block();
		var anchor = org.jsoup.Jsoup.parseBodyFragment(outbound.getLast().html()).selectFirst("a[href*='/t/c/']");
		assertThat(anchor).as("transmitted tracked link").isNotNull();
		URI uri = URI.create(anchor.attr("href"));
		assertThat(uri.getScheme()).isEqualTo("http");
		assertThat(uri.getAuthority()).isEqualTo("localhost:8080");
		return uri.getPath();
	}

	private long count(String table) {
		return database.sql("SELECT count(*) AS total FROM " + table)
				.map((row, metadata) -> row.get("total", Long.class)).one().block();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(excludeName = {
			"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
			"org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
			"org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
	})
	@Import({EmailConfiguration.class, IdentityConfiguration.class, SecurityConfiguration.class,
			SecurityErrorResponseWriter.class, GlobalExceptionHandler.class, TraceIdWebFilter.class})
	@ComponentScan(basePackageClasses = {SmtpController.class, TemplateController.class},
			basePackages = "com.camel_hub.advertisement.email.tracking")
	static class TestApplication { }
}
