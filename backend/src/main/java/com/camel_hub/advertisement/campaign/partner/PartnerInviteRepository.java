package com.camel_hub.advertisement.campaign.partner;

import reactor.core.publisher.Mono;

import java.time.Instant;

/** Atomic reservation is the authority for a single initial POST per mailbox contact. */
public interface PartnerInviteRepository {
    Mono<Boolean> reserve(byte[] emailHmac, String hash, Instant now);
    default Mono<Boolean> reserve(com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.ProductionClaim claim,
            String hash, Instant now) {
        return reserve(claim.emailHmac(), hash, now);
    }
    Mono<Row> find(byte[] emailHmac);
    Mono<Boolean> ready(byte[] emailHmac, String hash, byte[] encryptedUrl, Instant expiresAt, Instant now);
    Mono<Void> transition(byte[] emailHmac, String hash, State state, Instant now);

    enum State { REQUESTING, READY, HELD, CLAIMED }

    record Row(String hash, State state, byte[] encryptedUrl, Instant expiresAt) {
        @Override public String toString() { return "PartnerInviteRow[state=" + state + "]"; }
    }
}
