package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import com.camel_hub.advertisement.campaign.tracking.CampaignCallbackNamespace;
import com.camel_hub.advertisement.campaign.tracking.CampaignLinkRewriter;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailTrackingModels;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.jsoup.Jsoup;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Final safety rendering plus an isolated callback namespace backed only by safety tables. */
public final class CampaignSafetyTrackingService
		implements CampaignSafetyOutboundPreparer, CampaignCallbackNamespace {
	private static final String PLACEHOLDER = "{{unsubscribe_url}}";
	private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String TAIL = "[0-9]{1,19}\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}";
	private static final Pattern OPEN_TOKEN = Pattern.compile(
			"campaign-safety-open:v1\\." + UUID_SHAPE + "\\." + TAIL);
	private static final Pattern CLICK_TOKEN = Pattern.compile(
			"campaign-safety-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL);
	private static final Pattern UNSUBSCRIBE_TOKEN = Pattern.compile(
			"campaign-safety-unsubscribe:v1\\." + UUID_SHAPE + "\\." + TAIL);
	private static final Pattern ANY_OTHER_CAPABILITY = Pattern.compile(
			"(?:campaign-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|(?<![A-Za-z0-9:_-])v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|(?<![A-Za-z0-9:_-])v1c\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL + ")");
	private static final Pattern ANY_SIGNED_CAPABILITY = Pattern.compile(
			"(?:campaign-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-safety-(?:open|unsubscribe):v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|campaign-safety-click:v1\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL
					+ "|(?<![A-Za-z0-9:_-])v1\\." + UUID_SHAPE + "\\." + TAIL
					+ "|(?<![A-Za-z0-9:_-])v1c\\." + UUID_SHAPE + "\\." + UUID_SHAPE + "\\." + TAIL + ")");
	private static final Pattern CALLBACK_URL = Pattern.compile(
			"(?<![A-Za-z0-9._~:/?#@!$&*+,;=%-])"
					+ "https://(?:\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(?::[0-9]{1,5})?/(t/o|t/c|u)/"
					+ "([^\\s\\\"'<>\\)\\]\\}]+)");
	private static final Pattern EMAIL = Pattern.compile(
			"(?i)(?<![A-Z0-9.!#$%&'*+/=?^_`{|}~-])[A-Z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}"
					+ "@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9-])");
	private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration OBSERVATION_TIMEOUT = Duration.ofMillis(500);

	private final CampaignSafetyRepository repository;
	private final CampaignSafetyRuntimePolicy policy;
	private final MailTrackingProperties tracking;
	private final CampaignSafetySigner signer;
	private final MailOpenClassifier classifier;
	private final CampaignLinkRewriter linkRewriter;
	private final Clock clock;
	private final TransactionalOperator transactions;
	private final CampaignSafetyContentPolicy contentPolicy = new CampaignSafetyContentPolicy();

	public CampaignSafetyTrackingService(
			CampaignSafetyRepository repository, CampaignSafetyRuntimePolicy policy,
			MailTrackingProperties tracking, CampaignSafetySigner signer,
			MailOpenClassifier classifier, Clock clock, TransactionalOperator transactions
	) {
		this.repository = repository;
		this.policy = policy;
		this.tracking = tracking;
		this.signer = signer;
		this.classifier = classifier;
		this.linkRewriter = new CampaignLinkRewriter(tracking.publicBaseUrl());
		this.clock = clock;
		this.transactions = transactions;
	}

	@Override
	public Mono<PreparedSafetyOutbound> prepare(CampaignDeliveryRepository.SafetyClaim claim) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			CampaignSafetyRuntimePolicy.Destination destination = policy.requireMatching(claim.destinationHmac());
			byte[] leaseHash = sha256(claim.leaseDigest());
			return repository.lockPreparation(claim, leaseHash, now)
					.switchIfEmpty(Mono.error(new IllegalStateException(
							"Campaign safety preparation lease is no longer active")))
					.flatMap(state -> {
						validateState(claim, state, destination);
						boolean htmlPlaceholder = state.html().contains(PLACEHOLDER);
						boolean textPlaceholder = state.text().contains(PLACEHOLDER);
						if (htmlPlaceholder != textPlaceholder) return invalid();
						return htmlPlaceholder
								? prepareInitial(claim, state, destination, leaseHash, now)
								: reuseFrozen(claim, state, destination, leaseHash, now);
					})
					.as(transactions::transactional);
		});
	}

	@Override
	public PreparedSafetyOutbound validateForSend(
			CampaignDeliveryRepository.SafetyClaim claim, PreparedSafetyOutbound prepared
	) {
		CampaignSafetyRuntimePolicy.Destination destination = policy.requireMatching(claim.destinationHmac());
		if (!destination.address().equals(prepared.recipient())) throw invalidContent();
		validateFinal(claim, prepared);
		return prepared;
	}

	private Mono<PreparedSafetyOutbound> prepareInitial(
			CampaignDeliveryRepository.SafetyClaim claim, CampaignSafetyRepository.PreparationState state,
			CampaignSafetyRuntimePolicy.Destination destination, byte[] leaseHash, Instant now
	) {
		validateSource(state);
		Instant expiresAt = now.plus(tracking.tokenTtl()).truncatedTo(ChronoUnit.SECONDS);
		if (!expiresAt.isAfter(state.leaseExpiresAt())) return invalid();

		UUID unsubscribeId = UUID.randomUUID();
		String unsubscribeToken = signer.issueUnsubscribe(state.messageId(), expiresAt);
		String unsubscribeUrl = tracking.publicBaseUrl() + "/u/" + unsubscribeToken;
		String html = state.html().replace(PLACEHOLDER, unsubscribeUrl);
		String text = state.text().replace(PLACEHOLDER, unsubscribeUrl);
		List<CampaignLinkRewriter.EligibleLink> eligible = state.trackingClicks()
				? linkRewriter.eligibleLinks(html, unsubscribeUrl).stream()
						.sorted(Comparator.comparing(CampaignLinkRewriter.EligibleLink::targetUrl)).toList()
				: List.of();
		Map<String, String> clickTokens = new LinkedHashMap<>();
		List<LinkDraft> links = new ArrayList<>();
		for (CampaignLinkRewriter.EligibleLink item : eligible) {
			UUID linkId = UUID.randomUUID();
			String token = signer.issueClick(state.messageId(), linkId, expiresAt);
			clickTokens.put(item.targetUrl(), token);
			links.add(new LinkDraft(linkId, "CLICK", item.targetUrl(), sha256(item.targetUrl()), token));
		}
		String rewritten = state.trackingClicks() ? linkRewriter.rewrite(html, clickTokens) : html;
		String openToken = state.trackingOpens() ? signer.issueOpen(state.messageId(), expiresAt) : null;
		String finalHtml = openToken == null ? rewritten : appendPixel(rewritten, openToken);
		links.add(new LinkDraft(unsubscribeId, "UNSUBSCRIBE", null, null, unsubscribeToken));
		if (openToken != null) links.add(new LinkDraft(UUID.randomUUID(), "OPEN", null, null, openToken));

		PreparedSafetyOutbound outbound = outbound(destination.address(), state.subject(), finalHtml, text, unsubscribeUrl);
		validateFinal(claim, outbound);
		return Flux.fromIterable(links).concatMap(link -> repository.insertLink(
				link.id(), state.messageId(), link.type(), link.target(), link.targetHash(),
				signer.digest(link.token()), expiresAt, now)).then()
				.then(repository.persistPreparedBodies(
						claim, leaseHash, state.subject(), finalHtml, text, clock.instant()))
				.thenReturn(outbound);
	}

	private Mono<PreparedSafetyOutbound> reuseFrozen(
			CampaignDeliveryRepository.SafetyClaim claim, CampaignSafetyRepository.PreparationState state,
			CampaignSafetyRuntimePolicy.Destination destination, byte[] leaseHash, Instant now
	) {
		return repository.artifacts(state.messageId()).collectList().flatMap(artifacts -> {
			FrozenValidation validation = validateFrozen(state, artifacts);
			if (!validation.expiresAt().isAfter(state.leaseExpiresAt())) {
				return rotationAllowed(state, claim)
						? rotateFrozen(claim, state, destination, validation.capabilities(),
								artifacts, leaseHash, now)
						: invalid();
			}
			PreparedSafetyOutbound outbound = outbound(
					destination.address(), state.subject(), state.html(), state.text(),
					validation.capabilities().unsubscribeHtml().url());
			validateFinal(claim, outbound);
			return repository.persistPreparedBodies(
					claim, leaseHash, state.subject(), state.html(), state.text(), clock.instant())
					.thenReturn(outbound);
		});
	}

	private FrozenValidation validateFrozen(
			CampaignSafetyRepository.PreparationState state,
			List<CampaignSafetyRepository.TrackingArtifact> artifacts
	) {
		FrozenCapabilities capabilities = parseCapabilities(
				state.subject(), state.html(), state.text(), state.messageId(),
				state.trackingOpens(), state.trackingClicks(), null);
		// HTML owns one reference per digest row; repeated anchors to one target share one click token.
		List<CallbackReference> references = uniqueArtifactReferences(capabilities.htmlReferences());
		if (artifacts.size() != references.size()) throw invalidContent();
		Set<Instant> expirations = new java.util.HashSet<>();
		for (CallbackReference reference : references) {
			VerifiedCapability verified = verify(reference, state.messageId(), null);
			CampaignSafetyRepository.TrackingArtifact artifact = artifacts.stream()
					.filter(candidate -> candidate.type().equals(reference.kind().name())
							&& (verified.linkId() == null || candidate.id().equals(verified.linkId()))
							&& candidate.expiresAt().equals(verified.expiresAt())
							&& MessageDigest.isEqual(candidate.tokenHash(), signer.digest(reference.token())))
					.findFirst().orElseThrow(this::invalidContent);
			if (reference.kind() == CallbackKind.CLICK) {
				if (artifact.targetUrl() == null || artifact.targetHash() == null
						|| !MessageDigest.isEqual(artifact.targetHash(), sha256(artifact.targetUrl()))
						|| !linkRewriter.safeRedirectTarget(artifact.targetUrl())) throw invalidContent();
			}
			else if (artifact.targetUrl() != null || artifact.targetHash() != null) {
				throw invalidContent();
			}
			expirations.add(verified.expiresAt());
		}
		if (expirations.size() != 1) throw invalidContent();
		return new FrozenValidation(capabilities, expirations.iterator().next());
	}

	private boolean rotationAllowed(
			CampaignSafetyRepository.PreparationState state,
			CampaignDeliveryRepository.SafetyClaim claim
	) {
		Integer code = state.previousResponseCode();
		return claim.attemptNumber() > 1 && state.attemptNumber() == claim.attemptNumber()
				&& "TEMPORARY_FAILURE".equals(state.previousAttemptStatus())
				&& Boolean.TRUE.equals(state.previousAttemptRetryable())
				&& "SMTP_REJECTED".equals(state.previousFailureCategory())
				&& code != null && code >= 400 && code <= 499;
	}

	private Mono<PreparedSafetyOutbound> rotateFrozen(
			CampaignDeliveryRepository.SafetyClaim claim,
			CampaignSafetyRepository.PreparationState state,
			CampaignSafetyRuntimePolicy.Destination destination,
			FrozenCapabilities capabilities,
			List<CampaignSafetyRepository.TrackingArtifact> artifacts,
			byte[] leaseHash, Instant now
	) {
		Instant expiresAt = now.plus(tracking.tokenTtl()).truncatedTo(ChronoUnit.SECONDS);
		if (!expiresAt.isAfter(state.leaseExpiresAt())) return invalid();
		Map<String, String> replacements = new LinkedHashMap<>();
		List<RotationDraft> rotated = new ArrayList<>();
		for (CallbackReference reference : uniqueArtifactReferences(capabilities.htmlReferences())) {
			VerifiedCapability verified = verify(reference, state.messageId(), null);
			CampaignSafetyRepository.TrackingArtifact artifact = matchingArtifact(
					artifacts, reference, verified);
			String token = switch (reference.kind()) {
				case OPEN -> signer.issueOpen(state.messageId(), expiresAt);
				case CLICK -> signer.issueClick(state.messageId(), artifact.id(), expiresAt);
				case UNSUBSCRIBE -> signer.issueUnsubscribe(state.messageId(), expiresAt);
			};
			replacements.put(reference.token(), token);
			rotated.add(new RotationDraft(artifact, token));
		}
		String html = replaceCapabilities(state.html(), capabilities.htmlReferences(), replacements);
		String text = replaceCapabilities(state.text(), capabilities.textReferences(), replacements);
		String unsubscribeToken = replacements.get(capabilities.unsubscribeHtml().token());
		if (unsubscribeToken == null) return invalid();
		String unsubscribeUrl = tracking.publicBaseUrl() + "/u/" + unsubscribeToken;
		PreparedSafetyOutbound outbound = outbound(
				destination.address(), state.subject(), html, text, unsubscribeUrl);
		validateFinal(claim, outbound);

		return Flux.fromIterable(rotated).concatMap(link -> repository.rotateFrozenLink(
					state.messageId(), link.expected(), signer.digest(link.token()), expiresAt)).then()
				.then(repository.persistPreparedBodies(
						claim, leaseHash, state.subject(), html, text, clock.instant()))
				.thenReturn(outbound);
	}

	private CampaignSafetyRepository.TrackingArtifact matchingArtifact(
			List<CampaignSafetyRepository.TrackingArtifact> artifacts,
			CallbackReference reference, VerifiedCapability verified
	) {
		List<CampaignSafetyRepository.TrackingArtifact> matches = artifacts.stream()
				.filter(candidate -> candidate.type().equals(reference.kind().name())
						&& (verified.linkId() == null || candidate.id().equals(verified.linkId()))
						&& candidate.expiresAt().equals(verified.expiresAt())
						&& MessageDigest.isEqual(candidate.tokenHash(), signer.digest(reference.token())))
				.toList();
		if (matches.size() != 1) throw invalidContent();
		return matches.getFirst();
	}

	private String replaceCapabilities(
			String content, List<CallbackReference> references, Map<String, String> replacements
	) {
		String replaced = content;
		for (CallbackReference reference : references) {
			String token = replacements.get(reference.token());
			if (token == null) throw invalidContent();
			String replacement = tracking.publicBaseUrl() + "/" + reference.kind().path() + "/" + token;
			replaced = replaced.replace(reference.url(), replacement);
		}
		return replaced;
	}

	@Override
	public Mono<Boolean> observeOpen(
			String token, HttpHeaders headers, AuthenticationRequestContext request
	) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			return signer.verifyOpen(token, now)
					.map(verified -> repository.resolveCallback(signer.digest(token), "OPEN", now)
							.filter(callback -> callback.messageId().equals(verified.messageId()))
							.flatMap(callback -> repository.observeCallback(
									callback, "OPEN", observation("OPEN", headers, request), now)))
					.orElseGet(() -> Mono.just(false));
		}).timeout(RESOLUTION_TIMEOUT).onErrorReturn(false);
	}

	@Override
	public Mono<CampaignCallbackNamespace.ResolvedClick> click(
			String token, HttpHeaders headers, AuthenticationRequestContext request, boolean observe
	) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			return signer.verifyClick(token, now)
					.map(verified -> repository.resolveCallback(signer.digest(token), "CLICK", now)
							.filter(callback -> callback.messageId().equals(verified.messageId())
									&& callback.linkId().equals(verified.linkId())
									&& linkRewriter.safeRedirectTarget(callback.targetUrl()))
							.flatMap(callback -> (observe
									? repository.observeCallback(callback, "CLICK",
											observation("CLICK", headers, request), now)
											.timeout(OBSERVATION_TIMEOUT).onErrorReturn(false)
									: Mono.just(true))
									.thenReturn(new CampaignCallbackNamespace.ResolvedClick(callback.targetUrl()))))
					.orElseGet(Mono::empty);
		}).timeout(RESOLUTION_TIMEOUT).onErrorResume(ignored -> Mono.empty());
	}

	@Override
	public Mono<Boolean> unsubscribe(String token, AuthenticationRequestContext request) {
		return Mono.defer(() -> {
			Instant now = clock.instant();
			return signer.verifyUnsubscribe(token, now)
					.map(verified -> repository.resolveCallback(signer.digest(token), "UNSUBSCRIBE", now)
							.filter(callback -> callback.messageId().equals(verified.messageId()))
							.flatMap(callback -> repository.observeCallback(callback, "UNSUBSCRIBE",
									observation("UNSUBSCRIBE", HttpHeaders.EMPTY, request), now)))
					.orElseGet(() -> Mono.just(false));
		}).timeout(RESOLUTION_TIMEOUT).onErrorReturn(false);
	}

	private void validateState(
			CampaignDeliveryRepository.SafetyClaim claim,
			CampaignSafetyRepository.PreparationState state,
			CampaignSafetyRuntimePolicy.Destination destination
	) {
		if (!state.messageId().equals(claim.messageId()) || !state.runId().equals(claim.runId())
				|| !state.campaignRecipientId().equals(claim.campaignRecipientId())
				|| state.attemptNumber() != claim.attemptNumber()
				|| state.trackingOpens() != claim.trackingOpensEnabled()
				|| state.trackingClicks() != claim.trackingClicksEnabled()
				|| !MessageDigest.isEqual(state.destinationHmac(), destination.hmac())
				|| state.subject() == null || state.subject().isBlank()
				|| state.html() == null || state.html().isBlank()
				|| state.text() == null || state.text().isBlank()) throw invalidContent();
	}

	private void validateSource(CampaignSafetyRepository.PreparationState state) {
		try {
			contentPolicy.validateSource(state.subject(), state.html(), state.text());
		}
		catch (IllegalArgumentException rejected) {
			throw invalidContent();
		}
		if (!state.subject().startsWith("[SAFETY TEST] ") || !state.html().contains("<strong>SAFETY TEST</strong>")
				|| !state.text().startsWith("[SAFETY TEST") || state.subject().contains(PLACEHOLDER)
				|| ANY_OTHER_CAPABILITY.matcher(state.subject() + state.html() + state.text()).find()
				|| containsSafetyToken(state.subject()) || containsSafetyToken(state.html())
				|| containsSafetyToken(state.text()) || containsEmail(state.subject(), state.html(), state.text())) {
			throw invalidContent();
		}
	}

	private void validateFinal(
			CampaignDeliveryRepository.SafetyClaim claim, PreparedSafetyOutbound outbound
	) {
		if (!outbound.subject().startsWith("[SAFETY TEST] ")
				|| !outbound.html().contains("<strong>SAFETY TEST</strong>")
				|| !outbound.text().startsWith("[SAFETY TEST")
				|| outbound.subject().contains(PLACEHOLDER) || outbound.html().contains(PLACEHOLDER)
				|| outbound.text().contains(PLACEHOLDER)
				|| contentPolicy.containsAddress(outbound.subject(), false)
				|| contentPolicy.containsAddress(outbound.html(), true)
				|| contentPolicy.containsAddress(outbound.text(), false)
				|| contentPolicy.containsForbiddenSenderMetadata(
						claim.fromName(), claim.fromEmail(), claim.replyTo())
				|| outbound.headers().values().stream()
						.anyMatch(value -> contentPolicy.containsAddress(value, false))
				|| !outbound.headers().keySet().equals(java.util.Set.of(
						"List-Unsubscribe", "List-Unsubscribe-Post"))
				|| !"List-Unsubscribe=One-Click".equals(outbound.headers().get("List-Unsubscribe-Post"))) {
			throw invalidContent();
		}
		Instant now = clock.instant();
		FrozenCapabilities capabilities = parseCapabilities(
				outbound.subject(), outbound.html(), outbound.text(), claim.messageId(),
				claim.trackingOpensEnabled(), claim.trackingClicksEnabled(), now);
		for (CallbackReference reference : capabilities.htmlReferences()) {
			verify(reference, claim.messageId(), now);
		}
		String unsubscribe = capabilities.unsubscribeHtml().token();
		if (!outbound.headers().get("List-Unsubscribe").equals(
				"<" + tracking.publicBaseUrl() + "/u/" + unsubscribe + ">")
				|| signer.verifyUnsubscribe(unsubscribe, now)
						.filter(value -> value.messageId().equals(claim.messageId())).isEmpty()) throw invalidContent();
	}

	private PreparedSafetyOutbound outbound(
			String recipient, String subject, String html, String text, String unsubscribeUrl
	) {
		return new PreparedSafetyOutbound(recipient, subject, html, text, Map.of(
				"List-Unsubscribe", "<" + unsubscribeUrl + ">",
				"List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
	}

	private FrozenCapabilities parseCapabilities(
			String subject, String html, String text, UUID messageId,
			boolean trackingOpens, boolean trackingClicks, Instant now
	) {
		if (contentPolicy.containsForbidden(subject, false)
				|| contentPolicy.containsAddress(html, true) || contentPolicy.containsAddress(text, false)
				|| contentPolicy.containsForeignCapability(html, true)
				|| contentPolicy.containsForeignCapability(text, false)
				|| contentPolicy.containsCapabilityJoinedAcrossHtmlNodes(html)) throw invalidContent();
		validateHtmlCapabilityAttributes(html);
		List<CallbackReference> htmlReferences = callbackReferences(html, true);
		List<CallbackReference> textReferences = callbackReferences(text, true);
		validateDecodedCapabilityCount(html, true, htmlReferences.size());
		validateDecodedCapabilityCount(text, false, textReferences.size());
		List<CallbackReference> unsubscribeHtml = ofKind(htmlReferences, CallbackKind.UNSUBSCRIBE);
		List<CallbackReference> unsubscribeText = ofKind(textReferences, CallbackKind.UNSUBSCRIBE);
		List<CallbackReference> opens = ofKind(htmlReferences, CallbackKind.OPEN);
		List<CallbackReference> clicks = ofKind(htmlReferences, CallbackKind.CLICK);
		if (unsubscribeHtml.size() != 1 || unsubscribeText.size() != 1
				|| !unsubscribeHtml.getFirst().url().equals(unsubscribeText.getFirst().url())
				|| !unsubscribeHtml.getFirst().token().equals(unsubscribeText.getFirst().token())
				|| !ofKind(textReferences, CallbackKind.OPEN).isEmpty()
				|| !ofKind(textReferences, CallbackKind.CLICK).isEmpty()
				|| opens.size() != (trackingOpens ? 1 : 0)
				|| !trackingClicks && !clicks.isEmpty()
				|| countClickAnchors(html) != clicks.size()
				|| countOpenImages(html) != opens.size()) throw invalidContent();
		for (CallbackReference reference : concat(htmlReferences, textReferences)) {
			if (!tracking.publicBaseUrl().equals(reference.origin())) throw invalidContent();
			verify(reference, messageId, now);
		}
		return new FrozenCapabilities(
				unsubscribeHtml.getFirst(), List.copyOf(opens), List.copyOf(clicks),
				List.copyOf(htmlReferences), List.copyOf(textReferences));
	}

	private void validateHtmlCapabilityAttributes(String html) {
		for (var element : Jsoup.parseBodyFragment(html == null ? "" : html).getAllElements()) {
			for (var attribute : element.attributes()) {
				String value = attribute.getValue();
				if (ANY_SIGNED_CAPABILITY.matcher(contentPolicy.inspect(value, false)).results().findAny().isEmpty()) {
					continue;
				}
				List<CallbackReference> references = callbackReferences(value, false);
				if (references.size() != 1 || !references.getFirst().url().equals(value)
						|| !references.getFirst().kind().matchesDomRole(element.tagName(), attribute.getKey())) {
					throw invalidContent();
				}
			}
		}
	}

	private void validateDecodedCapabilityCount(String value, boolean html, int expected) {
		String inspected = contentPolicy.inspect(value, html);
		if (ANY_SIGNED_CAPABILITY.matcher(inspected).results().count() != expected) throw invalidContent();
	}

	private List<CallbackReference> callbackReferences(String content, boolean allowSentencePunctuation) {
		List<CallbackReference> references = new ArrayList<>();
		Matcher matcher = CALLBACK_URL.matcher(content == null ? "" : content);
		while (matcher.find()) {
			CallbackKind kind = CallbackKind.fromPath(matcher.group(1));
			String rawToken = matcher.group(2);
			String token = allowSentencePunctuation ? trimSentencePunctuation(rawToken) : rawToken;
			boolean safetyCapability = OPEN_TOKEN.matcher(token).matches()
					|| CLICK_TOKEN.matcher(token).matches() || UNSUBSCRIBE_TOKEN.matcher(token).matches();
			if (!safetyCapability) continue;
			if (!kind.pattern().matcher(token).matches()) throw invalidContent();
			String rawUrl = matcher.group().substring(0, matcher.group().length() - rawToken.length()) + token;
			try {
				URI uri = URI.create(rawUrl);
				if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
						|| uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0
						|| uri.getPort() > 65_535 || !uri.getRawPath().equals("/" + kind.path() + "/" + token)) {
					throw invalidContent();
				}
				references.add(new CallbackReference(kind, rawUrl, token, origin(uri)));
			}
			catch (IllegalArgumentException rejected) {
				throw invalidContent();
			}
		}
		return List.copyOf(references);
	}

	private VerifiedCapability verify(CallbackReference reference, UUID messageId, Instant now) {
		return switch (reference.kind()) {
			case OPEN -> (now == null
					? signer.verifyOpenIncludingExpired(reference.token())
					: signer.verifyOpen(reference.token(), now))
					.filter(value -> value.messageId().equals(messageId))
					.map(value -> new VerifiedCapability(null, value.expiresAt()))
					.orElseThrow(this::invalidContent);
			case CLICK -> (now == null
					? signer.verifyClickIncludingExpired(reference.token())
					: signer.verifyClick(reference.token(), now))
					.filter(value -> value.messageId().equals(messageId))
					.map(value -> new VerifiedCapability(value.linkId(), value.expiresAt()))
					.orElseThrow(this::invalidContent);
			case UNSUBSCRIBE -> (now == null
					? signer.verifyUnsubscribeIncludingExpired(reference.token())
					: signer.verifyUnsubscribe(reference.token(), now))
					.filter(value -> value.messageId().equals(messageId))
					.map(value -> new VerifiedCapability(null, value.expiresAt()))
					.orElseThrow(this::invalidContent);
		};
	}

	private String origin(URI uri) {
		try {
			return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null)
					.toString();
		}
		catch (URISyntaxException impossible) {
			throw invalidContent();
		}
	}

	private String trimSentencePunctuation(String value) {
		int end = value.length();
		while (end > 0 && ".,;:!?".indexOf(value.charAt(end - 1)) >= 0) end--;
		return value.substring(0, end);
	}

	private int countClickAnchors(String html) {
		return Jsoup.parseBodyFragment(html == null ? "" : html).select("a[href]").stream()
				.mapToInt(anchor -> (int) callbackReferences(anchor.attr("href"), false).stream()
						.filter(reference -> reference.kind() == CallbackKind.CLICK).count())
				.sum();
	}

	private int countOpenImages(String html) {
		return Jsoup.parseBodyFragment(html == null ? "" : html).select("img[src]").stream()
				.mapToInt(image -> (int) callbackReferences(image.attr("src"), false).stream()
						.filter(reference -> reference.kind() == CallbackKind.OPEN).count())
				.sum();
	}

	private List<CallbackReference> ofKind(List<CallbackReference> values, CallbackKind kind) {
		return values.stream().filter(reference -> reference.kind() == kind).toList();
	}

	private List<CallbackReference> concat(
			List<CallbackReference> first, List<CallbackReference> second
	) {
		List<CallbackReference> result = new ArrayList<>(first);
		result.addAll(second);
		return List.copyOf(result);
	}

	private List<CallbackReference> uniqueArtifactReferences(List<CallbackReference> references) {
		Map<String, CallbackReference> unique = new LinkedHashMap<>();
		for (CallbackReference reference : references) {
			String key = reference.kind().name() + "\n" + reference.token();
			CallbackReference previous = unique.putIfAbsent(key, reference);
			if (previous != null && !previous.url().equals(reference.url())) throw invalidContent();
		}
		return List.copyOf(unique.values());
	}

	private String appendPixel(String html, String token) {
		return html + "<img src=\"" + tracking.publicBaseUrl() + "/t/o/" + token
				+ "\" width=\"1\" height=\"1\" alt=\"\" style=\"width:1px;height:1px;border:0\""
				+ " referrerpolicy=\"no-referrer\">";
	}

	private CampaignSafetyRepository.Observation observation(
			String eventType, HttpHeaders headers, AuthenticationRequestContext request
	) {
		MailOpenClassifier.Observation base = classifier.classify(headers == null ? HttpHeaders.EMPTY : headers);
		String userAgent = bounded(headers == null ? null : headers.getFirst(HttpHeaders.USER_AGENT), 512);
		String classification = switch (base.classification()) {
			case PREFETCH, IMAGE_PROXY -> "PREFETCH";
			case BOT -> securityScanner(userAgent) ? "SECURITY_SCANNER" : "BOT";
			case UNCLASSIFIED -> eventType.equals("OPEN") ? "UNCLASSIFIED" : "LIKELY_HUMAN";
		};
		String ip = request == null || request.ipAddress() == null ? "unknown" : request.ipAddress();
		byte[] fingerprint = signer.fingerprint("campaign-safety-callback:v1\n" + eventType + "\n" + ip
				+ "\n" + userAgent + "\n" + classification);
		return new CampaignSafetyRepository.Observation(classification, base.reason(), fingerprint);
	}

	private boolean securityScanner(String value) {
		return value.contains("proofpoint") || value.contains("barracuda")
				|| value.contains("mimecast") || value.contains("security scanner");
	}

	private String bounded(String value, int maximum) {
		if (value == null) return "";
		return value.substring(0, Math.min(value.length(), maximum))
				.replaceAll("[\\p{Cntrl}]", " ").strip().toLowerCase(Locale.ROOT);
	}

	private List<String> matches(Pattern pattern, String value) {
		List<String> matches = new ArrayList<>();
		Matcher matcher = pattern.matcher(value == null ? "" : value);
		while (matcher.find()) matches.add(matcher.group());
		return List.copyOf(matches);
	}

	private boolean containsSafetyToken(String value) {
		return OPEN_TOKEN.matcher(value).find() || CLICK_TOKEN.matcher(value).find()
				|| UNSUBSCRIBE_TOKEN.matcher(value).find();
	}

	private boolean containsEmail(String... values) {
		return Arrays.stream(values).anyMatch(value -> value != null && EMAIL.matcher(value).find());
	}

	private byte[] sha256(String value) {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("Campaign safety SHA-256 is unavailable", impossible);
		}
	}

	private <T> Mono<T> invalid() {
		return Mono.error(invalidContent());
	}

	private IllegalArgumentException invalidContent() {
		return new IllegalArgumentException("Campaign safety content is invalid");
	}

	private record LinkDraft(UUID id, String type, String target, byte[] targetHash, String token) { }

	private record RotationDraft(
			CampaignSafetyRepository.TrackingArtifact expected, String token
	) { }

	private record FrozenCapabilities(
			CallbackReference unsubscribeHtml, List<CallbackReference> openReferences,
			List<CallbackReference> clickReferences, List<CallbackReference> htmlReferences,
			List<CallbackReference> textReferences
	) { }

	private record VerifiedCapability(UUID linkId, Instant expiresAt) { }

	private record FrozenValidation(FrozenCapabilities capabilities, Instant expiresAt) { }

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

		boolean matchesDomRole(String tagName, String attributeName) {
			return switch (this) {
				case OPEN -> "img".equals(tagName) && "src".equals(attributeName);
				case CLICK, UNSUBSCRIBE -> "a".equals(tagName) && "href".equals(attributeName);
			};
		}

		static CallbackKind fromPath(String path) {
			return switch (path) {
				case "t/o" -> OPEN;
				case "t/c" -> CLICK;
				case "u" -> UNSUBSCRIBE;
				default -> throw new IllegalArgumentException("Unsupported campaign safety callback path");
			};
		}
	}
}
