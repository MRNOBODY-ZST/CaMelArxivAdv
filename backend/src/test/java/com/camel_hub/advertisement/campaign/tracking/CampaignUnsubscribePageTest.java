package com.camel_hub.advertisement.campaign.tracking;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignUnsubscribePageTest {

	@ParameterizedTest
	@EnumSource(CampaignUnsubscribePage.State.class)
	void everyStateHasAccessibleStandaloneResponsiveMarkup(CampaignUnsubscribePage.State state) {
		String html = new String(CampaignUnsubscribePage.render(state), StandardCharsets.UTF_8);
		Document page = Jsoup.parse(html);

		assertThat(page.selectFirst("html").attr("lang")).isEqualTo("en");
		assertThat(page.title()).isEqualTo("Mail preferences | Camel Hub");
		assertThat(page.selectFirst("meta[name=viewport]").attr("content"))
				.isEqualTo("width=device-width, initial-scale=1");
		assertThat(page.selectFirst("meta[name=robots]").attr("content")).isEqualTo("noindex, nofollow");
		assertThat(page.selectFirst("meta[name=referrer]").attr("content")).isEqualTo("no-referrer");
		assertThat(page.select("main")).hasSize(1);
		assertThat(page.select("h1")).hasSize(1);
		assertThat(page.selectFirst("section").attr("aria-labelledby"))
				.isEqualTo(page.selectFirst("h1").id());
		assertThat(page.select("svg")).allSatisfy(icon ->
				assertThat(icon.attr("focusable")).isEqualTo("false"));
		assertThat(page.select("a[href]")).isNotEmpty().allSatisfy(link -> {
			assertThat(link.attr("href")).isEqualTo("https://api.camel-hub.com/");
			assertThat(link.attr("rel")).isEqualTo("noreferrer");
		});
		assertThat(page.select("script, link, img, iframe, object, embed, base, [style], [onclick], [onload]"))
				.isEmpty();
		assertThat(html).doesNotContain("{{", "/u/", "http://", "unsafe-inline", "javascript:");
		String css = page.selectFirst("style").data();
		assertThat(css).contains("max-width: 680px", "min-height: 44px", "min-height: 52px",
				"@media (max-width: 560px)", "@media (prefers-color-scheme: dark)",
				"@media (forced-colors: active)", ":focus-visible", "max-width: 100%");
	}

	@ParameterizedTest
	@EnumSource(CampaignUnsubscribePage.State.class)
	void exactEmittedStylesAreAuthorizedWithoutWeakeningOtherCspDirectives(CampaignUnsubscribePage.State state)
			throws Exception {
		Document page = Jsoup.parse(new String(CampaignUnsubscribePage.render(state), StandardCharsets.UTF_8));
		assertThat(page.select("style")).hasSize(1);
		String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
				.digest(page.selectFirst("style").data().getBytes(StandardCharsets.UTF_8)));
		assertThat(CampaignUnsubscribePage.contentSecurityPolicy()).isEqualTo(
				"default-src 'none'; style-src 'sha256-" + hash
						+ "'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
	}

	@ParameterizedTest
	@EnumSource(value = CampaignUnsubscribePage.State.class, names = {"CONFIRMATION", "RETRY"})
	void confirmationAndRetryHaveWorkingSamePagePostActions(CampaignUnsubscribePage.State state) {
		Document page = Jsoup.parse(new String(CampaignUnsubscribePage.render(state), StandardCharsets.UTF_8));
		assertThat(page.select("form")).hasSize(1);
		assertThat(page.selectFirst("form").attr("method")).isEqualTo("post");
		assertThat(page.selectFirst("form").hasAttr("action")).isTrue();
		assertThat(page.selectFirst("form").attr("action")).isEmpty();
		assertThat(page.select("form button[type=submit]")).hasSize(1);
		assertThat(page.selectFirst("form button").text()).isEqualTo(state == CampaignUnsubscribePage.State.CONFIRMATION
				? "Unsubscribe from research emails" : "Try again");
		assertThat(page.select("input, select, textarea, [formaction]")).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(CampaignUnsubscribePage.State.class)
	void copyIsHonestAboutConfirmationAndDoesNotClaimATokenWasValidated(CampaignUnsubscribePage.State state) {
		Document page = Jsoup.parse(new String(CampaignUnsubscribePage.render(state), StandardCharsets.UTF_8));
		switch (state) {
			case CONFIRMATION -> assertThat(page.text()).contains("Nothing changes until you confirm.",
					"Opening this page does not unsubscribe you.", "No sign-in is needed.");
			case COMPLETE -> {
				assertThat(page.text()).contains("Request received.", "Your request has been received.",
						"We do not display or confirm email addresses")
						.doesNotContain("You are unsubscribed", "Your preference has been recorded");
				assertThat(page.select("form")).isEmpty();
			}
			case RETRY -> assertThat(page.text()).contains("has not been confirmed", "Please try again.");
		}
	}
}
