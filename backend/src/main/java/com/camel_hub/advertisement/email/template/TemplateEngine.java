package com.camel_hub.advertisement.email.template;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TemplateEngine {

	public static final Set<String> ALLOWED_VARIABLES = Set.of(
			"author_name", "first_name", "paper_title", "arxiv_id", "primary_category",
			"paper_url", "organization", "unsubscribe_url");
	private static final Set<String> URL_VARIABLES = Set.of("paper_url", "unsubscribe_url");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");
	private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("^\\{\\{\\s*([a-z_]+)\\s*}}$");
	private static final Pattern BRACE = Pattern.compile("\\{\\{|}}");
	private static final Pattern TEMPLATE_ASSET_PATH = Pattern.compile(
			"^/api/v1/templates/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
					+ "/assets/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/content$");
	private static final Safelist EMAIL_HTML = new Safelist()
			.addTags("p", "div", "span", "br", "h1", "h2", "h3", "h4", "h5", "h6",
					"strong", "em", "b", "i", "u", "ul", "ol", "li", "a", "img", "table",
					"thead", "tbody", "tfoot", "tr", "td", "th", "blockquote", "hr", "pre", "code")
			.addAttributes("a", "href", "title", "target", "rel")
			.addAttributes("img", "src", "alt", "title", "width", "height")
			.addAttributes("table", "width", "cellpadding", "cellspacing", "border")
			.addAttributes("td", "width", "colspan", "rowspan", "align", "valign")
			.addAttributes("th", "width", "colspan", "rowspan", "align", "valign")
			.addProtocols("a", "href", "http", "https", "mailto")
			.addProtocols("img", "src", "http", "https")
			.addEnforcedAttribute("a", "rel", "noopener noreferrer");

	private final int maxContentBytes;

	public TemplateEngine(int maxContentBytes) {
		if (maxContentBytes < 64) {
			throw new IllegalArgumentException("Template byte limit is invalid");
		}
		this.maxContentBytes = maxContentBytes;
	}

	public TemplateModels.PreparedTemplate prepare(TemplateModels.TemplateDraft draft) {
		if (draft == null) {
			throw new TemplateValidationException("Template content is required");
		}
		String subject = normalized(draft.subjectTemplate());
		String fromName = normalized(draft.fromNameTemplate());
		String replyTo = normalized(draft.replyTo());
		String rawHtml = draft.htmlContent() == null ? "" : draft.htmlContent().strip();
		validateAttributeContexts(rawHtml);
		String html = sanitize(rawHtml);
		String text = draft.autoGenerateText() || normalized(draft.textContent()).isEmpty()
				? deriveText(html)
				: draft.textContent().strip();

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		if (subject.isEmpty() || subject.length() > 998 || containsHeaderBreak(subject)) {
			errors.add("Subject must contain 1 to 998 safe characters");
		}
		if (fromName.isEmpty() || fromName.length() > 160 || containsHeaderBreak(fromName)) {
			errors.add("Sender name must contain 1 to 160 safe characters");
		}
		if (!validEmail(replyTo)) {
			errors.add("Reply-To must be one valid email address");
		}
		if (html.isEmpty()) errors.add("HTML content is required");
		if (text.isEmpty()) errors.add("Plain text content is required");

		Set<String> variables = new TreeSet<>();
		for (String value : List.of(subject, fromName, html, text)) {
			collectVariables(value, variables, errors);
		}
		if (!variables.contains("unsubscribe_url")) {
			warnings.add("unsubscribe_url is not present");
		}
		if (!html.equals(rawHtml)) {
			warnings.add("Unsafe or unsupported HTML was removed");
		}
		int size = byteSize(subject, fromName, replyTo, html, text);
		if (size > maxContentBytes) {
			throw new TemplateValidationException("Template content size exceeds " + maxContentBytes + " bytes");
		}
		if (size > (maxContentBytes * 8L) / 10L) {
			warnings.add("Template content is above 80% of the configured size limit");
		}
		var validation = new TemplateModels.ValidationResult(
				errors.isEmpty(), List.copyOf(new LinkedHashSet<>(errors)), List.copyOf(warnings), Set.copyOf(variables));
		return new TemplateModels.PreparedTemplate(
				subject, fromName, replyTo, html, text, draft.autoGenerateText(), size, validation);
	}

	public TemplateModels.RenderedTemplate render(
			TemplateModels.PreparedTemplate prepared, Map<String, String> values
	) {
		if (prepared == null || !prepared.validation().valid()) {
			throw new TemplateValidationException("Template must pass validation before rendering");
		}
		Map<String, String> safeValues = values == null ? Map.of() : Map.copyOf(values);
		for (String variable : prepared.validation().variables()) {
			if (!safeValues.containsKey(variable) || safeValues.get(variable) == null) {
				throw new TemplateValidationException("Missing template variable: " + variable);
			}
		}
		Document document = Jsoup.parseBodyFragment(prepared.sanitizedHtml());
		renderNode(document.body(), safeValues);
		String html = document.body().html();
		return new TemplateModels.RenderedTemplate(
				header(renderScalar(prepared.subjectTemplate(), safeValues), "subject", 998),
				header(renderScalar(prepared.fromNameTemplate(), safeValues), "sender name", 160),
				prepared.replyTo(), html, renderScalar(prepared.textContent(), safeValues));
	}

	private void renderNode(Node node, Map<String, String> values) {
		if (node instanceof TextNode textNode) {
			textNode.text(renderScalar(textNode.getWholeText(), values));
		}
		if (node instanceof Element element) {
			for (Attribute attribute : List.copyOf(element.attributes().asList())) {
				Matcher matcher = WHOLE_PLACEHOLDER.matcher(attribute.getValue());
				if (!matcher.matches()) continue;
				String variable = matcher.group(1);
				String value = values.get(variable);
				if (!URL_VARIABLES.contains(variable) || !isSafeAbsoluteUrl(value)) {
					throw new TemplateValidationException("Unsafe URL value for " + variable);
				}
				element.attr(attribute.getKey(), value);
			}
		}
		for (Node child : List.copyOf(node.childNodes())) renderNode(child, values);
	}

	private String renderScalar(String template, Map<String, String> values) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder rendered = new StringBuilder();
		while (matcher.find()) {
			String value = values.get(matcher.group(1));
			if (value == null) throw new TemplateValidationException("Missing template variable: " + matcher.group(1));
			matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(rendered);
		return rendered.toString();
	}

	private void validateAttributeContexts(String html) {
		Document document = Jsoup.parseBodyFragment(html);
		for (Element element : document.getAllElements()) {
			for (Attribute attribute : element.attributes()) {
				String value = attribute.getValue();
				if (!BRACE.matcher(value).find()) continue;
				Matcher matcher = WHOLE_PLACEHOLDER.matcher(value);
				if (!matcher.matches() || !attribute.getKey().equals("href")
						|| !URL_VARIABLES.contains(matcher.group(1))) {
					throw new TemplateValidationException(
							"Template variables in an HTML attribute must be a whole safe URL attribute");
				}
			}
		}
	}

	private void collectVariables(String value, Set<String> variables, List<String> errors) {
		Matcher matcher = PLACEHOLDER.matcher(value);
		StringBuilder unmatched = new StringBuilder(value);
		while (matcher.find()) {
			String variable = matcher.group(1);
			variables.add(variable);
			if (!ALLOWED_VARIABLES.contains(variable)) errors.add("Unknown template variable: " + variable);
			unmatched.replace(matcher.start(), matcher.end(), " ".repeat(matcher.end() - matcher.start()));
		}
		if (BRACE.matcher(unmatched).find()) errors.add("Malformed template placeholder");
	}

	private String sanitize(String html) {
		Document parsed = Jsoup.parseBodyFragment(html);
		Map<String, String> assetPlaceholders = new LinkedHashMap<>();
		for (Element element : parsed.getAllElements()) {
			for (Attribute attribute : element.attributes()) {
				Matcher matcher = WHOLE_PLACEHOLDER.matcher(attribute.getValue());
				if (matcher.matches() && URL_VARIABLES.contains(matcher.group(1))) {
					element.attr(attribute.getKey(), "https://placeholder.invalid/" + matcher.group(1));
				}
			}
			if (element.tagName().equals("img") && (TEMPLATE_ASSET_PATH.matcher(element.attr("src")).matches()
					|| TemplateAssetSigner.isSignedPath(element.attr("src")))) {
				String placeholder = "https://template-asset.invalid/" + assetPlaceholders.size();
				assetPlaceholders.put(placeholder, element.attr("src"));
				element.attr("src", placeholder);
			}
		}
		Document.OutputSettings output = new Document.OutputSettings().prettyPrint(false);
		String clean = Jsoup.clean(parsed.body().html(), "", EMAIL_HTML, output).strip();
		for (String variable : URL_VARIABLES) {
			clean = clean.replace("https://placeholder.invalid/" + variable, "{{" + variable + "}}");
		}
		for (Map.Entry<String, String> asset : assetPlaceholders.entrySet()) {
			clean = clean.replace(asset.getKey(), asset.getValue());
		}
		return clean;
	}

	private String deriveText(String html) {
		Document document = Jsoup.parseBodyFragment(html);
		for (Element link : document.select("a[href]")) {
			String href = link.attr("href").strip();
			if (!href.isEmpty() && !link.text().strip().equals(href)) {
				link.appendText(" (" + href + ")");
			}
		}
		document.select("br").append("\\n");
		document.select("p,div,h1,h2,h3,h4,h5,h6,li,tr,blockquote").append("\\n");
		return document.text().replace("\\n", "\n").replaceAll("[ \\t]+\\n", "\n")
				.replaceAll("\\n{3,}", "\n\n").strip();
	}

	private boolean validEmail(String value) {
		try {
			InternetAddress address = new InternetAddress(value, true);
			return address.getAddress().equals(value) && !value.contains("\r") && !value.contains("\n");
		}
		catch (AddressException exception) {
			return false;
		}
	}

	private boolean isSafeAbsoluteUrl(String value) {
		if (value == null || value.length() > 2_048) return false;
		try {
			URI uri = new URI(value);
			return uri.isAbsolute() && uri.getHost() != null && uri.getUserInfo() == null
					&& (uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"));
		}
		catch (URISyntaxException exception) {
			return false;
		}
	}

	private String header(String value, String label, int maxLength) {
		if (value.isEmpty() || value.length() > maxLength
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw new TemplateValidationException("Rendered " + label + " is invalid");
		}
		return value;
	}

	private boolean containsHeaderBreak(String value) {
		return value.contains("\r") || value.contains("\n");
	}

	private String normalized(String value) {
		return value == null ? "" : value.strip();
	}

	private int byteSize(String... values) {
		long size = 0;
		for (String value : values) size += value.getBytes(StandardCharsets.UTF_8).length;
		return Math.toIntExact(size);
	}
}
