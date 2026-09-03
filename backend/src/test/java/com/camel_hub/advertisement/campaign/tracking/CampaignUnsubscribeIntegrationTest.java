package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CampaignUnsubscribeIntegrationTest extends CampaignTrackingDatabaseTestSupport {

	private static final AuthenticationRequestContext REQUEST =
			new AuthenticationRequestContext("198.51.100.27", "Mail Client", "unsubscribe-test-1");

	@Test
	void getAndHeadOnlyConfirmWhilePostAndRfc8058AtomicallySuppress(CapturedOutput output) {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String token = tokenFor(claim.recipientId());
		WebTestClient client = WebTestClient.bindToController(new CampaignUnsubscribeController(service)).build();

		String validConfirmation = client.get().uri("/u/{token}", token).exchange().expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer")
				.expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
				.expectHeader().exists("Content-Security-Policy")
				.expectBody(String.class).returnResult().getResponseBody();
		String invalidConfirmation = client.get().uri("/u/not-a-token").exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();
		client.head().uri("/u/{token}", token).exchange().expectStatus().isOk().expectBody().isEmpty();
		assertThat(validConfirmation).isEqualTo(invalidConfirmation).doesNotContain(token, "/u/");
		assertThat(count("unsubscribe_records")).isZero();
		assertThat(count("suppression_entries")).isZero();

		String result = client.post().uri("/u/{token}", token)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.bodyValue("List-Unsubscribe=One-Click")
				.exchange().expectStatus().isOk()
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer")
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(result).doesNotContain(token, "author@example.org", "/u/");
		assertThat(count("unsubscribe_records")).isEqualTo(1);
		assertThat(count("suppression_entries")).isEqualTo(1);
		assertThat(text("SELECT reason FROM suppression_entries")).isEqualTo("UNSUBSCRIBED");
		assertThat(text("SELECT source FROM suppression_entries")).isEqualTo("PUBLIC_UNSUBSCRIBE");
		assertThat(count("audit_logs")).isEqualTo(1);
		assertThat(status(claim.recipientId())).isEqualTo("SMTP_ACCEPTED");
		assertThat(text("SELECT resource_type FROM audit_logs")).isEqualTo("CAMPAIGN_UNSUBSCRIBE");
		assertThat(text("SELECT after_summary->>'unsubscribeRecorded' FROM audit_logs")).isEqualTo("true");
		assertThat(text("SELECT after_summary->>'globalSuppressionActive' FROM audit_logs")).isEqualTo("true");
		assertThat(text("SELECT after_summary->>'globalSuppressionChanged' FROM audit_logs")).isEqualTo("true");
		assertThat(text("SELECT after_summary->>'unsentRecipientsSuppressed' FROM audit_logs")).isEqualTo("0");
		assertThat(text("SELECT after_summary->>'status' FROM audit_logs")).isNull();
		assertThat(text("SELECT to_jsonb(a)::text FROM audit_logs a")).doesNotContain(token, "author@example.org");
		assertThat(output.getAll()).doesNotContain(token, "author@example.org");
	}

	@Test
	void invalidExpiredWrongNamespaceAndRepeatedPostsReturnTheSameGenericResult() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String valid = tokenFor(claim.recipientId());
		CampaignTrackingSigner signer = new CampaignTrackingSigner(TRACKING_KEY);
		List<String> invalid = List.of(
				"invalid", signer.issueOpen(claim.recipientId(), NOW.plusSeconds(60)),
				signer.issueUnsubscribe(claim.recipientId(), NOW.minusSeconds(1)),
				valid.substring(0, valid.length() - 1) + (valid.endsWith("A") ? "B" : "A"),
				"x".repeat(513));
		WebTestClient client = WebTestClient.bindToController(new CampaignUnsubscribeController(service)).build();
		String expected = client.post().uri("/u/invalid").exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();

		for (String token : invalid) {
			client.post().uri("/u/{token}", token).exchange().expectStatus().isOk()
					.expectBody(String.class).value(body -> assertThat(body).isEqualTo(expected).doesNotContain(token));
		}
		assertThat(count("unsubscribe_records")).isZero();
		client.post().uri("/u/{token}", valid).exchange().expectStatus().isOk()
				.expectBody(String.class).value(body -> assertThat(body).isEqualTo(expected));
		client.post().uri("/u/{token}", valid).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.bodyValue("List-Unsubscribe=One-Click").exchange().expectStatus().isOk()
				.expectBody(String.class).value(body -> assertThat(body).isEqualTo(expected));
		assertThat(count("unsubscribe_records")).isEqualTo(1);
		assertThat(count("suppression_entries")).isEqualTo(1);
		assertThat(count("audit_logs")).isEqualTo(1);
	}

	@Test
	void unsubscribeUpdatesOnlyUnsentProductionRowsAndNeverSafetyState() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim source = preparedClaim(service);
		String token = tokenFor(source.recipientId());
		UUID queued = insertMatchingRecipient(source, "QUEUED");
		UUID temporary = insertMatchingRecipient(source, "TEMPORARY_FAILURE");
		UUID connecting = insertMatchingRecipient(source, "CONNECTING");
		UUID accepted = insertMatchingRecipient(source, "SMTP_ACCEPTED");
		UUID permanent = insertMatchingRecipient(source, "PERMANENT_FAILURE");

		service.unsubscribe(token, REQUEST).block();

		assertThat(status(queued)).isEqualTo("UNSUBSCRIBED");
		assertThat(status(temporary)).isEqualTo("UNSUBSCRIBED");
		assertThat(status(connecting)).isEqualTo("CONNECTING");
		assertThat(status(accepted)).isEqualTo("SMTP_ACCEPTED");
		assertThat(status(permanent)).isEqualTo("PERMANENT_FAILURE");
		assertThat(status(source.recipientId())).isEqualTo("SMTP_ACCEPTED");
		assertThat(count("campaign_safety_messages")).isZero();
		assertThat(count("campaign_safety_events")).isZero();
	}

	@Test
	void concurrentUnsubscribeIsIdempotentAndReactivatesOnlyExpiredSuppression() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String token = tokenFor(claim.recipientId());
		database.sql("""
				INSERT INTO suppression_entries (
				    email_hmac, email_domain, reason, source, notes, created_by, created_at, expires_at
				) SELECT email_hmac, 'stale.example', 'MANUAL', 'ADMIN', 'expired entry', :actor, :old, :expired
				  FROM campaign_recipients WHERE id = :recipient
				""").bind("actor", ACTOR).bind("old", NOW.minusSeconds(3600)).bind("expired", NOW.minusSeconds(1))
				.bind("recipient", claim.recipientId()).fetch().rowsUpdated().block();

		Flux.range(0, 24).flatMap(ignored -> service.unsubscribe(token, REQUEST), 24).blockLast();

		assertThat(count("unsubscribe_records")).isEqualTo(1);
		assertThat(count("suppression_entries")).isEqualTo(1);
		assertThat(count("audit_logs")).isEqualTo(1);
		assertThat(text("SELECT reason FROM suppression_entries")).isEqualTo("UNSUBSCRIBED");
		assertThat(text("SELECT source FROM suppression_entries")).isEqualTo("PUBLIC_UNSUBSCRIBE");
		assertThat(text("SELECT email_domain FROM suppression_entries")).isEqualTo("example.org");
		assertThat(text("SELECT notes FROM suppression_entries")).isNull();
		assertThat(text("SELECT created_by::text FROM suppression_entries")).isNull();
		assertThat(longValue("SELECT extract(epoch FROM created_at)::bigint FROM suppression_entries"))
				.isEqualTo(NOW.getEpochSecond());
		assertThat(text("SELECT expires_at::text FROM suppression_entries")).isNull();
	}

	@Test
	void activeSeriousSuppressionIsNeverDowngradedByUnsubscribe() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String token = tokenFor(claim.recipientId());
		database.sql("""
				INSERT INTO suppression_entries (email_hmac, email_domain, reason, source, notes, expires_at)
				SELECT email_hmac, email_domain, 'PRIVACY_REQUEST', 'ADMIN', 'retain serious reason', NULL
				FROM campaign_recipients WHERE id = :recipient
				""").bind("recipient", claim.recipientId()).fetch().rowsUpdated().block();

		service.unsubscribe(token, REQUEST).block();

		assertThat(text("SELECT reason FROM suppression_entries")).isEqualTo("PRIVACY_REQUEST");
		assertThat(text("SELECT source FROM suppression_entries")).isEqualTo("ADMIN");
		assertThat(text("SELECT notes FROM suppression_entries")).isEqualTo("retain serious reason");
		assertThat(text("SELECT expires_at::text FROM suppression_entries")).isNull();
		assertThat(text("SELECT after_summary->>'unsubscribeRecorded' FROM audit_logs")).isEqualTo("true");
		assertThat(text("SELECT after_summary->>'globalSuppressionActive' FROM audit_logs")).isEqualTo("true");
		assertThat(text("SELECT after_summary->>'globalSuppressionChanged' FROM audit_logs")).isEqualTo("false");
		assertThat(text("SELECT after_summary->>'unsentRecipientsSuppressed' FROM audit_logs")).isEqualTo("0");
		assertThat(text("SELECT after_summary->>'status' FROM audit_logs")).isNull();
	}

	@Test
	void differentCampaignTokensForTheSameAddressSerializeWithoutDeadlock() throws Exception {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim first = preparedClaim(service);
		CampaignDeliveryRepository.ProductionClaim second = preparedClaim(service);
		List<String> tokens = List.of(tokenFor(first.recipientId()), tokenFor(second.recipientId()));
		CountDownLatch ready = new CountDownLatch(24);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(24);
		List<Future<Boolean>> calls = new ArrayList<>();
		try {
			for (int index = 0; index < 24; index++) {
				String token = tokens.get(index % tokens.size());
				calls.add(pool.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
					return service.unsubscribe(token, REQUEST).block();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<Boolean> call : calls) assertThat(call.get(20, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("unsubscribe_records")).isEqualTo(1);
		assertThat(count("suppression_entries")).isEqualTo(1);
		assertThat(count("audit_logs")).isEqualTo(1);
	}

	@Test
	void anExistingUnsubscribeStillAuditsARealQueuedRecipientRepair() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim source = preparedClaim(service);
		String token = tokenFor(source.recipientId());
		assertThat(service.unsubscribe(token, REQUEST).block()).isTrue();
		sql("TRUNCATE audit_logs");
		UUID queued = insertMatchingRecipient(source, "QUEUED");

		assertThat(service.unsubscribe(token, REQUEST).block()).isTrue();

		assertThat(status(queued)).isEqualTo("UNSUBSCRIBED");
		assertThat(count("audit_logs")).isEqualTo(1);
		assertThat(count("unsubscribe_records")).isEqualTo(1);
		assertThat(count("suppression_entries")).isEqualTo(1);
		assertThat(text("SELECT after_summary->>'unsubscribeRecorded' FROM audit_logs")).isEqualTo("false");
		assertThat(text("SELECT after_summary->>'globalSuppressionChanged' FROM audit_logs")).isEqualTo("false");
		assertThat(text("SELECT after_summary->>'unsentRecipientsSuppressed' FROM audit_logs")).isEqualTo("1");
		assertThat(text("SELECT after_summary->>'status' FROM audit_logs")).isNull();
	}

	@Test
	void unsubscribeWaitsForDurableSerializationBeyondTwoSecondsInsteadOfPretendingSuccess() throws Exception {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String token = tokenFor(claim.recipientId());
		long lockKey = ByteBuffer.wrap(Arrays.copyOf(claim.emailHmac(), Long.BYTES)).getLong();
		CountDownLatch locked = new CountDownLatch(1);
		Mono<Void> holder = Mono.usingWhen(connectionFactory.create(), connection ->
				Mono.from(connection.beginTransaction())
						.then(Mono.from(connection.createStatement("SELECT pg_advisory_xact_lock($1)")
								.bind(0, lockKey).execute()))
						.doOnSuccess(ignored -> locked.countDown())
						.then(Mono.delay(Duration.ofMillis(2300)))
						.then(Mono.from(connection.commitTransaction())),
				connection -> Mono.from(connection.close()),
				(connection, error) -> Mono.from(connection.rollbackTransaction()).then(Mono.from(connection.close())),
				connection -> Mono.from(connection.rollbackTransaction()).then(Mono.from(connection.close())))
				.subscribeOn(Schedulers.boundedElastic()).then();
		var subscription = holder.subscribe();
		try {
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(service.unsubscribe(token, REQUEST).block(Duration.ofSeconds(10))).isTrue();
		}
		finally {
			subscription.dispose();
		}
		assertThat(count("unsubscribe_records")).isEqualTo(1);
		assertThat(count("suppression_entries")).isEqualTo(1);
	}

	@Test
	void persistentStorageFailureReturnsARetryableGenericResponseWithoutEchoingCapability(CapturedOutput output) {
		CampaignCallbackNamespace failing = new CampaignCallbackNamespace() {
			@Override public Mono<Boolean> observeOpen(String token, HttpHeaders headers,
					AuthenticationRequestContext request) { return Mono.just(false); }
			@Override public Mono<ResolvedClick> click(String token, HttpHeaders headers,
					AuthenticationRequestContext request, boolean observe) { return Mono.empty(); }
			@Override public Mono<Boolean> unsubscribe(String token, AuthenticationRequestContext request) {
				return Mono.error(new IllegalStateException("storage unavailable"));
			}
		};
		String secretToken = "campaign-unsubscribe:v1.secret-value";

		WebTestClient.bindToController(new CampaignUnsubscribeController(failing)).build()
				.post().uri("/u/{token}", secretToken).exchange()
				.expectStatus().isEqualTo(503)
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectBody(String.class).value(body -> assertThat(body)
						.doesNotContain(secretToken, "storage unavailable"));
		assertThat(output.getAll()).contains("Campaign unsubscribe persistence failed")
				.doesNotContain(secretToken, "storage unavailable");
	}

	private String tokenFor(UUID recipientId) {
		String body = text("SELECT rendered_text FROM campaign_recipients WHERE id = '" + recipientId + "'");
		String url = body.substring(body.indexOf("https://tracking.example.test/u/")).strip();
		return URI.create(url).getPath().substring(3);
	}

	private UUID insertMatchingRecipient(CampaignDeliveryRepository.ProductionClaim source, String status) {
		UUID campaign = UUID.randomUUID();
		UUID recipient = UUID.randomUUID();
		database.sql("""
				INSERT INTO campaigns (
				    id, name, purpose, status, template_id, template_version_id, smtp_account_id,
				    from_name, from_email, reply_to, unsubscribe_enabled, created_by, updated_by
				) VALUES (:campaign, :name, 'Research outreach', 'RUNNING', :template, :version, :smtp,
				          'Research Team', 'sender@example.org', 'reply@example.org', true, :actor, :actor)
				""").bind("campaign", campaign).bind("name", "Matching " + recipient)
				.bind("template", TEMPLATE).bind("version", TEMPLATE_VERSION).bind("smtp", SMTP).bind("actor", ACTOR)
				.fetch().rowsUpdated().block();
		database.sql("""
				INSERT INTO campaign_recipients (
				    id, campaign_id, email_ciphertext, email_nonce, email_hmac, email_domain,
			    confidence, status, personalization_status, rendered_subject, rendered_html,
			    rendered_text, personalized_at, next_attempt_at, attempt_count
				) VALUES (:recipient, :campaign, decode('aa','hex'), decode('bb','hex'), :hmac, 'example.org',
			          'HIGH', :status, 'GENERATED', 'Subject', '<p>body</p>', 'body', :now, :now,
				          CASE WHEN :status = 'QUEUED' THEN 0 ELSE 1 END)
				""").bind("recipient", recipient).bind("campaign", campaign).bind("hmac", source.emailHmac())
				.bind("status", status).bind("now", NOW).fetch().rowsUpdated().block();
		return recipient;
	}

	private String status(UUID recipient) {
		return text("SELECT status FROM campaign_recipients WHERE id = '" + recipient + "'");
	}
}
