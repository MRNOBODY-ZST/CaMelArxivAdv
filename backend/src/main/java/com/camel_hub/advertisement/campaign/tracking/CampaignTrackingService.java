package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import com.camel_hub.advertisement.campaign.delivery.CampaignOutboundPreparer;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailTrackingModels;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Final send-time rendering and production callback resolver. */
public final class CampaignTrackingService implements CampaignOutboundPreparer, CampaignCallbackNamespace {

	private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration OBSERVATION_TIMEOUT = Duration.ofMillis(500);
	private static final String PLACEHOLDER = "{{unsubscribe_url}}";
	private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String COMMON_TAIL = "[0-9]{1,19}\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}";
	private static final Pattern OPEN_TOKEN = Pattern.compile(
			"campaign-open:v1\\." + UUID_SHAPE + "\\." + COMMON_TAIL);
	private static final Pattern CLICK_TOKEN = Pattern.compile(
			"campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + COMMON_TAIL);
	private static final Pattern UNSUBSCRIBE_TOKEN = Pattern.compile(
			"campaign-unsubscribe:v1\\." + UUID_SHAPE + "\\." + COMMON_TAIL);
	private static final Pattern ANY_SIGNED_CAPABILITY = Pattern.compile(
			"(?:campaign-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + COMMON_TAIL
					+ "|campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + COMMON_TAIL
					+ "|v1\\." + UUID_SHAPE + "\\." + COMMON_TAIL
					+ "|v1c\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + COMMON_TAIL + ")");
	private static final Pattern CALLBACK_URL = Pattern.compile(
			"(?<![A-Za-z0-9._~:/?#@!$&*+,;=%-])"
					+ "https://(?:\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(?::[0-9]{1,5})?/(t/o|t/c|u)/"
					+ "([^\\s\\\"'<>\\)\\]\\}]+)");
	private final CampaignTrackingRepository repository;
	private final MailTrackingProperties properties;
	private final CampaignTrackingSigner signer;
	private final MailOpenClassifier classifier;
	private final CampaignLinkRewriter linkRewriter;
	private final Clock clock;
	private final TransactionalOperator transactions;
	private final Function<String, byte[]> fingerprintHasher;
	private final Pattern configuredCallbackPath;

	public CampaignTrackingService(
			CampaignTrackingRepository repository, MailTrackingProperties properties,
			CampaignTrackingSigner signer, MailOpenClassifier classifier, Clock clock,
			TransactionalOperator transactions
	) {
		this(repository, properties, signer, classifier, clock, transactions, signer::fingerprint);
	}

	public CampaignTrackingService(
			CampaignTrackingRepository repository, MailTrackingProperties properties,
			CampaignTrackingSigner signer, MailOpenClassifier classifier, Clock clock,
			TransactionalOperator transactions, Function<String, byte[]> fingerprintHasher
	) {
		this.repository = repository;
		this.properties = properties;
		this.signer = signer;
		this.classifier = classifier;
		this.linkRewriter = new CampaignLinkRewriter(properties.publicBaseUrl());
		this.clock = clock;
		this.transactions = transactions;
		this.fingerprintHasher = fingerprintHasher == null ? signer::fingerprint : fingerprintHasher;
		this.configuredCallbackPath = Pattern.compile(
				Pattern.quote(properties.publicBaseUrl()) + "/(?:t/o|t/c|u)/", Pattern.CASE_INSENSITIVE);
	}

	@Override
	public Mono<PreparedOutbound> prepare(CampaignDeliveryRepository.ProductionClaim claim) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			byte[] leaseHash = CampaignTrackingSigner.sha256Bytes(claim.leaseDigest());
			return repository.lockPreparation(claim, leaseHash, now)
					.switchIfEmpty(Mono.error(new IllegalStateException("Campaign preparation lease is no longer active")))
					.flatMap(state -> validateClaim(state, claim).then(Mono.defer(() ->
							isFrozen(state) ? reuseFrozen(claim, state, leaseHash, now)
									: prepareInitial(claim, state, leaseHash, now))))
					.as(transactions::transactional);
		});
	}

	@Override
	public Mono<Boolean> observeOpen(
			String token, HttpHeaders headers, AuthenticationRequestContext request
	) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			return signer.verifyOpen(token, now)
					.map(verified -> repository.observeOpen(verified, signer.digest(token),
							observation("OPEN", headers, request), now).as(transactions::transactional))
					.orElseGet(() -> Mono.just(false));
		}).timeout(RESOLUTION_TIMEOUT).onErrorReturn(false);
	}

	@Override
	public Mono<CampaignCallbackNamespace.ResolvedClick> click(
			String token, HttpHeaders headers, AuthenticationRequestContext request, boolean observe
	) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			Mono<CampaignTrackingRepository.ResolvedClick> resolution = signer.verifyClick(token, now)
					.map(verified -> repository.resolveClick(verified, signer.digest(token), now))
					.orElseGet(Mono::empty);
			return resolution.timeout(RESOLUTION_TIMEOUT)
					.filter(resolved -> linkRewriter.safeRedirectTarget(resolved.targetUrl()))
					.flatMap(resolved -> (observe
							? repository.observeClick(resolved, observation("CLICK", headers, request), now)
									.as(transactions::transactional).timeout(OBSERVATION_TIMEOUT).onErrorReturn(false)
							: Mono.just(true))
							.thenReturn(new CampaignCallbackNamespace.ResolvedClick(resolved.targetUrl())))
					.onErrorResume(ignored -> Mono.empty());
		});
	}

	@Override
	public Mono<Boolean> unsubscribe(String token, AuthenticationRequestContext request) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			return signer.verifyUnsubscribe(token, now)
					.map(verified -> Mono.defer(() -> repository.resolveUnsubscribe(
									verified, signer.digest(token), now)
							.flatMap(resolved -> repository.serializeUnsubscribe(resolved.emailHmac())
									.then(repository.lockUnsubscribe(
											verified, signer.digest(token), resolved.emailHmac(), now))
									.flatMap(locked -> repository.applyUnsubscribe(
											locked, signer.digest(token), requestFingerprint("UNSUBSCRIBE", request),
											now, request == null ? null : request.traceId())))
							.defaultIfEmpty(false)
							.as(transactions::transactional))
							.retryWhen(Retry.max(3).filter(this::transientFailure)))
					.orElseGet(() -> Mono.just(false));
		});
	}

	private Mono<Void> validateClaim(
			CampaignTrackingRepository.PreparationState state,
			CampaignDeliveryRepository.ProductionClaim claim
	) {
		boolean valid = state.recipientId().equals(claim.recipientId())
				&& state.campaignId().equals(claim.campaignId())
				&& state.templateVersionId().equals(claim.templateVersionId())
				&& state.attemptNumber() == claim.attemptNumber()
				&& state.trackingOpens() == claim.trackingOpensEnabled()
				&& state.trackingClicks() == claim.trackingClicksEnabled()
				&& state.unsubscribeEnabled() && claim.unsubscribeEnabled()
				&& state.subject() != null && !state.subject().isBlank()
				&& state.html() != null && !state.html().isBlank()
				&& state.text() != null && !state.text().isBlank()
				&& !state.subject().contains(PLACEHOLDER);
		return valid ? Mono.empty() : Mono.error(new IllegalArgumentException("Campaign content is not ready for delivery"));
	}

	private boolean isFrozen(CampaignTrackingRepository.PreparationState state) {
		boolean htmlPlaceholder = state.html().contains(PLACEHOLDER);
		boolean textPlaceholder = state.text().contains(PLACEHOLDER);
		if (htmlPlaceholder != textPlaceholder) {
			throw new IllegalArgumentException("Campaign unsubscribe rendering is inconsistent");
		}
		return !htmlPlaceholder;
	}

	private Mono<PreparedOutbound> prepareInitial(
			CampaignDeliveryRepository.ProductionClaim claim,
			CampaignTrackingRepository.PreparationState state,
			byte[] leaseHash, Instant now
	) {
		validateInitialDraft(state);
		Instant expiresAt = now.plus(properties.tokenTtl()).truncatedTo(ChronoUnit.SECONDS);
		String unsubscribeToken = signer.issueUnsubscribe(state.recipientId(), expiresAt);
		String unsubscribeUrl = properties.publicBaseUrl() + "/u/" + unsubscribeToken;
		String html = state.html().replace(PLACEHOLDER, unsubscribeUrl);
		String text = state.text().replace(PLACEHOLDER, unsubscribeUrl);
		validateInitialRenderedCapabilities(html, text, unsubscribeUrl, unsubscribeToken);
		List<CampaignLinkRewriter.EligibleLink> eligible = state.trackingClicks()
				? linkRewriter.eligibleLinks(html, unsubscribeUrl).stream()
						.sorted(Comparator.comparing(CampaignLinkRewriter.EligibleLink::targetUrl)).toList()
				: List.of();

		return Flux.fromIterable(eligible)
				.concatMap(link -> repository.ensureLink(
						state.campaignId(), state.templateVersionId(), link.targetUrl(), link.label(), now))
				.collectList()
				.flatMap(links -> {
					Map<String, String> callbacks = new LinkedHashMap<>();
					List<TokenDraft> clickTokens = new java.util.ArrayList<>();
					for (int index = 0; index < links.size(); index++) {
						CampaignTrackingRepository.PersistedLink persisted = links.get(index);
						String token = signer.issueClick(state.recipientId(), persisted.id(), expiresAt);
						callbacks.put(eligible.get(index).targetUrl(), token);
						clickTokens.add(new TokenDraft(persisted.id(), "CLICK", token));
					}
					String rewritten = state.trackingClicks() ? linkRewriter.rewrite(html, callbacks) : html;
					String openToken = state.trackingOpens() ? signer.issueOpen(state.recipientId(), expiresAt) : null;
					String finalHtml = openToken == null ? rewritten : appendPixel(rewritten, openToken);
					Mono<Void> tokens = repository.insertToken(
							state.recipientId(), null, "UNSUBSCRIBE", signer.digest(unsubscribeToken), expiresAt, now)
							.then(openToken == null ? Mono.empty() : repository.insertToken(
									state.recipientId(), null, "OPEN", signer.digest(openToken), expiresAt, now))
							.thenMany(Flux.fromIterable(clickTokens).concatMap(token -> repository.insertToken(
									state.recipientId(), token.linkId(), token.type(), signer.digest(token.value()),
									expiresAt, now))).then();
					return tokens.then(repository.persistPreparedBodies(
							claim, leaseHash, state.subject(), finalHtml, text, clock.instant()))
							.thenReturn(outbound(state.subject(), finalHtml, text, unsubscribeUrl));
				});
	}

	private Mono<PreparedOutbound> reuseFrozen(
			CampaignDeliveryRepository.ProductionClaim claim,
			CampaignTrackingRepository.PreparationState state, byte[] leaseHash, Instant now
	) {
		return Mono.defer(() -> {
			FrozenCapabilities capabilities = parseFrozen(state);
			return repository.frozenArtifacts(state.recipientId()).collectList()
					.flatMap(artifacts -> {
						FrozenValidation validation = validateFrozen(state, capabilities, artifacts);
						if (validation == null) return invalidFrozen();
						if (!validation.expiresAt().isAfter(state.deliveryLeaseExpiresAt())) {
							return rotationAllowed(state, claim)
									? rotateFrozen(claim, state, capabilities, artifacts.size(), leaseHash, now)
									: invalidFrozen();
						}
						return repository.persistPreparedBodies(
								claim, leaseHash, state.subject(), state.html(), state.text(), clock.instant())
								.thenReturn(outbound(state.subject(), state.html(), state.text(),
										capabilities.unsubscribeUrl()));
					});
		});
	}

	private boolean rotationAllowed(
			CampaignTrackingRepository.PreparationState state,
			CampaignDeliveryRepository.ProductionClaim claim
	) {
		Integer code = state.previousResponseCode();
		return claim.attemptNumber() > 1 && state.attemptNumber() == claim.attemptNumber()
				&& "TEMPORARY_FAILURE".equals(state.previousAttemptStatus())
				&& Boolean.TRUE.equals(state.previousAttemptRetryable())
				&& "SMTP_REJECTED".equals(state.previousFailureCategory())
				&& code != null && code >= 400 && code <= 499;
	}

	private Mono<PreparedOutbound> rotateFrozen(
			CampaignDeliveryRepository.ProductionClaim claim,
			CampaignTrackingRepository.PreparationState state, FrozenCapabilities capabilities,
			int oldTokenCount, byte[] leaseHash, Instant now
	) {
		Instant expiresAt = now.plus(properties.tokenTtl()).truncatedTo(ChronoUnit.SECONDS);
		Map<String, String> replacements = new LinkedHashMap<>();
		List<TokenDraft> tokens = new ArrayList<>();

		String unsubscribe = signer.issueUnsubscribe(state.recipientId(), expiresAt);
		replacements.put(capabilities.unsubscribeToken(), unsubscribe);
		tokens.add(new TokenDraft(null, "UNSUBSCRIBE", unsubscribe));
		for (String old : capabilities.openTokens()) {
			String replacement = signer.issueOpen(state.recipientId(), expiresAt);
			replacements.put(old, replacement);
			tokens.add(new TokenDraft(null, "OPEN", replacement));
		}
		for (String old : capabilities.clickTokens()) {
			CampaignTrackingSigner.VerifiedClick verified = signer.verifyClickIncludingExpired(old)
					.orElseThrow(this::frozenError);
			String replacement = signer.issueClick(state.recipientId(), verified.linkId(), expiresAt);
			replacements.put(old, replacement);
			tokens.add(new TokenDraft(verified.linkId(), "CLICK", replacement));
		}

		String html = replaceCapabilities(state.html(), capabilities.htmlReferences(), replacements);
		String text = replaceCapabilities(state.text(), capabilities.textReferences(), replacements);
		CampaignTrackingRepository.PreparationState rotated = new CampaignTrackingRepository.PreparationState(
				state.recipientId(), state.campaignId(), state.templateVersionId(), state.subject(), html, text,
				state.trackingOpens(), state.trackingClicks(), state.unsubscribeEnabled(), state.attemptNumber(),
				state.deliveryLeaseExpiresAt(), state.previousAttemptStatus(), state.previousAttemptRetryable(), state.previousResponseCode(),
				state.previousFailureCategory());
		FrozenCapabilities newCapabilities = parseFrozen(rotated);
		if (newCapabilities.openTokens().size() != capabilities.openTokens().size()
				|| newCapabilities.clickTokens().size() != capabilities.clickTokens().size()) return invalidFrozen();

		return repository.deleteFrozenTokens(state.recipientId(), oldTokenCount)
				.thenMany(Flux.fromIterable(tokens).concatMap(token -> repository.insertToken(
						state.recipientId(), token.linkId(), token.type(), signer.digest(token.value()),
						expiresAt, now))).then()
				.then(repository.persistPreparedBodies(
						claim, leaseHash, state.subject(), html, text, clock.instant()))
				.thenReturn(outbound(state.subject(), html, text, newCapabilities.unsubscribeUrl()));
	}

	private String replaceCapabilities(
			String content, List<CallbackReference> references, Map<String, String> replacements
	) {
		String replaced = content;
		for (CallbackReference reference : references) {
			String replacement = replacements.get(reference.token());
			if (replacement == null) throw frozenError();
			String replacementUrl = properties.publicBaseUrl() + "/" + reference.kind().path() + "/" + replacement;
			replaced = replaced.replace(reference.url(), replacementUrl);
		}
		return replaced;
	}

	private PreparedOutbound outbound(String subject, String html, String text, String unsubscribeUrl) {
		return new PreparedOutbound(subject, html, text, Map.of(
				"List-Unsubscribe", "<" + unsubscribeUrl + ">",
				"List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
	}

	private String appendPixel(String html, String token) {
		return html + "<img src=\"" + properties.publicBaseUrl() + "/t/o/" + token
				+ "\" width=\"1\" height=\"1\" alt=\"\" style=\"width:1px;height:1px;border:0\""
				+ " referrerpolicy=\"no-referrer\">";
	}

	private FrozenCapabilities parseFrozen(CampaignTrackingRepository.PreparationState state) {
		if (containsPreloadedCapability(decodeRepeatedly(state.subject()))) throw frozenError();
		validateHtmlCapabilityAttributes(state.html());
		List<CallbackReference> html = callbackReferences(state.html(), true);
		List<CallbackReference> text = callbackReferences(state.text(), true);
		validateDecodedCapabilityCount(state.html(), true, html.size());
		validateDecodedCapabilityCount(state.text(), false, text.size());
		List<CallbackReference> unsubscribeHtml = ofKind(html, CallbackKind.UNSUBSCRIBE);
		List<CallbackReference> unsubscribeText = ofKind(text, CallbackKind.UNSUBSCRIBE);
		Set<String> unsubscribeTokens = tokens(unsubscribeHtml, unsubscribeText);
		Set<String> unsubscribeUrls = urls(unsubscribeHtml, unsubscribeText);
		List<CallbackReference> opens = ofKind(html, CallbackKind.OPEN);
		List<CallbackReference> clicks = ofKind(html, CallbackKind.CLICK);
		if (unsubscribeHtml.isEmpty() || unsubscribeText.isEmpty()
				|| unsubscribeTokens.size() != 1 || unsubscribeUrls.size() != 1
				|| !ofKind(text, CallbackKind.OPEN).isEmpty() || !ofKind(text, CallbackKind.CLICK).isEmpty()
				|| state.trackingOpens() != (opens.size() == 1)
				|| !state.trackingClicks() && !clicks.isEmpty()
				|| countClickAnchors(state.html()) != clicks.size()
				|| countOpenImages(state.html()) != opens.size()) {
			throw frozenError();
		}
		Set<String> origins = new HashSet<>();
		for (CallbackReference reference : concat(html, text)) origins.add(reference.origin());
		if (origins.size() != 1 || !origins.contains(properties.publicBaseUrl())) throw frozenError();
		return new FrozenCapabilities(
				unsubscribeUrls.iterator().next(), unsubscribeTokens.iterator().next(),
				tokens(opens), tokens(clicks), html, text);
	}

	private FrozenValidation validateFrozen(
			CampaignTrackingRepository.PreparationState state, FrozenCapabilities capabilities,
			List<CampaignTrackingRepository.FrozenArtifact> artifacts
	) {
		if (artifacts.size() != 1 + capabilities.openTokens().size() + capabilities.clickTokens().size()) {
			return null;
		}
		Set<Instant> expirations = new HashSet<>();
		var unsubscribe = signer.verifyUnsubscribeIncludingExpired(capabilities.unsubscribeToken());
		if (unsubscribe.isEmpty() || !unsubscribe.orElseThrow().recipientId().equals(state.recipientId())
				|| matchingArtifacts(artifacts, "UNSUBSCRIBE", null, signer.digest(capabilities.unsubscribeToken()),
				unsubscribe.orElseThrow().expiresAt()) != 1) return null;
		expirations.add(unsubscribe.orElseThrow().expiresAt());
		for (String token : capabilities.openTokens()) {
			var verified = signer.verifyOpenIncludingExpired(token);
			if (verified.isEmpty() || !verified.orElseThrow().recipientId().equals(state.recipientId())
					|| matchingArtifacts(artifacts, "OPEN", null, signer.digest(token),
					verified.orElseThrow().expiresAt()) != 1) return null;
			expirations.add(verified.orElseThrow().expiresAt());
		}
		for (String token : capabilities.clickTokens()) {
			var verified = signer.verifyClickIncludingExpired(token);
			if (verified.isEmpty() || !verified.orElseThrow().recipientId().equals(state.recipientId())
					|| matchingArtifacts(artifacts, "CLICK", verified.orElseThrow().linkId(), signer.digest(token),
					verified.orElseThrow().expiresAt()) != 1) return null;
			expirations.add(verified.orElseThrow().expiresAt());
		}
		if (expirations.size() != 1 || !artifacts.stream().allMatch(artifact -> switch (artifact.type()) {
			case "UNSUBSCRIBE" -> artifact.linkId() == null;
			case "OPEN" -> state.trackingOpens() && artifact.linkId() == null;
			case "CLICK" -> state.trackingClicks() && artifact.linkId() != null
					&& linkRewriter.safeRedirectTarget(artifact.targetUrl());
			default -> false;
		})) return null;
		return new FrozenValidation(expirations.iterator().next());
	}

	private int matchingArtifacts(
			List<CampaignTrackingRepository.FrozenArtifact> artifacts, String type, UUID linkId,
			byte[] digest, Instant expiresAt
	) {
		return (int) artifacts.stream().filter(artifact -> artifact.type().equals(type)
				&& java.util.Objects.equals(artifact.linkId(), linkId)
				&& expiresAt.equals(artifact.expiresAt())
				&& MessageDigest.isEqual(digest, artifact.tokenHash())).count();
	}

	private void validateHtmlCapabilityAttributes(String html) {
		for (var element : Jsoup.parseBodyFragment(html == null ? "" : html).getAllElements()) {
			for (var attribute : element.attributes()) {
				String value = attribute.getValue();
				if (!containsPreloadedCapability(decodeRepeatedly(value))) continue;
				List<CallbackReference> references = callbackReferences(value, false);
				if (references.size() != 1 || !references.getFirst().url().equals(value)) throw frozenError();
			}
		}
	}

	private void validateInitialDraft(CampaignTrackingRepository.PreparationState state) {
		validateHtmlPlaceholderContexts(state.html());
		validateStandalonePlaceholders(state.text());
		if (containsPreloadedCapability(decodeRepeatedly(state.subject()))
				|| containsPreloadedCapability(decodeHtmlForInspection(state.html()))
				|| containsPreloadedCapability(decodeRepeatedly(state.text()))) {
			throw new IllegalArgumentException("Campaign draft contains a preloaded callback capability");
		}
	}

	private void validateDecodedCapabilityCount(String content, boolean html, int expected) {
		String decoded = html ? decodeHtmlForInspection(content) : decodeRepeatedly(content);
		if (signedCapabilityCount(decoded) != expected
				|| configuredCallbackPath.matcher(decoded).results().count() != expected) throw frozenError();
	}

	private void validateInitialRenderedCapabilities(
			String html, String text, String unsubscribeUrl, String unsubscribeToken
	) {
		validateHtmlCapabilityAttributes(html);
		List<CallbackReference> htmlReferences = callbackReferences(html, true);
		List<CallbackReference> textReferences = callbackReferences(text, true);
		validateDecodedCapabilityCount(html, true, htmlReferences.size());
		validateDecodedCapabilityCount(text, false, textReferences.size());
		if (htmlReferences.isEmpty() || textReferences.isEmpty()) throw frozenError();
		for (CallbackReference reference : concat(htmlReferences, textReferences)) {
			if (reference.kind() != CallbackKind.UNSUBSCRIBE
					|| !reference.url().equals(unsubscribeUrl) || !reference.token().equals(unsubscribeToken)) {
				throw frozenError();
			}
		}
	}

	private void validateHtmlPlaceholderContexts(String html) {
		var document = Jsoup.parseBodyFragment(html == null ? "" : html);
		int accepted = 0;
		for (var element : document.getAllElements()) {
			for (var attribute : element.attributes()) {
				String value = attribute.getValue();
				if (!value.contains(PLACEHOLDER)) continue;
				if (!value.equals(PLACEHOLDER)) throw frozenError();
				accepted++;
			}
		}
		accepted += validatePlaceholderTextNodes(document.body());
		if (accepted != occurrences(html, PLACEHOLDER)) throw frozenError();
	}

	private int validatePlaceholderTextNodes(Node node) {
		int accepted = node instanceof TextNode text ? validateStandalonePlaceholders(text.getWholeText()) : 0;
		for (Node child : node.childNodes()) accepted += validatePlaceholderTextNodes(child);
		return accepted;
	}

	private int validateStandalonePlaceholders(String value) {
		int accepted = 0;
		for (int offset = 0; value != null && (offset = value.indexOf(PLACEHOLDER, offset)) >= 0;
				offset += PLACEHOLDER.length()) {
			int end = offset + PLACEHOLDER.length();
			boolean safeLeft = offset == 0 || standaloneLeft(value, offset);
			boolean safeRight = end == value.length() || standaloneRight(value.charAt(end));
			if (!safeLeft || !safeRight) throw frozenError();
			accepted++;
		}
		return accepted;
	}

	private boolean standaloneLeft(String value, int placeholderOffset) {
		char boundary = value.charAt(placeholderOffset - 1);
		if (Character.isWhitespace(boundary)) return true;
		if ("([{<\"'".indexOf(boundary) < 0) return false;
		return placeholderOffset == 1 || Character.isWhitespace(value.charAt(placeholderOffset - 2));
	}

	private boolean standaloneRight(char value) {
		return Character.isWhitespace(value) || ".,;:!?)]}>\"'".indexOf(value) >= 0;
	}

	private String decodeHtmlForInspection(String html) {
		String normalized = Jsoup.parseBodyFragment(html == null ? "" : html).body().html();
		return decodeRepeatedly(normalized);
	}

	private String decodeRepeatedly(String value) {
		String decoded = value == null ? "" : value;
		try {
			for (int round = 0; round < 5; round++) {
				String next = URLDecoder.decode(escapeInvalidPercents(decoded).replace("+", "%2B"),
						StandardCharsets.UTF_8);
				if (next.equals(decoded)) return decoded;
				decoded = next;
			}
			if (containsValidPercentEscape(decoded)) throw frozenError();
			return decoded;
		}
		catch (IllegalArgumentException exception) {
			throw frozenError();
		}
	}

	private String escapeInvalidPercents(String value) {
		StringBuilder safe = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (current == '%' && (index + 2 >= value.length()
					|| Character.digit(value.charAt(index + 1), 16) < 0
					|| Character.digit(value.charAt(index + 2), 16) < 0)) {
				safe.append("%25");
			}
			else safe.append(current);
		}
		return safe.toString();
	}

	private boolean containsValidPercentEscape(String value) {
		for (int index = 0; index + 2 < value.length(); index++) {
			if (value.charAt(index) == '%' && Character.digit(value.charAt(index + 1), 16) >= 0
					&& Character.digit(value.charAt(index + 2), 16) >= 0) return true;
		}
		return false;
	}

	private boolean containsPreloadedCapability(String value) {
		return signedCapabilityCount(value) != 0 || configuredCallbackPath.matcher(value).find();
	}

	private long signedCapabilityCount(String value) {
		return ANY_SIGNED_CAPABILITY.matcher(value).results().count();
	}

	private List<CallbackReference> callbackReferences(String content, boolean allowSentencePunctuation) {
		List<CallbackReference> result = new ArrayList<>();
		var matcher = CALLBACK_URL.matcher(content == null ? "" : content);
		while (matcher.find()) {
			CallbackKind kind = CallbackKind.fromPath(matcher.group(1));
			String rawToken = matcher.group(2);
			String token = allowSentencePunctuation ? trimSentencePunctuation(rawToken) : rawToken;
			boolean campaignCapability = OPEN_TOKEN.matcher(token).matches()
					|| CLICK_TOKEN.matcher(token).matches() || UNSUBSCRIBE_TOKEN.matcher(token).matches();
			if (!campaignCapability) continue;
			if (!kind.pattern().matcher(token).matches()) throw frozenError();
			String rawUrl = matcher.group().substring(0, matcher.group().length() - rawToken.length()) + token;
			try {
				URI uri = URI.create(rawUrl);
				if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
						|| uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0
						|| uri.getPort() > 65_535 || !uri.getRawPath().equals("/" + kind.path() + "/" + token)) {
					throw frozenError();
				}
				result.add(new CallbackReference(kind, rawUrl, token, origin(uri)));
			}
			catch (IllegalArgumentException exception) {
				throw frozenError();
			}
		}
		return List.copyOf(result);
	}

	private String origin(URI uri) {
		try {
			return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null)
					.toString();
		}
		catch (URISyntaxException exception) {
			throw frozenError();
		}
	}

	private String trimSentencePunctuation(String value) {
		int end = value.length();
		while (end > 0 && ".,;:!?".indexOf(value.charAt(end - 1)) >= 0) end--;
		return value.substring(0, end);
	}

	private long occurrences(String value, String needle) {
		long count = 0;
		for (int offset = 0; value != null && (offset = value.indexOf(needle, offset)) >= 0;
				offset += needle.length()) count++;
		return count;
	}

	private int countClickAnchors(String html) {
		return Jsoup.parseBodyFragment(html).select("a[href]").stream()
				.mapToInt(anchor -> (int) callbackReferences(anchor.attr("href"), false).stream()
						.filter(reference -> reference.kind() == CallbackKind.CLICK).count())
				.sum();
	}

	private int countOpenImages(String html) {
		return Jsoup.parseBodyFragment(html).select("img[src]").stream()
				.mapToInt(image -> (int) callbackReferences(image.attr("src"), false).stream()
						.filter(reference -> reference.kind() == CallbackKind.OPEN).count())
				.sum();
	}

	private List<CallbackReference> ofKind(List<CallbackReference> source, CallbackKind kind) {
		return source.stream().filter(reference -> reference.kind() == kind).toList();
	}

	@SafeVarargs
	private final Set<String> tokens(List<CallbackReference>... sources) {
		Set<String> values = new HashSet<>();
		for (List<CallbackReference> source : sources) source.forEach(reference -> values.add(reference.token()));
		return Set.copyOf(values);
	}

	@SafeVarargs
	private final Set<String> urls(List<CallbackReference>... sources) {
		Set<String> values = new HashSet<>();
		for (List<CallbackReference> source : sources) source.forEach(reference -> values.add(reference.url()));
		return Set.copyOf(values);
	}

	private List<CallbackReference> concat(List<CallbackReference> first, List<CallbackReference> second) {
		List<CallbackReference> values = new ArrayList<>(first);
		values.addAll(second);
		return values;
	}

	private <T> Mono<T> invalidFrozen() {
		return Mono.error(frozenError());
	}

	private IllegalArgumentException frozenError() {
		return new IllegalArgumentException("Frozen campaign capabilities are invalid");
	}

	private boolean transientFailure(Throwable failure) {
		for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
			if (candidate instanceof TransientDataAccessException) return true;
		}
		return false;
	}

	private CampaignTrackingRepository.Observation observation(
			String eventType, HttpHeaders headers, AuthenticationRequestContext request
	) {
		MailOpenClassifier.Observation base = classifier.classify(headers == null ? HttpHeaders.EMPTY : headers);
		String userAgent = bounded(headers == null ? null : headers.getFirst(HttpHeaders.USER_AGENT), 512);
		String classification = classification(eventType, base.classification(), userAgent);
		String ip = request == null || request.ipAddress() == null ? "unknown" : request.ipAddress();
		byte[] fingerprint = fingerprintHasher.apply("campaign-callback:v1\n" + eventType + "\n" + ip
				+ "\n" + userAgent + "\n" + classification);
		return new CampaignTrackingRepository.Observation(classification, base.reason(), fingerprint);
	}

	private byte[] requestFingerprint(String eventType, AuthenticationRequestContext request) {
		String ip = request == null || request.ipAddress() == null ? "unknown" : request.ipAddress();
		String userAgent = bounded(request == null ? null : request.userAgentSummary(), 255);
		return fingerprintHasher.apply("campaign-callback:v1\n" + eventType + "\n" + ip + "\n" + userAgent);
	}

	private String classification(
			String eventType, MailTrackingModels.Classification classification, String userAgent
	) {
		return switch (classification) {
			case PREFETCH, IMAGE_PROXY -> "PREFETCH";
			case BOT -> securityScanner(userAgent) ? "SECURITY_SCANNER" : "BOT";
			case UNCLASSIFIED -> eventType.equals("CLICK") ? "LIKELY_HUMAN" : "UNCLASSIFIED";
		};
	}

	private boolean securityScanner(String userAgent) {
		return userAgent.contains("proofpoint") || userAgent.contains("barracuda")
				|| userAgent.contains("mimecast") || userAgent.contains("security scanner");
	}

	private String bounded(String value, int maximum) {
		if (value == null) return "";
		String safe = value.substring(0, Math.min(maximum, value.length()))
				.replaceAll("[\\p{Cntrl}]", " ").strip().toLowerCase(Locale.ROOT);
		return safe;
	}

	private record TokenDraft(UUID linkId, String type, String value) { }

	private record FrozenCapabilities(
			String unsubscribeUrl, String unsubscribeToken, Set<String> openTokens, Set<String> clickTokens,
			List<CallbackReference> htmlReferences, List<CallbackReference> textReferences
	) { }

	private record FrozenValidation(Instant expiresAt) { }

	private record CallbackReference(CallbackKind kind, String url, String token, String origin) { }

	private enum CallbackKind {
		OPEN("t/o", OPEN_TOKEN), CLICK("t/c", CLICK_TOKEN), UNSUBSCRIBE("u", UNSUBSCRIBE_TOKEN);

		private final String path;
		private final Pattern pattern;

		CallbackKind(String path, Pattern pattern) {
			this.path = path;
			this.pattern = pattern;
		}

		String path() { return path; }
		Pattern pattern() { return pattern; }

		static CallbackKind fromPath(String path) {
			return switch (path) {
				case "t/o" -> OPEN;
				case "t/c" -> CLICK;
				case "u" -> UNSUBSCRIBE;
				default -> throw new IllegalArgumentException("Unsupported campaign callback path");
			};
		}
	}
}
