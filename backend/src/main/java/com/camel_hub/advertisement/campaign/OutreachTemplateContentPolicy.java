package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.campaign.partner.PartnerContentPolicy;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.template.TemplateModels;
import com.camel_hub.advertisement.email.template.TemplateValidationException;
import org.jsoup.Jsoup;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deliberately conservative machine checks; this is not human semantic verification. */
public final class OutreachTemplateContentPolicy {
	public static final String VERSION = "exact-v1";
	private static final String UNSUBSCRIBE = "{{unsubscribe_url}}";
	private static final String INERT_UNSUBSCRIBE = "https://placeholder.invalid/outreach-unsubscribe-exact-v1";
	private static final String RATIONALE = "Deterministic template rendering; frozen source fields only; machine policy exact-v1.";
	private static final Pattern ARXIV_ID = Pattern.compile("^([0-9]{4}[.][0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$");
	private static final Pattern OWNERSHIP = Pattern.compile("(?i)\\byour\\s+(?:paper|research|work|study|publication|article)\\b|\\byou\\s+(?:authored|wrote|published)\\b|[您你]的(?:论文|研究|工作|文章)");
	private static final Pattern MONEY = Pattern.compile("(?i)(?:[$€£¥￥]\\s*[0-9]|[0-9][0-9,.]*\\s*(?:USD|EUR|GBP|CNY|RMB|dollars?|euros?|元|美元|人民币))");
	private static final Pattern TEXT_URL = Pattern.compile("https?://[^\\s<>\\\"'()]+", Pattern.CASE_INSENSITIVE);
	private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
	private static final Pattern ASSET_PATH = Pattern.compile("^/api/v1/(?:templates/" + UUID_PATTERN + "/assets/" + UUID_PATTERN + "/content|template-assets/" + UUID_PATTERN + "/" + UUID_PATTERN + "/content\\?signature=[A-Za-z0-9_-]{43})$");
	private static final Set<String> OFFICIAL_LINKS = Set.of("https://api.camel-hub.com/", "https://api.camel-hub.cn/");
	private final TemplateEngine engine;

	public OutreachTemplateContentPolicy(TemplateEngine engine) {
		this.engine = engine;
	}

	public <T> List<T> select(List<T> candidates, Integer exactEmailCount) {
		if (candidates == null || candidates.isEmpty()) throw invalid("No eligible paper contacts were found");
		if (exactEmailCount != null) {
			if (exactEmailCount < 1 || exactEmailCount > 120) throw invalid("Exact email target must be between 1 and 120");
			if (candidates.size() < exactEmailCount) throw invalid("Insufficient eligible addresses for the exact email target");
			return List.copyOf(candidates.subList(0, exactEmailCount));
		}
		if (candidates.size() > 120) throw invalid("Paper selection exceeds 120 eligible addresses; choose fewer papers");
		return List.copyOf(candidates);
	}

	public CampaignRepository.GeneratedDraft render(TemplateSnapshot template, CampaignRepository.QueuedTarget source) {
		try {
			validateSource(source);
			boolean anonymous = source.authorName() == null || source.authorName().isBlank();
			String author = anonymous ? "research team" : source.authorName();
			String paperUrl = "https://arxiv.org/abs/" + source.arxivId();
			String all = template.subject() + "\n" + template.html() + "\n" + template.text();
			if (all.contains(INERT_UNSUBSCRIBE)) throw invalid("Reserved rendering URLs are not valid template content");
			if (all.contains("partner_claim_url") || PartnerContentPolicy.containsCapability(all)) {
				throw invalid("Partner activation is not supported by automatic template review");
			}
			String visible = Normalizer.normalize(Jsoup.parseBodyFragment(template.html()).text() + "\n" + template.subject() + "\n" + template.text(), Normalizer.Form.NFKC);
			if (MONEY.matcher(visible).find()) throw invalid("Payment amounts require explicit content review");
			if (anonymous && OWNERSHIP.matcher(visible).find()) throw invalid("Unknown author identity cannot support ownership wording");
			var prepared = engine.prepare(new TemplateModels.TemplateDraft(template.subject(), template.fromName(), template.replyTo(), template.html(), template.text(), false));
			if (!prepared.validation().valid() || prepared.subjectTemplate().contains("unsubscribe_url")) throw invalid("Template headers or variables are invalid");
			var canonical = Jsoup.parseBodyFragment(template.html());
			canonical.select("a").attr("rel", "noopener noreferrer");
			for (var link : canonical.select("a[href]")) {
				link.attr("href", link.attr("href").replaceAll("\\{\\{\\s*(paper_url|unsubscribe_url)\\s*}}", "{{$1}}"));
			}
			if (!prepared.sanitizedHtml().equals(canonical.body().html().strip())) throw invalid("Template HTML requires unsafe or unsupported sanitization");
			var rendered = engine.render(prepared, Map.of(
					"author_name", author, "first_name", anonymous ? "research team" : author.strip().split("\\s+", 2)[0],
					"paper_title", source.paperTitle(), "arxiv_id", source.arxivId(),
					"primary_category", source.primaryCategory() == null ? "" : source.primaryCategory(),
					"paper_url", paperUrl, "organization", anonymous || source.organization() == null ? "" : source.organization(),
					"unsubscribe_url", INERT_UNSUBSCRIBE));
			String html = rendered.html().replace(INERT_UNSUBSCRIBE, UNSUBSCRIBE);
			String text = rendered.text().replace(INERT_UNSUBSCRIBE, UNSUBSCRIBE);
			if (!html.contains(UNSUBSCRIBE) || !text.contains(UNSUBSCRIBE)) throw invalid("HTML and text must retain the exact unsubscribe placeholder");
			String remaining = (rendered.subject() + html + text).replace(UNSUBSCRIBE, "");
			if (remaining.contains("{{") || remaining.contains("}}")) throw invalid("Unresolved template variables are not permitted");
			var document = Jsoup.parseBodyFragment(html);
			for (var link : document.select("a[href]")) validateLink(link.attr("href"), paperUrl);
			for (var image : document.select("img")) {
				if (!ASSET_PATH.matcher(image.attr("src")).matches()) throw invalid("Automatic templates support only existing local template assets");
			}
			var urls = TEXT_URL.matcher(text);
			while (urls.find()) validateLink(urls.group().replaceAll("[.,;:!?]+$", ""), paperUrl);
			return new CampaignRepository.GeneratedDraft(rendered.subject(), html, text, RATIONALE);
		}
		catch (TemplateValidationException invalid) {
			throw invalid("Template content did not pass email safety validation");
		}
	}

	private void validateLink(String url, String paperUrl) {
		if (!UNSUBSCRIBE.equals(url) && !paperUrl.equals(url) && !OFFICIAL_LINKS.contains(url)) {
			throw invalid("Template links must be the frozen paper, official website, or unsubscribe placeholder");
		}
	}

	public void validateSource(CampaignRepository.QueuedTarget source) {
		if (source == null || source.recipientId() == null || source.arxivId() == null || !ARXIV_ID.matcher(source.arxivId()).matches()) throw invalid("Frozen paper source is invalid");
		safeScalar(source.authorName(), 300, false, false);
		safeScalar(source.paperTitle(), 2000, true, false);
		safeScalar(source.abstractText(), 20_000, true, true);
		safeScalar(source.primaryCategory(), 100, false, false);
		safeScalar(source.organization(), 500, false, false);
	}

	private void safeScalar(String value, int maximum, boolean required, boolean multiline) {
		if (value == null) {
			if (required) throw invalid("Required frozen source field is missing");
			return;
		}
		if (value.length() > maximum || required && value.isBlank() || value.contains("{{") || value.contains("}}") || value.contains(INERT_UNSUBSCRIBE)
				|| value.contains("partner_claim_url") || PartnerContentPolicy.containsCapability(value)
				|| value.codePoints().anyMatch(c -> Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t')))) {
			throw invalid("Frozen source contains unsafe or invalid values");
		}
	}

	public byte[] sourceHash(TemplateSnapshot template, CampaignRepository.FrozenProductionRecipient source) {
		var target = source.target();
		return digest(VERSION, template.provenance(), template.subject(), template.html(), template.text(), template.fromName(), template.replyTo(),
				source.contactId(), source.paperId(), source.authorId(), HexFormat.of().formatHex(source.emailHmac()),
				target.recipientId(), target.authorName(), target.paperTitle(), target.abstractText(), target.arxivId(), target.primaryCategory(), target.organization());
	}

	public byte[] contentHash(CampaignRepository.GeneratedDraft draft) {
		return digest(draft.subject(), draft.html(), draft.text(), draft.rationale());
	}

	public void verifyReview(TemplateSnapshot template, CampaignRepository.FrozenProductionRecipient source,
			CampaignRepository.GeneratedDraft stored, String policyVersion, byte[] sourceHash, byte[] contentHash) {
		var expected = render(template, source.target());
		if (!VERSION.equals(policyVersion) || !Arrays.equals(sourceHash(template, source), sourceHash)
				|| !Arrays.equals(contentHash(stored), contentHash) || !Arrays.equals(contentHash(expected), contentHash)) {
			throw invalid("Automatic content review is stale; create a new outreach run");
		}
	}

	private byte[] digest(Object... fields) {
		try {
			MessageDigest hash = MessageDigest.getInstance("SHA-256");
			for (Object field : fields) {
				byte[] value = field == null ? new byte[0] : field.toString().getBytes(StandardCharsets.UTF_8);
				hash.update(ByteBuffer.allocate(4).putInt(field == null ? -1 : value.length).array());
				hash.update(value);
			}
			return hash.digest();
		}
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}

	private CampaignValidationException invalid(String message) { return new CampaignValidationException(message); }

	public record TemplateSnapshot(String provenance, String subject, String html, String text, String fromName, String replyTo) { }
}
