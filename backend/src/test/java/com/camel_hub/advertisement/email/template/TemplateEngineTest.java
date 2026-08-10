package com.camel_hub.advertisement.email.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateEngineTest {

	private final TemplateEngine engine = new TemplateEngine(102_400);

	@Test
	void sanitizesDangerousMarkupAndReportsUnknownOrMalformedVariables() {
		var prepared = engine.prepare(new TemplateModels.TemplateDraft(
				"Hello {{author_name}}", "Research Team", "reply@example.org",
				"<p onclick=\"steal()\">Hi {{author_name}}</p><script>alert(1)</script>"
						+ "<iframe src=\"https://evil.example\"></iframe>"
						+ "<a href=\"javascript:alert(1)\">bad</a><img src=\"data:text/html,boom\">",
				"Hi {{mystery}} and {{paper_title", false));

		assertThat(prepared.sanitizedHtml())
				.doesNotContain("script", "iframe", "onclick", "javascript:", "data:");
		assertThat(prepared.validation().valid()).isFalse();
		assertThat(prepared.validation().errors())
				.anyMatch(message -> message.contains("mystery"))
				.anyMatch(message -> message.contains("Malformed"));
	}

	@Test
	void rendersTextVariablesAsTextAndUrlVariablesOnlyInWholeSafeAttributes() {
		var prepared = engine.prepare(new TemplateModels.TemplateDraft(
				"New paper: {{paper_title}}", "{{author_name}}", "reply@example.org",
				"<p>Hello {{author_name}}</p><a href=\"{{paper_url}}\">Read {{paper_title}}</a>"
						+ "<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
				"Hello {{author_name}} — {{paper_url}}", false));

		var rendered = engine.render(prepared, Map.of(
				"author_name", "Ada <Admin>",
				"paper_title", "A & B",
				"paper_url", "https://arxiv.org/abs/1234.5678?q=a&b=1",
				"unsubscribe_url", "https://example.org/unsubscribe/t-1"));

		assertThat(rendered.subject()).isEqualTo("New paper: A & B");
		assertThat(rendered.html()).contains("Ada &lt;Admin&gt;").contains("A &amp; B")
				.contains("href=\"https://arxiv.org/abs/1234.5678?q=a&amp;b=1\"");
		assertThat(rendered.text()).contains("Ada <Admin>").contains("https://arxiv.org/abs/1234.5678");
		assertThatThrownBy(() -> engine.prepare(new TemplateModels.TemplateDraft(
				"Subject", "Sender", "reply@example.org",
				"<a href=\"https://example.org/{{author_name}}\">bad context</a>", "Text", false)))
				.isInstanceOf(TemplateValidationException.class)
				.hasMessageContaining("attribute");
	}

	@Test
	void derivesPlainTextAndWarnsWhenUnsubscribeVariableIsMissing() {
		var prepared = engine.prepare(new TemplateModels.TemplateDraft(
				"Hello {{first_name}}", "Research Team", "reply@example.org",
				"<h1>Welcome</h1><p>Paper {{arxiv_id}}</p>", "", true));

		assertThat(prepared.textContent()).contains("Welcome").contains("Paper {{arxiv_id}}");
		assertThat(prepared.validation().warnings()).contains("unsubscribe_url is not present");
		assertThat(prepared.validation().variables()).containsExactlyInAnyOrder("first_name", "arxiv_id");
	}

	@Test
	void derivedPlainTextRetainsSafeLinkDestinations() {
		var prepared = engine.prepare(new TemplateModels.TemplateDraft(
				"Paper", "Research Team", "reply@example.org",
				"<p><a href=\"{{paper_url}}\">Read paper</a></p>"
						+ "<p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a></p>", "stale", true));

		assertThat(prepared.textContent())
				.contains("Read paper ({{paper_url}})")
				.contains("Unsubscribe ({{unsubscribe_url}})");
		var rendered = engine.render(prepared, Map.of(
				"paper_url", "https://arxiv.org/abs/2608.01234",
				"unsubscribe_url", "https://example.org/unsubscribe/preview"));
		assertThat(rendered.text())
				.contains("https://arxiv.org/abs/2608.01234")
				.contains("https://example.org/unsubscribe/preview");
	}

	@Test
	void blocksContentAboveTheConfiguredByteLimit() {
		TemplateEngine tiny = new TemplateEngine(64);

		assertThatThrownBy(() -> tiny.prepare(new TemplateModels.TemplateDraft(
				"Subject", "Sender", "reply@example.org", "<p>" + "x".repeat(100) + "</p>", "text", false)))
				.isInstanceOf(TemplateValidationException.class)
				.hasMessageContaining("size");
	}

	@Test
	void preservesOnlyTheAuthorizedSameOriginTemplateAssetPath() {
		String assetPath = "/api/v1/templates/ddeb786e-051d-4fdf-aaa0-275943dc086a"
				+ "/assets/1ece7eb8-0f55-4ae2-8097-c04a66a7a8a1/content";
		String signedPath = "/api/v1/template-assets/ddeb786e-051d-4fdf-aaa0-275943dc086a"
				+ "/1ece7eb8-0f55-4ae2-8097-c04a66a7a8a1/content"
				+ "?signature=abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";

		var prepared = engine.prepare(new TemplateModels.TemplateDraft(
				"Subject", "Sender", "reply@example.org",
				"<p>Image</p><img src=\"" + assetPath + "\" alt=\"figure\">"
						+ "<img src=\"" + signedPath + "\" alt=\"signed figure\">"
						+ "<img src=\"/api/private/secret\"><img src=\"//evil.example/pixel\">",
				"Image {{unsubscribe_url}}", false));

		assertThat(prepared.sanitizedHtml()).contains("src=\"" + assetPath + "\"")
				.contains("src=\"" + signedPath.replace("&", "&amp;") + "\"")
				.doesNotContain("/api/private/secret", "//evil.example/pixel");
	}

	@Test
	void rejectsRenderedHeadersThatExceedTheirFinalBoundsOrContainControls() {
		var subject = engine.prepare(new TemplateModels.TemplateDraft(
				"{{paper_title}}", "Research Team", "reply@example.org",
				"<p>Hello</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "Hello", false));
		assertThatThrownBy(() -> engine.render(subject, Map.of(
				"paper_title", "x".repeat(999),
				"unsubscribe_url", "https://example.org/unsubscribe/1")))
				.isInstanceOf(TemplateValidationException.class)
				.hasMessageContaining("subject");

		var sender = engine.prepare(new TemplateModels.TemplateDraft(
				"Subject", "{{organization}}", "reply@example.org",
				"<p>Hello</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "Hello", false));
		assertThatThrownBy(() -> engine.render(sender, Map.of(
				"organization", "x".repeat(161),
				"unsubscribe_url", "https://example.org/unsubscribe/1")))
				.isInstanceOf(TemplateValidationException.class)
				.hasMessageContaining("sender name");
		assertThatThrownBy(() -> engine.render(sender, Map.of(
				"organization", "Research\u0007Team",
				"unsubscribe_url", "https://example.org/unsubscribe/1")))
				.isInstanceOf(TemplateValidationException.class)
				.hasMessageContaining("sender name");
	}
}
