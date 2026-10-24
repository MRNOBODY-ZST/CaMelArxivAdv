package com.camel_hub.advertisement.campaign.partner;

import reactor.core.publisher.Mono;

import java.time.Instant;

/** The only remote partner operations; no retry or URL-retrieval operation exists. */
public interface PartnerInviteClient {
    Mono<PendingInvite> create(String hash, String email);
    Mono<IdentityStatus> identity(String hash);

    enum IdentityStatus { CLAIMED, PENDING, NOT_FOUND }

    record PendingInvite(String claimUrl, Instant expiresAt) {
        @Override public String toString() { return "PendingInvite[redacted]"; }
    }
}
