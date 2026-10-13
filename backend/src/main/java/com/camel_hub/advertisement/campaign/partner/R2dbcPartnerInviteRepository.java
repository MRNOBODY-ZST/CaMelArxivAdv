package com.camel_hub.advertisement.campaign.partner;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** A committed REQUESTING row is the no-replay boundary before remote I/O. */
public final class R2dbcPartnerInviteRepository implements PartnerInviteRepository {
    private final DatabaseClient database;
    private final java.time.Clock clock;
    private final org.springframework.transaction.reactive.TransactionalOperator transactions;

    public R2dbcPartnerInviteRepository(DatabaseClient database) {
        this(database, java.time.Clock.systemUTC());
    }

    public R2dbcPartnerInviteRepository(DatabaseClient database, java.time.Clock clock) {
        this.database = database;
        this.clock = clock;
        this.transactions = org.springframework.transaction.reactive.TransactionalOperator.create(
                new org.springframework.r2dbc.connection.R2dbcTransactionManager(database.getConnectionFactory()));
    }

    @Override public Mono<Boolean> reserve(byte[] emailHmac, String hash, Instant now) {
        // Legacy callers cannot prove which side of a transfer they represent. Deny before find/reuse, too.
        return database.sql("SELECT pg_advisory_xact_lock(20260923,1000)").fetch().all().then(database.sql("""
                SELECT EXISTS(SELECT 1 FROM campaign_recipient_transfers t JOIN campaign_recipients r
                  ON r.id=t.source_recipient_id OR r.id=t.target_recipient_id WHERE r.email_hmac=:hmac) AS transferred
                """).bind("hmac", emailHmac).map((r,m) -> Boolean.TRUE.equals(r.get("transferred", Boolean.class))).one())
                .flatMap(transferred -> transferred ? denied() : insertReservation(emailHmac, hash, now))
                .as(transactions::transactional);
    }

    @Override public Mono<Boolean> reserve(com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.ProductionClaim claim,
            String hash, Instant now) {
        return new com.camel_hub.advertisement.campaign.migration.CampaignMigrationDeliveryFence(database)
                .lockProductionAndVerify(claim)
                .flatMap(allowed -> {
                    if (!allowed) return denied();
                    Instant lockedNow = clock.instant();
                    return database.sql("""
                    SELECT r.id FROM campaign_recipients r JOIN campaigns c ON c.id=r.campaign_id
                    JOIN delivery_attempts a ON a.id=:attempt AND a.campaign_recipient_id=r.id
                    WHERE r.id=:recipient AND c.id=:campaign AND c.status='RUNNING' AND r.status='CONNECTING'
                      AND r.delivery_lease_hash=:lease AND r.delivery_lease_expires_at>:now
                      AND a.status='CONNECTING' AND a.smtp_started_at IS NULL
                      AND NOT EXISTS(SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac=r.email_hmac)
                      AND NOT EXISTS(SELECT 1 FROM suppression_entries s WHERE s.email_hmac=r.email_hmac
                        AND (s.expires_at IS NULL OR s.expires_at>:now))
                    """).bind("attempt", claim.attemptId()).bind("recipient", claim.recipientId())
                        .bind("campaign", claim.campaignId()).bind("lease", com.camel_hub.advertisement.campaign.tracking.CampaignTrackingSigner.sha256Bytes(claim.leaseDigest()))
                        .bind("now", lockedNow).map((r,m) -> true).one().defaultIfEmpty(false)
                        .flatMap(active -> active ? insertReservation(claim.emailHmac(), hash, lockedNow) : denied());
                })
                .as(transactions::transactional);
    }

    private Mono<Boolean> denied() { return Mono.error(new PartnerInviteException(PartnerInviteException.Reason.HELD)); }

    private Mono<Boolean> insertReservation(byte[] emailHmac, String hash, Instant now) {
        return database.sql("""
                INSERT INTO partner_invites (email_hmac, binding_hash, state, created_at, updated_at)
                VALUES (:contact, :hash, 'REQUESTING', :now, :now)
                ON CONFLICT DO NOTHING
                RETURNING binding_hash
                """).bind("contact", emailHmac).bind("hash", hash).bind("now", now)
                .map((row, metadata) -> true).one().defaultIfEmpty(false);
    }

    @Override public Mono<Row> find(byte[] emailHmac) {
        return database.sql("""
                SELECT binding_hash, state, encrypted_claim_url, expires_at
                FROM partner_invites WHERE email_hmac = :contact
                """).bind("contact", emailHmac)
                .map((row, metadata) -> new Row(row.get("binding_hash", String.class),
                        State.valueOf(row.get("state", String.class)),
                        row.get("encrypted_claim_url", byte[].class), row.get("expires_at", Instant.class)))
                .one();
    }

    @Override public Mono<Boolean> ready(byte[] emailHmac, String hash, byte[] encryptedUrl,
            Instant expiresAt, Instant now) {
        return database.sql("""
                UPDATE partner_invites
                SET state = 'READY', encrypted_claim_url = :url, expires_at = :expires,
                    updated_at = :now
                WHERE email_hmac = :contact AND binding_hash = :hash AND state = 'REQUESTING'
                """).bind("url", encryptedUrl).bind("expires", expiresAt).bind("now", now)
                .bind("contact", emailHmac).bind("hash", hash).fetch().rowsUpdated()
                .map(count -> count.longValue() == 1L);
    }

    @Override public Mono<Void> transition(byte[] emailHmac, String hash, State state, Instant now) {
        if (state != State.HELD && state != State.CLAIMED) {
            return Mono.error(new IllegalArgumentException("Unsupported partner invite state transition"));
        }
        return database.sql("""
                UPDATE partner_invites
                SET state = :state, encrypted_claim_url = NULL, expires_at = NULL, updated_at = :now
                WHERE email_hmac = :contact AND binding_hash = :hash
                  AND ((:state = 'CLAIMED' AND state IN ('REQUESTING', 'HELD', 'READY'))
                       OR (:state = 'HELD' AND state IN ('REQUESTING', 'HELD')))
                """).bind("state", state.name()).bind("now", now)
                .bind("contact", emailHmac).bind("hash", hash).fetch().rowsUpdated().then();
    }
}
