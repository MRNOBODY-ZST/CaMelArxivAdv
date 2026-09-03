package com.camel_hub.advertisement.campaign.tracking;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignLinkRewriterTest {

	private final CampaignLinkRewriter rewriter = new CampaignLinkRewriter("https://tracking.example.test");

	@Test
	void discoversOnlyAbsoluteSafeHttpTargetsAndNeverTracksCallbackUrls() {
		String unsubscribe = "https://tracking.example.test/u/campaign-unsubscribe:v1.opaque";
		String html = """
				<a href="https://papers.example.org/item?id=1">Paper</a>
				<a href="https://papers.example.org/item?id=1">Again</a>
				<a href="HTTP://papers.example.org/mixed">Mixed</a>
				<a href="/relative">Relative</a>
				<a href="https://user:secret@papers.example.org/private">User info</a>
				<a href="javascript:alert(1)">Script</a>
				<a href="data:text/html,unsafe">Data</a>
				<a href="https://papers.example.org/item#fragment">Fragment</a>
				<a href="%s">Unsubscribe</a>
				<a href="https://tracking.example.test/t/c/attacker-value">Callback</a>
				<a href="https://tracking.example.test/t/o/attacker-value">Open callback</a>
				<a href="https://tracking.example.test/u/attacker-value">Other unsubscribe</a>
				<a href="https://tracking.example.test/api/v1/template-assets/a/b/content">Asset</a>
				<a href="https:\\evil.example/path">Backslash</a>
				""".formatted(unsubscribe);

		assertThat(rewriter.eligibleLinks(html, unsubscribe))
				.extracting(CampaignLinkRewriter.EligibleLink::targetUrl)
				.containsExactly("https://papers.example.org/item?id=1");
	}

	@Test
	void rewritesRepeatedTargetsToTheServerSuppliedCallbackOnly() {
		String html = "<a href=\"https://papers.example.org/item?id=1\">Paper</a>"
				+ "<a href=\"https://papers.example.org/item?id=1\">Again</a>"
				+ "<a href=\"/relative\">Relative</a>";
		String rewritten = rewriter.rewrite(html,
				Map.of("https://papers.example.org/item?id=1", "campaign-click:v1.opaque"));
		var links = Jsoup.parseBodyFragment(rewritten).select("a[href]");

		assertThat(links.get(0).attr("href"))
				.isEqualTo("https://tracking.example.test/t/c/campaign-click:v1.opaque");
		assertThat(links.get(1).attr("href"))
				.isEqualTo("https://tracking.example.test/t/c/campaign-click:v1.opaque");
		assertThat(links.get(2).attr("href")).isEqualTo("/relative");
	}

	@Test
	void rejectsDecodedBypassesUnsafePortsAndEveryCapabilitySelfLoop() {
		String tooLong = "https://papers.example.org/" + "x".repeat(2049);
		String html = """
				<a href="https://tracking.example.test/%%75/opaque">Encoded unsubscribe</a>
				<a href="https://tracking.example.test/%%74/c/opaque">Encoded click</a>
				<a href="https://tracking.example.test/%%74/o/opaque">Encoded open</a>
				<a href="https://tracking.example.test/api/v1/template-assets/%%2e%%2e/secret">Asset</a>
				<a href="https://papers.example.org/path%%0d%%0aInjected">Encoded control</a>
				<a href="https://papers.example.org/path%%5cevil">Encoded backslash</a>
				<a href="https://user%%40example.org@papers.example.org/private">Encoded userinfo</a>
				<a href="https://papers.example.org:0/invalid">Zero port</a>
				<a href="%s">Too long</a>
				<a href="mailto:author@example.org">Mail</a>
				""".formatted(tooLong);

		assertThat(rewriter.eligibleLinks(html, null)).isEmpty();
		for (String target : new String[] {
				"https://tracking.example.test/%75/opaque",
				"https://tracking.example.test/%74/c/opaque",
				"https://tracking.example.test/safe/../t/c/opaque",
				"https://tracking.example.test/safe/%2e%2e/u/opaque",
				"https://tracking.example.test/safe/%252e%252e/t/c/opaque",
				"https://tracking.example.test./t/o/opaque",
				"https://papers.example.org/path%0d%0aInjected",
				"https://papers.example.org/path%5cevil",
				"https://user%40example.org@papers.example.org/private",
				"https://papers.example.org:0/invalid", tooLong, "mailto:author@example.org"}) {
			assertThat(rewriter.safeRedirectTarget(target)).as(target).isFalse();
		}
	}

	@Test
	void refusesInvalidOriginsAndNeverTrustsCallerSuppliedFullCallbackUrls() {
		for (String origin : new String[] {
				"https://user@tracking.example.test", "https://tracking.example.test/path",
				"https://tracking.example.test?query", "https://tracking.example.test#fragment",
				"ftp://tracking.example.test", "https://tracking.example.test:0"}) {
			assertThatThrownBy(() -> new CampaignLinkRewriter(origin))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Campaign callback origin is invalid");
		}

		assertThatThrownBy(() -> rewriter.rewrite(
				"<a href=\"https://papers.example.org/item\">Paper</a>",
				Map.of("https://papers.example.org/item", "https://attacker.example/t/c/value")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
