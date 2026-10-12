package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionSynchronization;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** Transfer ownership only. Callers retain all normal delivery checks and own the transaction. */
public final class CampaignMigrationDeliveryFence {
    private final DatabaseClient db;
    private final OutreachTemplateContentPolicy content = new OutreachTemplateContentPolicy(new TemplateEngine(1_048_576));

    public CampaignMigrationDeliveryFence(DatabaseClient db) { this.db = db; }

    /** Immutable discovery, never authority until exact association is reread under the shared tier. */
    public record Source(UUID campaignId, UUID recipientId, String emailHmac) { }

    /** Opaque, transaction-confined lock receipt. Cannot be constructed or reused after completion. */
    public static final class LockedSources {
        private final List<Source> sources;
        private boolean active = true;
        private boolean lowerTiersLocked;
        private List<UUID> frozenSmtpIds = List.of();
        private LockedSources(List<Source> sources) { this.sources = List.copyOf(sources); }
    }

    public Mono<LockedSources> lockSources(List<Source> selected) {
        List<Source> sources = List.copyOf(selected);
        if (sources.isEmpty() || sources.size() > 20 || sources.stream().anyMatch(s -> s.campaignId()==null
                || s.recipientId()==null || s.emailHmac()==null || !s.emailHmac().matches("[0-9a-f]{64}"))
                || sources.stream().map(Source::recipientId).distinct().count()!=sources.size())
            return Mono.empty();
        UUID[] ids=sources.stream().map(Source::recipientId).toArray(UUID[]::new);
        UUID[] campaigns=sources.stream().map(Source::campaignId).distinct().toArray(UUID[]::new);
        String[] hmacs=sources.stream().map(Source::emailHmac).distinct().sorted().toArray(String[]::new);
        return TransactionSynchronizationManager.forCurrentTransaction().flatMap(manager -> {
            if (!manager.isActualTransactionActive()) return Mono.error(new IllegalStateException("Transfer fence requires a caller transaction"));
            return relatedCampaigns(campaigns,hmacs).flatMap(originalCampaigns -> batchParents(originalCampaigns).flatMap(original ->
                lockParents(original).then(db.sql("SELECT pg_advisory_xact_lock(20260923,1000)").fetch().all().then())
                    .then(db.sql("SELECT id FROM campaigns WHERE id=ANY(:ids) ORDER BY id FOR UPDATE")
                        .bind("ids",originalCampaigns.toArray(UUID[]::new)).fetch().all().then())
                    .then(relatedCampaigns(campaigns,hmacs)).filter(originalCampaigns::equals)
                    .flatMap(ignored -> batchParents(originalCampaigns)).filter(original::equals)
                    .flatMap(ignored -> db.sql("SELECT id FROM campaign_recipients WHERE encode(email_hmac,'hex')=ANY(:hmacs) ORDER BY id FOR UPDATE")
                        .bind("hmacs",hmacs).fetch().all().then(associations(ids)))
                    // Exact associations are authority only after all discovered rows are locked.
                    .filter(actual -> new HashSet<>(actual).equals(new HashSet<>(sources)))
                    .map(ignored -> {
                        LockedSources receipt=new LockedSources(sources);
                        manager.bindResource(receipt,receipt);
                        manager.registerSynchronization(new TransactionSynchronization() {
                            @Override public Mono<Void> suspend() {
                                manager.unbindResourceIfPossible(receipt); return Mono.empty();
                            }
                            @Override public Mono<Void> resume() {
                                manager.bindResource(receipt,receipt); return Mono.empty();
                            }
                            @Override public Mono<Void> afterCompletion(int status) {
                                receipt.active=false; manager.unbindResourceIfPossible(receipt); return Mono.empty();
                            }
                        });
                        return receipt;
                    })));
        });
    }

    /** Only lower tiers here; never reacquire parents, advisory, campaigns or recipients. */
    public Mono<Boolean> verifyLocked(LockedSources receipt, boolean beforePreparation) {
        return verifyLocked(receipt,beforePreparation,List.of());
    }

    /** Frozen IDs must come from the caller's already locked safety run/message, never request input. */
    public Mono<Boolean> verifyLocked(LockedSources receipt, boolean beforePreparation, List<UUID> frozenSmtpIds) {
        if (frozenSmtpIds==null || frozenSmtpIds.size()>20 || frozenSmtpIds.stream().anyMatch(Objects::isNull)) return Mono.just(false);
        List<UUID> frozen=frozenSmtpIds.stream().distinct().sorted().toList();
        return TransactionSynchronizationManager.forCurrentTransaction().flatMap(manager -> {
            if (receipt==null || !receipt.active || !manager.isActualTransactionActive() || manager.getResource(receipt)!=receipt)
                return Mono.just(false);
            if (receipt.lowerTiersLocked && !receipt.frozenSmtpIds.equals(frozen)) return Mono.just(false);
            return lockLowerTiers(receipt,frozen).thenMany(Flux.fromIterable(receipt.sources).concatMap(s -> verify(s.campaignId(),s.recipientId(),
                    HexFormat.of().parseHex(s.emailHmac()),beforePreparation,true))).all(Boolean::booleanValue);
        }).onErrorReturn(org.springframework.transaction.NoTransactionException.class,false);
    }

    private Mono<Void> lockLowerTiers(LockedSources receipt, List<UUID> frozenSmtpIds) {
        if (receipt.lowerTiersLocked) return Mono.empty();
        UUID[] campaigns=receipt.sources.stream().map(Source::campaignId).distinct().toArray(UUID[]::new);
        Mono<Void> locks=Mono.empty();
        for (String[] tier:List.of(new String[]{"email_templates","template_id","FOR SHARE"},
                new String[]{"email_template_versions","template_version_id","FOR SHARE"},
                new String[]{"smtp_accounts","smtp_account_id","FOR UPDATE"},new String[]{"mailbox_accounts","mailbox_account_id","FOR SHARE"})) {
            boolean smtp=tier[0].equals("smtp_accounts");
            var query=db.sql("SELECT id FROM "+tier[0]+" WHERE id IN (SELECT "+tier[1]+" FROM campaigns WHERE id=ANY(:campaigns))"
                    +(smtp ? " OR id=ANY(:frozen)" : "")+" ORDER BY id "+tier[2]).bind("campaigns",campaigns);
            if (smtp) query=query.bind("frozen",frozenSmtpIds.toArray(UUID[]::new));
            locks=locks.then(query.fetch().all().then());
        }
        return locks.thenMany(Flux.fromIterable(receipt.sources.stream().map(Source::emailHmac).distinct().sorted().toList())
            .concatMap(hmac -> {
                Mono<Void> rows=Mono.empty();
                for(String table:List.of("contacts","suppression_entries","unsubscribe_records","recipient_delivery_cooldowns","bulk_outreach_reservations","partner_invites"))
                    rows=rows.then(db.sql("SELECT email_hmac FROM "+table+" WHERE email_hmac=decode(:hmac,'hex') FOR UPDATE")
                        .bind("hmac",hmac).fetch().all().then());
                return rows;
            })).then(Mono.fromRunnable(()->{receipt.frozenSmtpIds=frozenSmtpIds;receipt.lowerTiersLocked=true;}));
    }

    private Mono<List<Source>> associations(UUID[] ids) {
        return db.sql("SELECT id,campaign_id,encode(email_hmac,'hex') AS hmac FROM campaign_recipients WHERE id=ANY(:ids) ORDER BY id")
            .bind("ids",ids).map((r,m)->new Source(r.get("campaign_id",UUID.class),r.get("id",UUID.class),r.get("hmac",String.class))).all().collectList();
    }

    private Mono<List<UUID>> relatedCampaigns(UUID[] campaigns,String[] hmacs) {
        return db.sql("SELECT id FROM campaigns WHERE id=ANY(:campaigns) OR id IN (SELECT campaign_id FROM campaign_recipients WHERE encode(email_hmac,'hex')=ANY(:hmacs)) ORDER BY id")
            .bind("campaigns",campaigns).bind("hmacs",hmacs).map((r,m)->r.get("id",UUID.class)).all().collectList();
    }

    private Mono<List<Parent>> batchParents(List<UUID> campaigns) {
        return db.sql("""
            SELECT 'outreach_runs' AS kind,o.id,o.campaign_id,to_jsonb(o)::text AS association
              FROM outreach_runs o WHERE o.campaign_id=ANY(:campaigns)
            UNION ALL SELECT 'bulk_outreach_runs',p.id,b.campaign_id,
              jsonb_build_array(to_jsonb(b),p.status,p.lock_version)::text
              FROM bulk_outreach_children b JOIN bulk_outreach_runs p ON p.id=b.run_id WHERE b.campaign_id=ANY(:campaigns)
            ORDER BY kind DESC,id,campaign_id
            """).bind("campaigns",campaigns.toArray(UUID[]::new)).map((r,m)->new Parent(r.get("kind",String.class),
                r.get("id",UUID.class),r.get("campaign_id",UUID.class),r.get("association",String.class))).all().collectList();
    }

    public Mono<Boolean> lockAndVerify(UUID campaignId, UUID recipientId, byte[] expectedEmailHmac,
            boolean beforePreparation) {
        return lockAndVerify(campaignId, recipientId, expectedEmailHmac, beforePreparation, false);
    }

    /** Candidate discovery must retain the delivery pump's nonblocking campaign-lock behavior. */
    public Mono<Boolean> lockCandidateAndVerify(UUID campaignId, UUID recipientId, byte[] expectedEmailHmac,
            boolean beforePreparation) {
        return lockAndVerify(campaignId, recipientId, expectedEmailHmac, beforePreparation, true);
    }

    private Mono<Boolean> lockAndVerify(UUID campaignId, UUID recipientId, byte[] expectedEmailHmac,
            boolean beforePreparation, boolean skipLocked) {
        return TransactionSynchronizationManager.forCurrentTransaction().flatMap(manager -> {
            if (!manager.isActualTransactionActive()) return Mono.error(new IllegalStateException("Transfer fence requires a caller transaction"));
            if (campaignId == null || recipientId == null || expectedEmailHmac == null || expectedEmailHmac.length != 32)
                return Mono.just(false);
            return parents(campaignId, expectedEmailHmac).flatMap(original -> lockParents(original)
                    .then(db.sql("SELECT pg_advisory_xact_lock(20260923,1000)").fetch().all().then())
                    .then(lockCampaigns(campaignId, expectedEmailHmac, skipLocked))
                    .filter(Boolean::booleanValue).flatMap(locked -> parents(campaignId, expectedEmailHmac))
                    .flatMap(current -> !original.equals(current) ? Mono.just(false)
                            : db.sql("SELECT id FROM campaign_recipients WHERE email_hmac=:hmac ORDER BY id FOR UPDATE")
                                .bind("hmac", expectedEmailHmac).fetch().all().then(verify(campaignId, recipientId, expectedEmailHmac, beforePreparation))))
                    .defaultIfEmpty(false);
        });
    }

    private Mono<Boolean> lockCampaigns(UUID campaign, byte[] hmac, boolean skipLocked) {
        String sql = "SELECT id FROM campaigns WHERE id=:campaign OR id IN (SELECT campaign_id FROM campaign_recipients WHERE email_hmac=:hmac) ORDER BY id";
        return db.sql(sql).bind("campaign", campaign).bind("hmac", hmac).map((r,m) -> r.get("id", UUID.class)).all().collectList()
                .flatMap(expected -> db.sql(sql + " FOR UPDATE" + (skipLocked ? " SKIP LOCKED" : ""))
                        .bind("campaign", campaign).bind("hmac", hmac).map((r,m) -> r.get("id", UUID.class)).all().collectList()
                        .map(locked -> locked.equals(expected)));
    }

    private Mono<List<Parent>> parents(UUID campaign, byte[] hmac) {
        return db.sql("""
            WITH related AS (SELECT :campaign::uuid AS id UNION SELECT campaign_id FROM campaign_recipients WHERE email_hmac=:hmac)
            SELECT 'outreach_runs' AS kind,o.id,o.campaign_id,to_jsonb(o)::text AS association
              FROM outreach_runs o WHERE o.campaign_id IN(SELECT id FROM related)
            UNION ALL SELECT 'bulk_outreach_runs',p.id,b.campaign_id,
              jsonb_build_array(to_jsonb(b),p.status,p.lock_version)::text
              FROM bulk_outreach_children b JOIN bulk_outreach_runs p ON p.id=b.run_id
              WHERE b.campaign_id IN(SELECT id FROM related)
            ORDER BY kind DESC,id,campaign_id
            """).bind("campaign", campaign).bind("hmac", hmac)
                .map((r,m) -> new Parent(r.get("kind", String.class), r.get("id", UUID.class),
                        r.get("campaign_id", UUID.class), r.get("association", String.class))).all().collectList();
    }

    private Mono<Void> lockParents(List<Parent> parents) {
        return Flux.fromIterable(parents).concatMap(parent -> db.sql("SELECT id FROM " + parent.kind() + " WHERE id=:id FOR UPDATE")
                .bind("id", parent.id()).fetch().all()).then();
    }

    private Mono<Boolean> verify(UUID campaign, UUID recipient, byte[] hmac, boolean beforePreparation) {
        return verify(campaign,recipient,hmac,beforePreparation,false);
    }

    private Mono<Boolean> verify(UUID campaign, UUID recipient, byte[] hmac, boolean beforePreparation, boolean lowerTiersOnly) {
        return db.sql("""
            SELECT EXISTS(SELECT 1 FROM campaign_recipient_transfers t WHERE t.source_recipient_id=r.id) AS source,
              EXISTS(SELECT 1 FROM campaign_recipient_transfers t WHERE t.target_recipient_id=r.id) AS target,
              EXISTS(SELECT 1 FROM campaign_recipient_transfers t JOIN campaign_recipients s ON s.id=t.source_recipient_id
                WHERE s.email_hmac=r.email_hmac) AS transferred_contact
            FROM campaign_recipients r WHERE r.id=:recipient AND r.campaign_id=:campaign AND r.email_hmac=:hmac
            """).bind("campaign", campaign).bind("recipient", recipient).bind("hmac", hmac)
                .map((r,m) -> new Ownership(Boolean.TRUE.equals(r.get("source", Boolean.class)),
                        Boolean.TRUE.equals(r.get("target", Boolean.class)), Boolean.TRUE.equals(r.get("transferred_contact", Boolean.class))))
                .one().flatMap(owner -> owner.source() ? Mono.just(false) : owner.target()
                        ? verifyTarget(campaign, recipient, beforePreparation, lowerTiersOnly) : Mono.just(!owner.transferredContact())).defaultIfEmpty(false);
    }

    private Mono<Boolean> verifyTarget(UUID campaign, UUID recipient, boolean beforePreparation, boolean lowerTiersOnly) {
        return db.sql("""
            SELECT r.contact_id,r.paper_id,r.author_id,r.email_hmac,r.personalization_status,r.attempt_count,
              r.author_name_snapshot,r.paper_title_snapshot,r.paper_abstract_snapshot,r.arxiv_id_snapshot,
              r.category_snapshot,r.organization_snapshot,r.rendered_subject,r.rendered_html,r.rendered_text,
              r.personalization_rationale,t.source_hash,t.content_hash,t.provenance->>'capturedAt' AS captured_at
            FROM campaign_recipient_transfers t
            JOIN campaign_template_migrations m ON m.id=t.migration_id AND m.source_campaign_id=t.source_campaign_id
              AND m.target_campaign_id=t.target_campaign_id AND m.status='MIGRATED'
            JOIN campaign_recipients r ON r.id=t.target_recipient_id AND r.campaign_id=t.target_campaign_id
            JOIN campaigns c ON c.id=r.campaign_id AND c.template_id=m.target_template_id AND c.template_version_id=m.target_template_version_id
            JOIN campaign_recipients s ON s.id=t.source_recipient_id AND s.campaign_id=t.source_campaign_id
            JOIN outreach_content_reviews review ON review.campaign_id=r.campaign_id AND review.recipient_id=r.id
            WHERE r.id=:recipient AND r.campaign_id=:campaign
              AND c.recipient_policy='PAPER_CONTACT' AND c.generation_provider='TEMPLATE' AND c.generation_model='exact-v1'
              AND r.personalization_status='GENERATED' AND s.status='QUEUED' AND s.attempt_count=0
              AND s.contact_id=r.contact_id AND s.paper_id=r.paper_id AND s.email_hmac=r.email_hmac
              AND s.paper_title_snapshot IS NOT DISTINCT FROM r.paper_title_snapshot
              AND (nullif(btrim(s.paper_abstract_snapshot),'') IS NULL OR s.paper_abstract_snapshot=r.paper_abstract_snapshot)
              AND (nullif(btrim(s.arxiv_id_snapshot),'') IS NULL OR s.arxiv_id_snapshot=r.arxiv_id_snapshot)
              AND s.category_snapshot IS NOT DISTINCT FROM r.category_snapshot
              AND s.first_attempt_at IS NULL AND s.delivery_lease_hash IS NULL AND s.delivery_lease_expires_at IS NULL
              AND t.source_hash IS NOT NULL AND t.content_hash IS NOT NULL AND t.provenance IS NOT NULL
              AND t.provenance->>'paperId'=r.paper_id::text AND length(t.provenance->>'capturedAt')>0
              AND EXISTS(SELECT 1 FROM paper_author_contacts evidence WHERE evidence.id::text=t.provenance->>'evidenceId'
                AND evidence.paper_id=r.paper_id AND evidence.contact_id=r.contact_id)
              AND review.policy_version='exact-v1' AND review.source_hash=t.source_hash AND review.content_hash=t.content_hash
              AND NOT EXISTS(SELECT 1 FROM delivery_attempts a WHERE a.campaign_recipient_id=s.id)
              AND NOT EXISTS(SELECT 1 FROM tracking_tokens token WHERE token.campaign_recipient_id=s.id)
              AND NOT EXISTS(SELECT 1 FROM bulk_outreach_reservations br WHERE br.email_hmac=r.email_hmac
                AND (br.campaign_id IS DISTINCT FROM s.campaign_id OR br.recipient_id IS DISTINCT FROM s.id OR br.outcome IS DISTINCT FROM 'QUEUED'))
              AND NOT EXISTS(SELECT 1 FROM bulk_outreach_children child WHERE child.campaign_id=s.campaign_id
                AND NOT EXISTS(SELECT 1 FROM bulk_outreach_reservations br WHERE br.email_hmac=s.email_hmac
                  AND br.run_id=child.run_id AND br.campaign_id=s.campaign_id AND br.recipient_id=s.id AND br.outcome='QUEUED'))
            """).bind("campaign", campaign).bind("recipient", recipient).map((r,m) -> new Proof(
                    new CampaignRepository.FrozenProductionRecipient(r.get("contact_id", UUID.class), r.get("paper_id", UUID.class),
                        r.get("author_id", UUID.class), r.get("email_hmac", byte[].class), r.get("personalization_status", String.class),
                        r.get("attempt_count", Integer.class), true, new CampaignRepository.QueuedTarget(recipient,
                            r.get("author_name_snapshot", String.class), r.get("paper_title_snapshot", String.class),
                            r.get("paper_abstract_snapshot", String.class), r.get("arxiv_id_snapshot", String.class),
                            r.get("category_snapshot", String.class), r.get("organization_snapshot", String.class))),
                    new CampaignRepository.GeneratedDraft(r.get("rendered_subject", String.class), r.get("rendered_html", String.class),
                        r.get("rendered_text", String.class), r.get("personalization_rationale", String.class)),
                    r.get("source_hash", byte[].class), r.get("content_hash", byte[].class), r.get("captured_at", String.class))).one()
                .flatMap(proof -> (lowerTiersOnly ? new OutreachTemplateCampaignRepository(db).lockContextAfterCampaignLock(campaign)
                        : new OutreachTemplateCampaignRepository(db).lockContext(campaign)).flatMap(context -> {
                    try {
                        java.time.Instant.parse(proof.capturedAt());
                        var original = content.render(context.template(), proof.source().target());
                        content.verifyReview(context.template(), proof.source(), original, "exact-v1", proof.sourceHash(), proof.contentHash());
                        if (!context.accountsReady()) return Mono.just(false);
                        if (Arrays.equals(content.contentHash(proof.stored()), proof.contentHash())) return Mono.just(true);
                        return beforePreparation ? Mono.just(false) : matchesPreparedProof(campaign, recipient, proof);
                    } catch (CampaignValidationException | java.time.DateTimeException invalid) { return Mono.just(false); }
                })).defaultIfEmpty(false);
    }

    private Mono<Boolean> matchesPreparedProof(UUID campaign, UUID recipient, Proof proof) {
        return db.sql("""
            SELECT EXISTS(SELECT 1 FROM campaign_prepared_content_proofs p
              JOIN delivery_attempts a ON a.id=p.attempt_id AND a.campaign_recipient_id=p.recipient_id
              JOIN campaign_recipients r ON r.id=p.recipient_id
              WHERE p.campaign_id=:campaign AND p.recipient_id=:recipient
                AND p.source_hash=:source AND p.review_content_hash=:review AND p.prepared_content_hash=:prepared
                AND (p.attempt_number=r.attempt_count OR (p.attempt_number=r.attempt_count-1
                  AND r.status='CONNECTING' AND a.status='TEMPORARY_FAILURE' AND a.retryable))) AS matches
            """).bind("campaign",campaign).bind("recipient",recipient).bind("source",proof.sourceHash())
                .bind("review",proof.contentHash()).bind("prepared",preparedContentHash(proof.stored().subject(),proof.stored().html(),proof.stored().text()))
                .map((r,m)->Boolean.TRUE.equals(r.get("matches",Boolean.class))).one();
    }

    /** Length-prefixed SHA-256 over actual subject/HTML/text, not capability presence or original review hash. */
    public static byte[] preparedContentHash(String subject, String html, String text) {
        return new OutreachTemplateContentPolicy(new TemplateEngine(1_048_576))
                .contentHash(new CampaignRepository.GeneratedDraft(subject,html,text,null));
    }

    private record Parent(String kind, UUID id, UUID campaign, String association) { }
    private record Ownership(boolean source, boolean target, boolean transferredContact) { }
    private record Proof(CampaignRepository.FrozenProductionRecipient source, CampaignRepository.GeneratedDraft stored,
                         byte[] sourceHash, byte[] contentHash, String capturedAt) { }
}
