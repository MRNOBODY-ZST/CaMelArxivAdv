package com.camel_hub.advertisement.campaign.safety;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignSafetyContentPolicyTest {
	private static final String KEY = Base64.getEncoder().encodeToString(
			"campaign-safety-content-key-32!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	private static final Instant EXPIRY = Instant.parse("2030-05-05T10:15:30Z");
	private final CampaignSafetyContentPolicy policy = new CampaignSafetyContentPolicy();

	@Test
	void acceptsOrdinaryPaperUrlsAndOnlyStandaloneUnsubscribePlaceholders() {
		assertThatCode(() -> policy.validateSource(
				"A paper result", "<p>Read /u/profile and /t/c/docs.</p>"
						+ "<a href=\"{{unsubscribe_url}}\">Stop</a>",
				"Read https://papers.example.test/t/o/figure. Stop ({{unsubscribe_url}})."))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsNestedOrNonStandalonePlaceholderExfiltration() {
		for (String text : new String[] {
				"https://attacker.example/collect?next={{unsubscribe_url}}",
				"https://attacker.example/collect({{unsubscribe_url}})",
				"Unsubscribe:{{unsubscribe_url}}"
		}) {
			assertThatThrownBy(() -> policy.validateSource(
					"Subject", "<a href=\"{{unsubscribe_url}}\">Stop</a>", text))
					.as(text).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> policy.validateSource(
				"Subject", "<a href=\"https://attacker.example/?next={{unsubscribe_url}}\">Leak</a>",
				"Stop {{unsubscribe_url}}"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsRawPercentEncodedHtmlEntityAndDeepEncodedCapabilitiesOrAddresses() {
		CampaignSafetySigner signer = new CampaignSafetySigner(KEY);
		String token = signer.issueOpen(UUID.randomUUID(), EXPIRY);
		String percent = java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
		String compatibilityPercent = compatibilityPercentEncoded(token);
		String wrappedTestToken = "X" + new com.camel_hub.advertisement.email.tracking.MailTrackingSigner(KEY)
				.issue(UUID.randomUUID(), EXPIRY);
		String deep = percent;
		for (int round = 0; round < 6; round++) {
			deep = java.net.URLEncoder.encode(deep, java.nio.charset.StandardCharsets.UTF_8);
		}
		for (String injected : new String[] {
				token,
				percent,
				compatibilityPercent,
				wrappedTestToken,
				"campaign-safety-open&#58;v1." + token.substring(token.indexOf('.') + 1),
				deep,
				"author@example.test",
				"author%40example.test",
				"author&#64;example.test"
		}) {
			assertThatThrownBy(() -> policy.validateSource(
					"Subject", "<p>" + injected + "</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
					"Stop {{unsubscribe_url}}"))
					.as(injected).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsUnicodeCompatibilityEncodedAddressesAtTheFinalBoundary() {
		for (String injected : new String[] {
			"author\uff20example\uff0etest",
			"\uff41uthor@example.test",
			"author\u2024example@example.test"
		}) {
			assertThatThrownBy(() -> policy.validateSource(
					"Subject", "<p>" + injected + "</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
					"Stop {{unsubscribe_url}}"))
					.as(injected).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsAddressesAndCapabilitiesSplitAcrossHtmlNodes() {
		CampaignSafetySigner signer = new CampaignSafetySigner(KEY);
		String token = signer.issueOpen(UUID.randomUUID(), EXPIRY);
		for (String injected : new String[] {
			"logical<!--break-->@research.example",
			"logical<span>@</span>research.example",
			token.substring(0, token.indexOf(':')) + "<span>" + token.substring(token.indexOf(':')) + "</span>"
		}) {
			assertThatThrownBy(() -> policy.validateSource(
					"Subject", "<p>" + injected + "</p><a href=\"{{unsubscribe_url}}\">Stop</a>",
					"Stop {{unsubscribe_url}}"))
					.as(injected).isInstanceOf(IllegalArgumentException.class);
		}
	}

	private String compatibilityPercentEncoded(String value) {
		StringBuilder encoded = new StringBuilder(value.length() * 3);
		for (byte octet : value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
			int unsigned = Byte.toUnsignedInt(octet);
			encoded.append('％').append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)))
					.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
		}
		return encoded.toString();
	}
}
