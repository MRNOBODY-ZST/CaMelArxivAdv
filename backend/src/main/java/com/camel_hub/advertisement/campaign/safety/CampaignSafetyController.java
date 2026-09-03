package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/campaigns/{campaignId}/safety-runs")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CampaignSafetyController {
	private final CampaignSafetyService service;

	public CampaignSafetyController(CampaignSafetyService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('campaign:send')")
	Mono<CampaignSafetyService.SafetyRunView> start(
			@PathVariable UUID campaignId, @Valid @RequestBody SafetyStartRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.start(campaignId, RequestContextSupport.actorId(principal),
				RequestContextSupport.context(exchange), request.command());
	}

	@GetMapping
	@PreAuthorize("hasAuthority('campaign:read')")
	Mono<List<CampaignSafetyService.SafetyRunView>> list(@PathVariable UUID campaignId) {
		return service.list(campaignId);
	}

	@GetMapping("/{runId}")
	@PreAuthorize("hasAuthority('campaign:read')")
	Mono<CampaignSafetyService.SafetyRunView> get(
			@PathVariable UUID campaignId, @PathVariable UUID runId
	) {
		return service.get(campaignId, runId);
	}

	@PostMapping("/{runId}/cancel")
	@PreAuthorize("hasAuthority('campaign:pause')")
	Mono<CampaignSafetyService.SafetyRunView> cancel(
			@PathVariable UUID campaignId, @PathVariable UUID runId,
			@Valid @RequestBody SafetyCancelRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.cancel(campaignId, runId, RequestContextSupport.actorId(principal),
				RequestContextSupport.context(exchange), request.expectedLockVersion());
	}

	@JsonDeserialize(using = SafetyStartRequest.Deserializer.class)
	public static final class SafetyStartRequest {
		private final Long expectedLockVersion;
		private final Integer recipientLimit;
		private final String confirmation;

		public SafetyStartRequest(
				Long expectedLockVersion, Integer recipientLimit, String confirmation
		) {
			this.expectedLockVersion = expectedLockVersion;
			this.recipientLimit = recipientLimit;
			this.confirmation = confirmation;
		}

		CampaignSafetyService.StartCommand command() {
			if (expectedLockVersion == null || expectedLockVersion < 0 || recipientLimit == null
					|| recipientLimit < 1 || recipientLimit > 20
					|| !CampaignSafetyService.CONFIRMATION.equals(confirmation)) {
				throw new CampaignValidationException("Campaign safety request is invalid");
			}
			return new CampaignSafetyService.StartCommand(expectedLockVersion, recipientLimit, confirmation);
		}

		public static final class Deserializer extends ValueDeserializer<SafetyStartRequest> {
			@Override
			public SafetyStartRequest deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
				requireObject(parser, "campaign safety request");
				Set<String> fields = new HashSet<>();
				Long version = null;
				Integer limit = null;
				String confirmation = null;
				while (parser.nextToken() != JsonToken.END_OBJECT) {
					if (parser.currentToken() != JsonToken.PROPERTY_NAME) invalid(parser, "campaign safety request");
					String name = parser.currentName();
					if (!fields.add(name)) invalid(parser, "campaign safety request");
					JsonToken value = parser.nextToken();
					switch (name) {
						case "expectedLockVersion" -> version = strictLong(parser, value, "campaign safety request");
						case "recipientLimit" -> limit = strictInt(parser, value, "campaign safety request");
						case "confirmation" -> confirmation = strictString(parser, value, "campaign safety request");
						default -> invalid(parser, "campaign safety request");
					}
				}
				if (!fields.equals(Set.of("expectedLockVersion", "recipientLimit", "confirmation"))) {
					invalid(parser, "campaign safety request");
				}
				return new SafetyStartRequest(version, limit, confirmation);
			}
		}
	}

	@JsonDeserialize(using = SafetyCancelRequest.Deserializer.class)
	public static final class SafetyCancelRequest {
		private final Long expectedLockVersion;

		public SafetyCancelRequest(Long expectedLockVersion) {
			this.expectedLockVersion = expectedLockVersion;
		}

		long expectedLockVersion() {
			if (expectedLockVersion == null || expectedLockVersion < 0) {
				throw new CampaignValidationException("Campaign safety lock version is invalid");
			}
			return expectedLockVersion;
		}

		public static final class Deserializer extends ValueDeserializer<SafetyCancelRequest> {
			@Override
			public SafetyCancelRequest deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
				requireObject(parser, "campaign safety cancellation");
				Set<String> fields = new HashSet<>();
				Long version = null;
				while (parser.nextToken() != JsonToken.END_OBJECT) {
					if (parser.currentToken() != JsonToken.PROPERTY_NAME) invalid(parser, "campaign safety cancellation");
					String name = parser.currentName();
					if (!fields.add(name) || !"expectedLockVersion".equals(name)) {
						invalid(parser, "campaign safety cancellation");
					}
					version = strictLong(parser, parser.nextToken(), "campaign safety cancellation");
				}
				if (!fields.equals(Set.of("expectedLockVersion"))) {
					invalid(parser, "campaign safety cancellation");
				}
				return new SafetyCancelRequest(version);
			}
		}
	}

	private static void requireObject(JsonParser parser, String label) throws DatabindException {
		if (!parser.isExpectedStartObjectToken()) invalid(parser, label);
	}

	private static long strictLong(JsonParser parser, JsonToken token, String label) throws JacksonException {
		if (token != JsonToken.VALUE_NUMBER_INT) invalid(parser, label);
		return parser.getLongValue();
	}

	private static int strictInt(JsonParser parser, JsonToken token, String label) throws JacksonException {
		if (token != JsonToken.VALUE_NUMBER_INT) invalid(parser, label);
		return parser.getIntValue();
	}

	private static String strictString(JsonParser parser, JsonToken token, String label) throws JacksonException {
		if (token != JsonToken.VALUE_STRING) invalid(parser, label);
		return parser.getString();
	}

	private static void invalid(JsonParser parser, String label) throws DatabindException {
		throw DatabindException.from(parser, "Invalid " + label);
	}
}
