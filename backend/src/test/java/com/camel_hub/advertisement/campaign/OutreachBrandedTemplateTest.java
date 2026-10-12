package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.template.TemplateModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutreachBrandedTemplateTest {

	private static final String UNSUBSCRIBE = "{{unsubscribe_url}}";
	private final TemplateEngine engine = new TemplateEngine(102_400);
	private final OutreachTemplateContentPolicy policy = new OutreachTemplateContentPolicy(engine);

	@Test
	void approvedLayoutAndSenderSurvivePreparationAndRendering() throws IOException {
		var template = template();
		var prepared = engine.prepare(draft(template));
		assertThat(prepared.validation().errors()).isEmpty();
		assertThat(prepared.validation().variables()).contains("author_name", "paper_title", "paper_url", "unsubscribe_url")
				.isSubsetOf("author_name", "paper_title", "paper_url", "arxiv_id", "unsubscribe_url");
		var canonical = Jsoup.parseBodyFragment(template.html());
		canonical.select("a").attr("rel", "noopener noreferrer");
		assertThat(prepared.sanitizedHtml()).isEqualTo(canonical.body().html().strip());
		assertThat(engine.prepare(new TemplateModels.TemplateDraft(template.subject(), template.fromName(),
				template.replyTo(), prepared.sanitizedHtml(), template.text(), false)).sanitizedHtml())
				.isEqualTo(prepared.sanitizedHtml());

		var rendered = engine.render(prepared, Map.of("author_name", "Ada Lovelace", "paper_title", "Frozen source paper", "arxiv_id", "2601.00001",
				"paper_url", "https://arxiv.org/abs/2601.00001", "unsubscribe_url", "https://example.org/unsubscribe/preview"));
		assertThat(rendered.fromName()).isEqualTo("Camel Hub API Gateway");
		assertThat(rendered.replyTo()).isEqualTo("reply@example.invalid");
		assertThat(rendered.html()).contains("role=\"presentation\"", "max-width:600px", "background-color:#f4f2ed",
				"color:#183d33", "color:#b3542b");
		assertThat(Jsoup.parseBodyFragment(rendered.html()).select("table[role=presentation]")).hasSizeGreaterThanOrEqualTo(2);
		assertThat(Jsoup.parseBodyFragment(rendered.html()).select("img")).isEmpty();
	}

	@Test
	void namedRecipientBindsOnlyTheFrozenPaperAndRetainsLateBoundUnsubscribe() throws IOException {
		var template = template();
		var first = policy.render(template, target("Ada Lovelace", "Frozen source paper", "2601.00001"));
		assertThat(Jsoup.parseBodyFragment(first.html()).text()).contains("Ada Lovelace");
		assertThat(first.text()).contains("Ada Lovelace");
		assertFrozenPaper(first, "Frozen source paper", "https://arxiv.org/abs/2601.00001");

		var second = policy.render(template, target("Grace Hopper", "Another frozen paper", "2601.00002"));
		assertFrozenPaper(second, "Another frozen paper", "https://arxiv.org/abs/2601.00002");
		assertThat(second.html() + second.text()).doesNotContain("Frozen source paper", "2601.00001", "Ada Lovelace");
	}

	@Test
	void unmappedAuthorHasNeutralGreetingAndNoOwnershipClaimInFrozenContent() throws IOException {
		var template = template();
		for (String author : new String[]{null, " "}) {
			var source = new CampaignRepository.FrozenProductionRecipient(
					UUID.fromString("22222222-2222-2222-2222-222222222222"),
					UUID.fromString("33333333-3333-3333-3333-333333333333"), null, new byte[]{1},
					"GENERATED", 0, true, target(author, "Frozen source paper", "2601.00001"));
			var rendered = policy.render(template, source.target());
			assertThat(Jsoup.parseBodyFragment(rendered.html()).text()).contains("research team");
			assertThat(rendered.text()).contains("research team");
			assertThat(rendered.subject() + "\n" + Jsoup.parseBodyFragment(rendered.html()).text() + "\n" + rendered.text())
					.doesNotContainPattern("(?i)\\byour\\s+(?:paper|research|work|study|publication|article)\\b|\\byou\\s+(?:authored|wrote|published)\\b|[您你]的(?:论文|研究|工作|文章)");
			assertFrozenPaper(rendered, "Frozen source paper", "https://arxiv.org/abs/2601.00001");
			policy.verifyReview(template, source, rendered, OutreachTemplateContentPolicy.VERSION,
					policy.sourceHash(template, source), policy.contentHash(rendered));
		}
	}

	@Test
	void hostilePaperTitleIsRenderedAsTextWithoutCreatingMarkupOrLinks() throws IOException {
		String title = "A <img src=x onerror=alert(1)> & <a href=\"javascript:alert(2)\">paper</a>";
		var rendered = policy.render(template(), target("Ada Lovelace", title, "2601.00001"));
		var document = Jsoup.parseBodyFragment(rendered.html());
		assertThat(document.text()).contains(title);
		assertThat(rendered.text()).contains(title);
		assertThat(rendered.html()).contains("&lt;img src=x onerror=alert(1)&gt;", "&amp;", "&lt;a href=")
				.doesNotContain("<img src=x", "<a href=\"javascript:alert(2)\"");
		assertThat(document.select("img, [onerror], a[href^='javascript:']")).isEmpty();
		assertFrozenPaper(rendered, title, "https://arxiv.org/abs/2601.00001");
	}

	@Test
	void automaticApprovalRejectsUnsafeLinksAndUnsubscribeRemovalFromEitherBody() throws IOException {
		var approved = template();
		for (String unsafe : List.of("https://arxiv.org/abs/2601.00002", "https://arxiv.org.evil.example/abs/2601.00001")) {
			for (var edited : List.of(
					bodies(approved, approved.html().replace("{{paper_url}}", unsafe), approved.text()),
					bodies(approved, approved.html(), approved.text().replace("{{paper_url}}", unsafe)))) {
				assertThatThrownBy(() -> policy.render(edited, target(null, "Frozen source paper", "2601.00001")))
						.isInstanceOf(CampaignValidationException.class);
			}
		}
		for (var edited : List.of(
				bodies(approved, approved.html().replace(UNSUBSCRIBE, "https://api.camel-hub.com/"), approved.text()),
				bodies(approved, approved.html(), approved.text().replace(UNSUBSCRIBE, "https://api.camel-hub.com/")))) {
			assertThatThrownBy(() -> policy.render(edited, target(null, "Frozen source paper", "2601.00001")))
					.isInstanceOf(CampaignValidationException.class)
					.hasMessageContaining("unsubscribe");
		}
	}

	private void assertFrozenPaper(CampaignRepository.GeneratedDraft rendered, String title, String paperUrl) {
		var document = Jsoup.parseBodyFragment(rendered.html());
		assertThat(rendered.subject()).contains(title);
		assertThat(document.text()).contains(title, "Research Team", "Camel Hub");
		assertThat(document.select("a[href]").eachAttr("href")).contains(paperUrl, UNSUBSCRIBE)
				.allMatch(url -> List.of(paperUrl, UNSUBSCRIBE, "https://api.camel-hub.com/", "https://api.camel-hub.cn/").contains(url));
		assertThat(rendered.text()).contains(title, paperUrl, UNSUBSCRIBE, "Research Team", "Camel Hub");
		assertThat((rendered.subject() + rendered.html() + rendered.text()).replace(UNSUBSCRIBE, ""))
				.doesNotContain("{{", "}}", "placeholder.invalid", "partner_claim_url", "/partner/claim");
	}

	private OutreachTemplateContentPolicy.TemplateSnapshot template() throws IOException {
		Path project = Path.of("").toAbsolutePath();
		if (project.getFileName().toString().equals("backend")) project = project.getParent();
		Path payload = project.resolve("docs/email/camel-hub-auto-outreach-template.json");
		assertThat(payload).as("Approved branded automatic-outreach template payload must exist").exists().isRegularFile();
		var content = new ObjectMapper().readTree(Files.readString(payload)).path("content");
		assertThat(content.isObject()).as("Template create payload must contain its content object").isTrue();
		assertThat(content.path("autoGenerateText").isBoolean()).isTrue();
		assertThat(content.path("autoGenerateText").booleanValue()).isFalse();
		var prepared = engine.prepare(new TemplateModels.TemplateDraft(content.path("subjectTemplate").asText(),
				content.path("fromNameTemplate").asText(), content.path("replyTo").asText(),
				content.path("htmlContent").asText(), content.path("textContent").asText(), false));
		assertThat(prepared.validation().errors()).isEmpty();
		var canonicalRawHtml = Jsoup.parseBodyFragment(content.path("htmlContent").asText());
		canonicalRawHtml.select("a").attr("rel", "noopener noreferrer");
		for (var link : canonicalRawHtml.select("a[href]")) {
			link.attr("href", link.attr("href").replaceAll("\\{\\{\\s*(paper_url|unsubscribe_url)\\s*}}", "{{$1}}"));
		}
		assertThat(prepared.sanitizedHtml()).as("Raw approved layout must not require unsupported or unsafe sanitization")
				.isEqualTo(canonicalRawHtml.body().html().strip());
		return new OutreachTemplateContentPolicy.TemplateSnapshot("camel-hub-auto-outreach-template.json",
				prepared.subjectTemplate(), prepared.sanitizedHtml(), prepared.textContent(),
				prepared.fromNameTemplate(), prepared.replyTo());
	}

	private TemplateModels.TemplateDraft draft(OutreachTemplateContentPolicy.TemplateSnapshot template) {
		return new TemplateModels.TemplateDraft(template.subject(), template.fromName(), template.replyTo(), template.html(), template.text(), false);
	}

	private OutreachTemplateContentPolicy.TemplateSnapshot bodies(OutreachTemplateContentPolicy.TemplateSnapshot template, String html, String text) {
		return new OutreachTemplateContentPolicy.TemplateSnapshot(template.provenance(), template.subject(), html, text, template.fromName(), template.replyTo());
	}

	private CampaignRepository.QueuedTarget target(String author, String title, String arxivId) {
		return new CampaignRepository.QueuedTarget(UUID.fromString("11111111-1111-1111-1111-111111111111"),
				author, title, "Source abstract", arxivId, "cs.AI", null);
	}
}
