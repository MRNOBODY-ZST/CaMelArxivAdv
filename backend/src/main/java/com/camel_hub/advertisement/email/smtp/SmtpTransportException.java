package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;

public class SmtpTransportException extends RuntimeException {
	private static final java.util.regex.Pattern QUOTED_EMAIL_LIKE = java.util.regex.Pattern.compile(
			"(?iu)\"[^\"\\r\\n]{1,128}\"@[^\\s<>@]{1,320}");
	private static final java.util.regex.Pattern EMAIL_LIKE = java.util.regex.Pattern.compile(
			"(?iu)[^\\s<>@]{1,320}@[^\\s<>@]{1,320}");
	private static final java.util.regex.Pattern SECRET_LIKE = java.util.regex.Pattern.compile(
			"(?i)\\b(password|credential|authorization|token|api[_-]?key)\\s*[:=]\\s*[^\\s,;]{1,500}");
	private static final java.util.regex.Pattern URL_LIKE = java.util.regex.Pattern.compile(
			"(?i)\\bhttps?://[^\\s<>]{1,500}");
	private static final java.util.regex.Pattern AUTHORIZATION_LIKE = java.util.regex.Pattern.compile(
			"(?i)\\b(?:proxy-)?authorization\\s*:\\s*(?:bearer|basic)\\s+[^\\s,;]{1,500}");
	private static final java.util.regex.Pattern BEARER_LIKE = java.util.regex.Pattern.compile(
			"(?i)\\bbearer\\s+[A-Z0-9._~+/=\\-]{1,500}");

	private final FailureCategory category;
	private final AttemptStatus status;
	private final TransportStage stage;
	private final Integer responseCode;
	private final String responseSummary;
	private final boolean retryable;

	public SmtpTransportException(FailureCategory category) {
		this(category, AttemptStatus.PERMANENT_FAILURE, TransportStage.CONNECT,
				null, null, false);
	}

	public SmtpTransportException(
			FailureCategory category, AttemptStatus status, TransportStage stage,
			Integer responseCode, String responseSummary, boolean retryable
	) {
		super("SMTP operation failed: " + require(category).name());
		this.category = category;
		this.status = status == null ? AttemptStatus.PERMANENT_FAILURE : status;
		this.stage = stage == null ? TransportStage.CONNECT : stage;
		this.responseCode = validResponseCode(responseCode);
		this.responseSummary = sanitize(responseSummary);
		this.retryable = retryable
				&& this.status == AttemptStatus.TEMPORARY_FAILURE
				&& this.responseCode != null
				&& this.responseCode >= 400 && this.responseCode <= 499;
	}

	public FailureCategory category() {
		return category;
	}

	public AttemptStatus status() {
		return status;
	}

	public TransportStage stage() {
		return stage;
	}

	public Integer responseCode() {
		return responseCode;
	}

	public String responseSummary() {
		return responseSummary;
	}

	public boolean retryable() {
		return retryable;
	}

	private static FailureCategory require(FailureCategory category) {
		if (category == null) throw new IllegalArgumentException("SMTP failure category is required");
		return category;
	}

	private static Integer validResponseCode(Integer value) {
		return value != null && value >= 100 && value <= 599 ? value : null;
	}

	public static String sanitize(String value) {
		if (value == null || value.isBlank()) return null;
		String safe = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
		safe = QUOTED_EMAIL_LIKE.matcher(safe).replaceAll("[redacted-address]");
		safe = EMAIL_LIKE.matcher(safe).replaceAll("[redacted-address]");
		safe = AUTHORIZATION_LIKE.matcher(safe).replaceAll("Authorization: [redacted]");
		safe = BEARER_LIKE.matcher(safe).replaceAll("Bearer [redacted]");
		safe = SECRET_LIKE.matcher(safe).replaceAll("$1=[redacted]");
		safe = URL_LIKE.matcher(safe).replaceAll("[redacted-url]");
		return safe.substring(0, Math.min(safe.length(), 500));
	}

	public enum FailureCategory {
		CONNECTION_TIMEOUT,
		DNS_FAILURE,
		TLS_FAILURE,
		AUTHENTICATION_FAILED,
		CONNECTION_REJECTED,
		SMTP_REJECTED,
		CONFIGURATION_FAILURE,
		PREPARATION_FAILED,
		UNEXPECTED_FAILURE
	}
}
