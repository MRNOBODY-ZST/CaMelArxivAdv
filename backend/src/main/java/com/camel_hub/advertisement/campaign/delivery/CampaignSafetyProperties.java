package com.camel_hub.advertisement.campaign.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("app.campaign-safety")
public record CampaignSafetyProperties(
		boolean enabled, String recipient, int maximumRecipients, Map<String, String> destinations
) {
	private static final String LOCAL_ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";

	public CampaignSafetyProperties(boolean enabled, String recipient, int maximumRecipients) {
		this(enabled, recipient, maximumRecipients, Map.of());
	}

	@ConstructorBinding
	public CampaignSafetyProperties {
		if (maximumRecipients < 1 || maximumRecipients > 20) {
			throw new IllegalArgumentException("Campaign safety recipient limit must be between 1 and 20");
		}
		recipient = canonicalRecipient(recipient, enabled);
		Map<String, String> validated = new LinkedHashMap<>();
		if (destinations != null) {
			if (destinations.size() > 10) {
				throw new IllegalArgumentException("Campaign safety supports at most 10 named destinations");
			}
			destinations.forEach((key, value) -> {
				if (!validDestinationKey(key) || "default".equals(key)) {
					throw new IllegalArgumentException("Campaign safety destination key is invalid or reserved");
				}
				// Empty optional environment values do not enable a destination.
				String address = canonicalRecipient(value, false);
				if (!address.isBlank()) validated.put(key, address);
			});
		}
		destinations = Collections.unmodifiableMap(validated);
	}

	public static boolean validDestinationKey(String key) {
		return key != null && key.matches("[a-z][a-z0-9-]{0,31}");
	}

	public String validatedRecipient() {
		return recipient;
	}

	public String recipientDomain() {
		if (recipient.isBlank()) return "";
		return recipient.substring(recipient.indexOf('@') + 1);
	}

	public String maskedRecipient() {
		if (recipient.isBlank()) return "";
		String local = recipient.substring(0, recipient.indexOf('@'));
		return local.substring(0, 1) + "***@" + recipientDomain();
	}

	private static String canonicalRecipient(String value, boolean required) {
		String candidate = value == null ? "" : value.strip();
		if (candidate.isEmpty()) {
			if (required) invalid();
			return "";
		}
		if (candidate.length() > 320
				|| candidate.codePoints().anyMatch(point -> point > 0x7f || Character.isISOControl(point))) {
			invalid();
		}
		int separator = candidate.indexOf('@');
		if (separator < 1 || separator != candidate.lastIndexOf('@') || separator > 64
				|| separator == candidate.length() - 1) invalid();
		String local = candidate.substring(0, separator);
		String domain = candidate.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
		if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) invalid();
		for (String atom : local.split("\\.", -1)) {
			if (!atom.matches(LOCAL_ATOM)) invalid();
		}
		if (domain.length() > 253 || domain.startsWith(".") || domain.endsWith(".")
				|| domain.contains("..") || !domain.contains(".")) invalid();
		for (String label : domain.split("\\.", -1)) {
			if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")
					|| !label.matches("[a-z0-9-]+")) invalid();
		}
		return local + "@" + domain;
	}

	private static void invalid() {
		throw new IllegalArgumentException("Campaign safety recipient must be one strict email address");
	}
}
