package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.tracking.MailTrackingModels;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/** Revalidates the server-allowlisted live-test destination and its external callback boundary. */
public final class CampaignSafetyRuntimePolicy {
	private final CampaignSafetyProperties safety;
	private final SmtpProperties smtp;
	private final MailTrackingProperties tracking;
	private final CampaignSafetySigner signer;
	private final Duration deliveryLeaseDuration;

	public CampaignSafetyRuntimePolicy(
			CampaignSafetyProperties safety, SmtpProperties smtp,
			MailTrackingProperties tracking, CampaignSafetySigner signer,
			Duration deliveryLeaseDuration
	) {
		this.safety = safety;
		this.smtp = smtp;
		this.tracking = tracking;
		this.signer = signer;
		this.deliveryLeaseDuration = Objects.requireNonNull(
				deliveryLeaseDuration, "deliveryLeaseDuration");
		if (safety.enabled()) requireReady();
	}

	public Destination requireReady() {
		return requireReady(null);
	}

	public Destination requireReady(String destinationKey) {
		if (!safety.enabled()) throw rejected("Campaign safety mode is disabled");
		if (!smtp.liveAllowed()) throw rejected("Live SMTP is disabled");
		if (!tracking.enabled()
				|| tracking.callbackScope() != MailTrackingModels.CallbackScope.PUBLIC_HTTPS_CONFIGURED) {
			throw rejected("Public HTTPS tracking callbacks are required for a safety run");
		}
		// Capability expiry is truncated to whole seconds during preparation while the
		// lease retains sub-second precision. One full second of headroom guarantees the
		// freshly issued callbacks remain valid beyond the active delivery lease.
		if (tracking.tokenTtl().compareTo(deliveryLeaseDuration.plusSeconds(1)) < 0) {
			throw rejected("Campaign safety tracking lifetime must safely exceed the delivery lease");
		}
		if (destinationKey == null || "default".equals(destinationKey)) {
			return destination(safety.validatedRecipient());
		}
		if (!CampaignSafetyProperties.validDestinationKey(destinationKey)
				|| !safety.destinations().containsKey(destinationKey)) {
			throw rejected("Campaign safety destination key is not configured");
		}
		return destination(safety.destinations().get(destinationKey));
	}

	public Destination requireMatching(byte[] persistedHmac) {
		Destination current = requireReady();
		if (persistedHmac != null) {
			if (MessageDigest.isEqual(current.hmac(), persistedHmac)) return current;
			for (String address : safety.destinations().values()) {
				Destination allowed = destination(address);
				if (MessageDigest.isEqual(allowed.hmac(), persistedHmac)) return allowed;
			}
		}
		throw rejected("Campaign safety destination configuration changed");
	}

	private Destination destination(String address) {
		String domain = address.substring(address.indexOf('@') + 1);
		return new Destination(address, domain, address.substring(0, 1) + "***@" + domain,
				signer.destinationHmac(address));
	}

	private CampaignValidationException rejected(String message) {
		return new CampaignValidationException(message);
	}

	public record Destination(String address, String domain, String masked, byte[] hmac) {
		public Destination {
			hmac = hmac == null ? null : Arrays.copyOf(hmac, hmac.length);
		}

		@Override public byte[] hmac() {
			return hmac == null ? null : Arrays.copyOf(hmac, hmac.length);
		}
	}
}
