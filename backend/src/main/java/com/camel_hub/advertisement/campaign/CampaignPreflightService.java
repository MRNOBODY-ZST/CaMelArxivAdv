package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.email.tracking.MailTrackingModels;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.InternetAddress;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CampaignPreflightService {

	private final CampaignWorkflowRepository repository;
	private final CampaignDeliveryProperties deliveryProperties;
	private final MailTrackingProperties trackingProperties;
	private final ObjectMapper objectMapper;

	public CampaignPreflightService(
			CampaignWorkflowRepository repository, CampaignDeliveryProperties deliveryProperties,
			MailTrackingProperties trackingProperties, ObjectMapper objectMapper
	) {
		this.repository = repository;
		this.deliveryProperties = deliveryProperties;
		this.trackingProperties = trackingProperties;
		this.objectMapper = objectMapper;
	}

	public Mono<PreflightView> preflight(UUID campaignId) {
		Instant cooldownCutoff = Instant.now().minus(deliveryProperties.productionCooldown());
		return repository.preflight(campaignId, cooldownCutoff)
				.switchIfEmpty(Mono.error(new CampaignNotFoundException("Campaign was not found")))
				.map(this::view);
	}

	byte[] digestBytes(PreflightView view) {
		return HexFormat.of().parseHex(view.digest());
	}

	private PreflightView view(CampaignWorkflowRepository.PreflightRecord record) {
		boolean contentReady = record.total() > 0 && record.contentReady() == record.total();
		boolean unsubscribePresent = record.total() > 0 && record.unsubscribePresent() == record.total();
		boolean senderValid = nonBlank(record.purpose()) && nonBlank(record.fromName())
				&& email(record.fromEmail()) && email(record.replyTo());
		boolean smtpReady = record.smtpEnabled() && "SUCCEEDED".equals(record.smtpTestStatus())
				&& record.smtpTestCurrent();
		boolean mailboxReady = record.mailboxId() != null && record.mailboxEnabled()
				&& "SUCCEEDED".equals(record.mailboxTestStatus()) && record.mailboxTestCurrent();
		boolean trackingReady = trackingProperties.enabled()
				&& trackingProperties.callbackScope() == MailTrackingModels.CallbackScope.PUBLIC_HTTPS_CONFIGURED;
		boolean recipientsEligible = record.eligible() > 0;

		Map<String, PreflightCheck> checks = new LinkedHashMap<>();
		checks.put("CONTENT_READY", check(contentReady, record.contentReady() + " of " + record.total()
				+ " recipients have complete generated content"));
		checks.put("UNSUBSCRIBE_PRESENT", check(unsubscribePresent, record.unsubscribePresent() + " of "
				+ record.total() + " recipients retain the unsubscribe placeholder"));
		checks.put("SENDER_VALID", check(senderValid, senderValid
				? "Sender identity, purpose, and reply address are present" : "Sender identity or purpose is invalid"));
		checks.put("SMTP_READY", check(smtpReady, smtpReady
				? "SMTP account is enabled and tested" : "SMTP account must be enabled and successfully tested"));
		checks.put("MAILBOX_READY", check(mailboxReady, mailboxReady
				? "Mailbox account is enabled and tested" : "Mailbox account must be selected, enabled, and tested"));
		checks.put("TRACKING_READY", check(trackingReady, trackingReady
				? "Public HTTPS callbacks are configured" : "Public HTTPS callbacks are not configured"));
		checks.put("RECIPIENTS_ELIGIBLE", check(recipientsEligible,
				record.eligible() + " production recipient(s) are currently eligible"));

		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put("TOTAL", record.total());
		counts.put("ELIGIBLE", record.eligible());
		counts.put("CONTENT_NOT_READY", record.total() - record.contentReady());
		counts.put("UNSUBSCRIBE_MISSING", record.total() - record.unsubscribePresent());
		counts.put("CONFIDENCE_NOT_HIGH", record.confidenceNotHigh());
		counts.put("CONTACT_INACTIVE", record.contactInactive());
		counts.put("CONTACT_DELETED", record.contactDeleted());
		counts.put("SYNTAX_INVALID", record.syntaxInvalid());
		counts.put("EXAMPLE_ADDRESS", record.exampleAddress());
		counts.put("AUTHOR_RELATION_MISSING", record.authorRelationMissing());
		counts.put("EVIDENCE_NOT_HIGH", record.evidenceNotHigh());
		counts.put("EVIDENCE_UNVERIFIED", record.evidenceUnverified());
		counts.put("EVIDENCE_UNCONFIRMED", record.evidenceUnconfirmed());
		counts.put("EVIDENCE_MISSING", record.evidenceMissing());
		counts.put("SUPPRESSED", record.suppressed());
		counts.put("UNSUBSCRIBED", record.unsubscribed());
		counts.put("CAMPAIGN_EXCLUDED", record.campaignExcluded());
		counts.put("COOLDOWN_ACTIVE", record.cooldownActive());

		long estimatedMinutes = estimateMinutes(record);
		boolean ready = checks.values().stream().allMatch(PreflightCheck::passed);
		String digest = digest(ready, checks, counts, estimatedMinutes);
		return new PreflightView(ready, Map.copyOf(checks), Map.copyOf(counts), estimatedMinutes, digest);
	}

	private long estimateMinutes(CampaignWorkflowRepository.PreflightRecord record) {
		if (record.eligible() == 0) return 0;
		long minute = windows(record.eligible(), record.perMinuteLimit(), 1);
		long hour = windows(record.eligible(), record.perHourLimit(), 60);
		long day = windows(record.eligible(), record.perDayLimit(), 1_440);
		long domainHour = windows(record.eligible(), record.perDomainHourLimit(), 60);
		return Math.max(Math.max(minute, hour), Math.max(day, domainHour));
	}

	private long windows(long recipients, int limit, int minutes) {
		if (limit <= 0) return Long.MAX_VALUE;
		long windows = (recipients + limit - 1) / limit;
		return Math.max(1, (windows - 1) * minutes + 1);
	}

	private boolean nonBlank(String value) {
		return value != null && !value.isBlank();
	}

	private boolean email(String value) {
		try {
			if (value == null || value.contains("\r") || value.contains("\n")) return false;
			InternetAddress address = new InternetAddress(value, true);
			return value.equals(address.getAddress());
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private PreflightCheck check(boolean passed, String detail) {
		return new PreflightCheck(passed, detail);
	}

	private String digest(
			boolean ready, Map<String, PreflightCheck> checks, Map<String, Long> counts, long estimatedMinutes
	) {
		Map<String, Object> canonical = new LinkedHashMap<>();
		canonical.put("ready", ready);
		canonical.put("checks", checks);
		canonical.put("counts", counts);
		canonical.put("estimatedMinutes", estimatedMinutes);
		try {
			byte[] serialized = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
		}
		catch (JsonProcessingException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Campaign preflight digest could not be created", exception);
		}
	}

	public record PreflightCheck(boolean passed, String detail) { }

	public record PreflightView(
			boolean ready, Map<String, PreflightCheck> checks, Map<String, Long> counts,
			long estimatedMinutes, String digest
	) { }
}
