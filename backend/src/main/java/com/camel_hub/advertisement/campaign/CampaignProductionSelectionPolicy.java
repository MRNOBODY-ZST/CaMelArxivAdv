package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Owner test destinations are excluded using the contact HMAC key, not tracking-token keys. */
public final class CampaignProductionSelectionPolicy {
	private final Set<String> excludedHmacs;
	private final Duration cooldown;
	private final Clock clock;

	public CampaignProductionSelectionPolicy(ContactCrypto crypto, CampaignSafetyProperties safety, Duration cooldown) {
		this(crypto, safety, cooldown, Clock.systemUTC());
	}

	CampaignProductionSelectionPolicy(ContactCrypto crypto, CampaignSafetyProperties safety, Duration cooldown, Clock clock) {
		Set<String> hashes = new LinkedHashSet<>();
		Set<String> addresses = new LinkedHashSet<>(safety.destinations().values());
		if (safety.recipient() != null && !safety.recipient().isBlank()) addresses.add(safety.recipient());
		for (String address : addresses) {
			hashes.add(HexFormat.of().formatHex(crypto.hmac(address.toLowerCase(Locale.ROOT))));
		}
		this.excludedHmacs = Set.copyOf(hashes);
		this.cooldown = cooldown;
		this.clock = clock;
	}

	String[] excludedHmacs() { return excludedHmacs.toArray(String[]::new); }
	boolean excludes(byte[] hmac) { return excludedHmacs.contains(HexFormat.of().formatHex(hmac)); }
	Instant cooldownCutoff() { return clock.instant().minus(cooldown); }
}
