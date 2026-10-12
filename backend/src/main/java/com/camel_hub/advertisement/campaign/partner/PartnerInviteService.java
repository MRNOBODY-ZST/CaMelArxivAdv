package com.camel_hub.advertisement.campaign.partner;

import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Never renews an invite: only the first committed reservation may POST. */
public final class PartnerInviteService {
    private static final Pattern MAILBOX = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PartnerInviteRepository repository;
    private final PartnerInviteClient client;
    private final PartnerInviteCrypto crypto;
    private final PartnerInviteProperties properties;
    private final Clock clock;

    public PartnerInviteService(PartnerInviteRepository repository, PartnerInviteClient client,
            PartnerInviteCrypto crypto, PartnerInviteProperties properties, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.client = Objects.requireNonNull(client);
        this.crypto = Objects.requireNonNull(crypto);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public Mono<Invite> prepare(byte[] emailHmac, String email) {
        return prepare(null, emailHmac, email);
    }

    public Mono<Invite> prepareProduction(com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.ProductionClaim claim, String email) {
        return prepare(claim, claim.emailHmac(), email);
    }

    private Mono<Invite> prepare(com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.ProductionClaim claim,
            byte[] emailHmac, String email) {
        return Mono.defer(() -> {
            if (!properties.enabled()) return failure(PartnerInviteException.Reason.DISABLED);
            if (!crypto.configured() || properties.bearerToken() == null || properties.bearerToken().isBlank()) {
                return failure(PartnerInviteException.Reason.UNAVAILABLE);
            }
            if (emailHmac == null || emailHmac.length != 32 || email == null || email.length() > 50
                    || !MAILBOX.matcher(email).matches()) {
                return failure(PartnerInviteException.Reason.INVALID_INPUT);
            }
            byte[] contact = Arrays.copyOf(emailHmac, emailHmac.length);
            Instant now = clock.instant();
            String proposedHash = randomHash();
            return (claim == null ? repository.reserve(contact, proposedHash, now) : repository.reserve(claim, proposedHash, now))
                    .flatMap(inserted -> inserted ? create(contact, proposedHash, email, now)
                            : repository.find(contact)
                                    .switchIfEmpty(failure(PartnerInviteException.Reason.HELD))
                                    .flatMap(row -> existing(contact, row, now)))
                    .onErrorMap(error -> error instanceof PartnerInviteException ? error
                            : new PartnerInviteException(PartnerInviteException.Reason.UNAVAILABLE));
        });
    }

    private Mono<Invite> create(byte[] contact, String hash, String email, Instant now) {
        return client.create(hash, email)
                .flatMap(pending -> {
                    if (pending == null || pending.expiresAt() == null || !pending.expiresAt().isAfter(now)
                            || !HttpPartnerInviteClient.validClaimUrl(pending.claimUrl())) {
                        return hold(contact, hash, PartnerInviteException.Reason.HELD);
                    }
                    byte[] ciphertext = crypto.encrypt(contact, pending.claimUrl());
                    return repository.ready(contact, hash, ciphertext, pending.expiresAt(), now)
                            .flatMap(saved -> saved ? Mono.just(new Invite(pending.claimUrl(), pending.expiresAt()))
                                    : failure(PartnerInviteException.Reason.HELD));
                })
                .onErrorResume(error -> {
                    if (error instanceof PartnerInviteException exception
                            && exception.reason() == PartnerInviteException.Reason.CLAIMED) {
                        return repository.transition(contact, hash, PartnerInviteRepository.State.CLAIMED, clock.instant())
                                .then(failure(PartnerInviteException.Reason.CLAIMED));
                    }
                    return error instanceof PartnerInviteException exception
                            && exception.reason() == PartnerInviteException.Reason.HELD
                            ? Mono.error(exception) : hold(contact, hash, PartnerInviteException.Reason.HELD);
                });
    }

    private Mono<Invite> existing(byte[] contact, PartnerInviteRepository.Row row, Instant now) {
        return switch (row.state()) {
            case READY -> {
                if (row.expiresAt() == null || !row.expiresAt().isAfter(now)) {
                    yield failure(PartnerInviteException.Reason.EXPIRED);
                }
                yield client.identity(row.hash())
                        .flatMap(status -> {
                            if (status == PartnerInviteClient.IdentityStatus.CLAIMED) {
                                return repository.transition(contact, row.hash(), PartnerInviteRepository.State.CLAIMED,
                                                clock.instant())
                                        .then(PartnerInviteService.<Invite>failure(PartnerInviteException.Reason.CLAIMED));
                            }
                            if (status != PartnerInviteClient.IdentityStatus.PENDING) {
                                return PartnerInviteService.<Invite>failure(PartnerInviteException.Reason.HELD);
                            }
                            // Re-read after remote I/O: another process may have cleared this capability as CLAIMED.
                            return repository.find(contact).flatMap(current -> reuseReady(contact, row.hash(), current));
                        })
                        .switchIfEmpty(failure(PartnerInviteException.Reason.HELD))
                        .onErrorMap(error -> error instanceof PartnerInviteException ? error
                                : new PartnerInviteException(PartnerInviteException.Reason.HELD));
            }
            case CLAIMED -> failure(PartnerInviteException.Reason.CLAIMED);
            case REQUESTING, HELD -> client.identity(row.hash())
                    .flatMap(status -> status == PartnerInviteClient.IdentityStatus.CLAIMED
                            ? repository.transition(contact, row.hash(), PartnerInviteRepository.State.CLAIMED, now)
                                    .then(PartnerInviteService.<Invite>failure(PartnerInviteException.Reason.CLAIMED))
                            : PartnerInviteService.<Invite>failure(PartnerInviteException.Reason.HELD))
                    .onErrorMap(error -> error instanceof PartnerInviteException ? error
                            : new PartnerInviteException(PartnerInviteException.Reason.HELD));
        };
    }

    private Mono<Invite> reuseReady(byte[] contact, String hash, PartnerInviteRepository.Row row) {
        if (row.state() == PartnerInviteRepository.State.CLAIMED) {
            return failure(PartnerInviteException.Reason.CLAIMED);
        }
        if (row.state() != PartnerInviteRepository.State.READY || !row.hash().equals(hash)) {
            return failure(PartnerInviteException.Reason.HELD);
        }
        if (row.expiresAt() == null || !row.expiresAt().isAfter(clock.instant())) {
            return failure(PartnerInviteException.Reason.EXPIRED);
        }
        String url = crypto.decrypt(contact, row.encryptedUrl());
        return HttpPartnerInviteClient.validClaimUrl(url)
                ? Mono.just(new Invite(url, row.expiresAt())) : failure(PartnerInviteException.Reason.HELD);
    }

    private Mono<Invite> hold(byte[] contact, String hash, PartnerInviteException.Reason reason) {
        return repository.transition(contact, hash, PartnerInviteRepository.State.HELD, clock.instant())
                .onErrorResume(ignored -> Mono.empty())
                .then(failure(reason));
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static <T> Mono<T> failure(PartnerInviteException.Reason reason) {
        return Mono.error(new PartnerInviteException(reason));
    }

    public record Invite(String claimUrl, Instant expiresAt) {
        @Override public String toString() { return "Invite[redacted]"; }
    }
}
