package com.camel_hub.advertisement.campaign.partner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.partner-invite")
public record PartnerInviteProperties(
        boolean enabled, String bearerToken, String encryptionKeyBase64, Duration requestTimeout
) {
    public PartnerInviteProperties {
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        if (requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Partner invite timeout must be between zero and ten seconds");
        }
    }

    @Override public String toString() {
        return "PartnerInviteProperties[enabled=" + enabled + ", requestTimeout=" + requestTimeout + "]";
    }
}
