package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.email.template.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutreachTemplateContentPolicyTest {

	private final OutreachTemplateContentPolicy policy = new OutreachTemplateContentPolicy(new TemplateEngine(100_000));

	@Test
	void exactEmailTargetSelectsExactlyOneHundredFromAnOverflowSentinel() {
		var candidates = IntStream.range(0, 121).boxed().toList();
		assertThat(policy.select(candidates, 100)).hasSize(100).containsExactlyElementsOf(IntStream.range(0, 100).boxed().toList());
	}

	@Test
	void insufficientExactCandidatesBlockWithoutReducingTheTarget() {
		assertThatThrownBy(() -> policy.select(List.of(1, 2), 3)).isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void paperModeNeverSilentlyTruncatesMoreThanOneHundredTwentyAddresses() {
		assertThatThrownBy(() -> policy.select(IntStream.range(0, 121).boxed().toList(), null))
				.isInstanceOf(CampaignValidationException.class);
		assertThatThrownBy(() -> policy.select(List.of(), null)).isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void anonymousSourceUsesResearchTeamAndRetainsOnlyTheLateBoundUnsubscribePlaceholder() {
		var draft = policy.render(template(), target(null, "A <b>paper</b> & title"));
		assertThat(draft.subject()).isEqualTo("Research invitation: A <b>paper</b> & title");
		assertThat(draft.html()).contains("Hello research team", "A &lt;b&gt;paper&lt;/b&gt; &amp; title", "href=\"{{unsubscribe_url}}\"", "https://arxiv.org/abs/2601.00001")
				.doesNotContain("placeholder.invalid", "<b>paper</b>");
		assertThat(draft.text()).contains("Hello research team", "{{unsubscribe_url}}").doesNotContain("placeholder.invalid");
	}

	@Test
	void sourceHeaderInjectionCannotBecomeMailHeaders() {
		assertThatThrownBy(() -> policy.render(template(), target("Ada\r\nBcc: victim@example.test", "Title")))
				.isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void unknownIdentityCannotBeCreditedWithOwnershipOfThePaper() {
		var template = template("Your paper {{paper_title}}", "<p>Your paper {{paper_title}}</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "Your paper {{paper_title}}\n{{unsubscribe_url}}");
		assertThatThrownBy(() -> policy.render(template, target(null, "Title"))).isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void partnerClaimPlaceholdersAndFinancialAmountsBlock() {
		assertThatThrownBy(() -> policy.render(template("Invitation", "<p>{{partner_claim_url}}</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "{{partner_claim_url}} {{unsubscribe_url}}"), target("Ada", "Title")))
				.isInstanceOf(CampaignValidationException.class);
		assertThatThrownBy(() -> policy.render(template("Invitation", "<p>We promise $10.</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "We promise $10. {{unsubscribe_url}}"), target("Ada", "Title")))
				.isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void unrelatedArxivLinksAndUnapprovedExternalLinksBlock() {
		for (String url : List.of("https://arxiv.org/abs/2601.00002", "https://arxiv.org.evil.test/abs/2601.00001", "https://unknown.example/")) {
			assertThatThrownBy(() -> policy.render(template("Invitation", "<a href=\"" + url + "\">Visit</a><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "{{unsubscribe_url}}"), target("Ada", "Title")))
					.isInstanceOf(CampaignValidationException.class);
		}
	}

	@Test
	void changingAnyOfTheFourContentFieldsInvalidatesTheStoredReviewHash() {
		var original = new CampaignRepository.GeneratedDraft("Subject", "<p>Body</p>", "Body", "Source only");
		for (var changed : List.of(new CampaignRepository.GeneratedDraft("Changed", original.html(), original.text(), original.rationale()),
				new CampaignRepository.GeneratedDraft(original.subject(), "<p>Changed</p>", original.text(), original.rationale()),
				new CampaignRepository.GeneratedDraft(original.subject(), original.html(), "Changed", original.rationale()),
				new CampaignRepository.GeneratedDraft(original.subject(), original.html(), original.text(), "Changed"))) {
			assertThat(policy.contentHash(changed)).isNotEqualTo(policy.contentHash(original));
		}
	}

	@Test
	void templateSenderAndFrozenSourceChangesInvalidateTheSourceReview() {
		var source = new CampaignRepository.FrozenProductionRecipient(UUID.randomUUID(), UUID.randomUUID(), null, new byte[]{1}, "GENERATED", 0, true, target(null, "Title"));
		byte[] hash = policy.sourceHash(template(), source);
		var changed = new CampaignRepository.FrozenProductionRecipient(source.contactId(), source.paperId(), null, source.emailHmac(), "GENERATED", 0, true, target(null, "Changed"));
		assertThat(policy.sourceHash(template(), changed)).isNotEqualTo(hash);
		var senderChanged = new OutreachTemplateContentPolicy.TemplateSnapshot("same frozen manifest", template().subject(), template().html(), template().text(), "Other sender", "sender@example.test");
		assertThat(policy.sourceHash(senderChanged, source)).isNotEqualTo(hash);
	}

	@Test
	void freshReviewRejectsEditedContentEvenIfAnEditorReplacedTheContentHash() {
		var source = new CampaignRepository.FrozenProductionRecipient(UUID.randomUUID(), UUID.randomUUID(), null, new byte[]{1}, "GENERATED", 0, true, target(null, "Title"));
		var original = policy.render(template(), source.target());
		var changed = new CampaignRepository.GeneratedDraft(original.subject(), original.html(), "Edited {{unsubscribe_url}}", original.rationale());
		policy.verifyReview(template(), source, original, "exact-v1", policy.sourceHash(template(), source), policy.contentHash(original));
		assertThatThrownBy(() -> policy.verifyReview(template(), source, changed, "exact-v1", policy.sourceHash(template(), source), policy.contentHash(changed)))
				.isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void unsafeHtmlAndSourcePlaceholdersCannotPassTheReviewBoundary() {
		assertThatThrownBy(() -> policy.render(template("Invitation", "<script>alert(1)</script><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "{{unsubscribe_url}}"), target("Ada", "Title")))
				.isInstanceOf(CampaignValidationException.class);
		assertThatThrownBy(() -> policy.render(template(), target("Ada", "{{unsubscribe_url}}")))
				.isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void selectedOfficialWebsiteTextAndTheSourcePaperAreAcceptedWithoutAccessingThem() {
		var template = template("Invitation", "<p>{{paper_title}}</p><a href=\"https://api.camel-hub.com/\">Camel Hub</a><a href=\"{{paper_url}}\">Paper</a><a href=\"{{ unsubscribe_url }}\">Unsubscribe</a>", "https://api.camel-hub.com/ {{paper_url}} {{ unsubscribe_url }}");
		var draft = policy.render(template, target(null, "Title"));
		assertThat(draft.html()).contains("https://api.camel-hub.com/", "https://arxiv.org/abs/2601.00001", "{{unsubscribe_url}}");
		assertThat(draft.text()).isEqualTo("https://api.camel-hub.com/ https://arxiv.org/abs/2601.00001 {{unsubscribe_url}}");
	}

	@Test
	void bothBodiesMustContainTheUnsubscribePlaceholderAndNoExternalImageIsAllowed() {
		assertThatThrownBy(() -> policy.render(template("Invitation", "<p>No unsubscribe</p>", "{{unsubscribe_url}}"), target(null, "Title")))
				.isInstanceOf(CampaignValidationException.class);
		assertThatThrownBy(() -> policy.render(template("Invitation", "<img src=\"https://api.camel-hub.com/pixel.png\"><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "{{unsubscribe_url}}"), target(null, "Title")))
				.isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void literalInertRenderingUrlCannotMasqueradeAsAnUnsubscribeVariable() {
		assertThatThrownBy(() -> policy.render(template("Invitation", "<a href=\"https://placeholder.invalid/outreach-unsubscribe-exact-v1\">Unsubscribe</a>", "https://placeholder.invalid/outreach-unsubscribe-exact-v1"), target(null, "Title")))
				.isInstanceOf(CampaignValidationException.class);
	}

	@Test
	void sourceFieldsCannotIntroduceBareOrEncodedPartnerCapabilities() {
		for (String capability : List.of("api.camel-hub.cn/partner/claim/test-token", "api.camel-hub.cn%2Fpartner%2Fclaim%2Ftest-token")) {
			assertThatThrownBy(() -> policy.render(template(), target(null, capability)))
					.isInstanceOf(CampaignValidationException.class);
		}
	}

	private OutreachTemplateContentPolicy.TemplateSnapshot template() {
		return template("Research invitation: {{paper_title}}", "<p>Hello {{author_name}}, {{paper_title}}</p><a href=\"{{paper_url}}\">Paper</a><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "Hello {{first_name}}, {{paper_title}}\n{{paper_url}}\n{{unsubscribe_url}}");
	}

	private OutreachTemplateContentPolicy.TemplateSnapshot template(String subject, String html, String text) {
		return new OutreachTemplateContentPolicy.TemplateSnapshot("same frozen manifest", subject, html, text, "Camel Hub", "sender@example.test");
	}

	private CampaignRepository.QueuedTarget target(String author, String title) {
		return new CampaignRepository.QueuedTarget(UUID.fromString("11111111-1111-1111-1111-111111111111"), author, title, "Source abstract", "2601.00001", "cs.AI", null);
	}
}
