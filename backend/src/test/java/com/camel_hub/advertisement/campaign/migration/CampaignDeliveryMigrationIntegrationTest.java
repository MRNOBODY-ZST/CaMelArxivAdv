package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.delivery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.campaign.tracking.*;
import com.camel_hub.advertisement.campaign.partner.*;
import com.camel_hub.advertisement.email.tracking.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CampaignDeliveryMigrationIntegrationTest {
    static CampaignTemplateMigrationIntegrationTest.Fixture f;
    @BeforeAll static void database() throws Exception { f = new CampaignTemplateMigrationIntegrationTest.Fixture(); }
    @AfterAll static void close() throws Exception { f.close(); }
    @BeforeEach void seed() throws Exception { f.seed(); f.sql("TRUNCATE partner_invites"); }

    CampaignDeliveryRepository delivery() {
        return new CampaignDeliveryRepository(f.db, f.tx, new CampaignDeliveryProperties(true, 10,
                Duration.ofMinutes(2), Duration.ofDays(180), 3, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1)),
                new CampaignSafetyProperties(false, "", 20));
    }

    @Test void missingOrChangedMigrationProofCannotAuthorizeTargetClaim() throws Exception {
        UUID target = f.migrate("missing-proof").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='" + target + "'");
        f.sql("UPDATE campaign_recipient_transfers SET source_hash=NULL");
        assertThat(delivery().claimNext(Instant.now()).block()).isNull();
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isZero();
    }

    @Test void genuineMigratedTargetCanClaimAndBeginSmtpWithoutChangingSourceHistory() throws Exception {
        UUID target = f.migrate("valid-proof").targetCampaignId();
        String history = f.history();
        var workflow = new CampaignWorkflowRepository(f.db);
        var guard = new CampaignWorkflowRepository.ReadinessGuard(Instant.now().minus(Duration.ofDays(180)), true, true);
        assertThat(workflow.preflight(target, guard.cooldownCutoff()).block().eligible()).isEqualTo(1);
        assertThat(workflow.submitReview(target, 0, f.actor, new byte[32], guard).block().status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(workflow.approve(target, 1, f.actor, new byte[32], guard).block().status()).isEqualTo("APPROVED");
        assertThat(workflow.start(target, 2, f.actor, new byte[32], guard).block().status()).isEqualTo("RUNNING");
        var repository = delivery();
        var claim = (CampaignDeliveryRepository.ProductionClaim) repository.claimNext(Instant.now()).block();
        assertThat(claim).isNotNull();
        assertThat(claim.campaignId()).isEqualTo(target);
        var prepared = tracking().prepare(claim).block();
        assertThat(prepared.html()).doesNotContain("{{unsubscribe_url}}");
        assertThat(f.count("SELECT count(*) FROM tracking_tokens")).isPositive();
        assertThat(repository.beginSmtp(claim, Clock.systemUTC()).block()).isTrue();
        assertThat(f.history()).isEqualTo(history);
    }

    CampaignTrackingService tracking() {
        return tracking(Clock.systemUTC(), Duration.ofDays(30));
    }

    CampaignTrackingService tracking(Clock clock, Duration ttl) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new CampaignTrackingService(new CampaignTrackingRepository(f.db),
                new MailTrackingProperties(true, "https://tracking.example.test", key, ttl),
                new CampaignTrackingSigner(key), new MailOpenClassifier(), clock, f.tx);
    }

    @ParameterizedTest @ValueSource(strings={"rendered_subject", "rendered_html", "rendered_text"})
    void preparedTextTamperWithValidCapabilitiesCannotPassAnyFence(String column) throws Exception {
        UUID target=f.migrate("prepared-tamper").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+target+"'");
        var repository=delivery();
        var claim=(CampaignDeliveryRepository.ProductionClaim)repository.claimNext(Instant.now()).block();
        tracking().prepare(claim).block();
        String tokens=f.value("SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM tracking_tokens t");
        f.sql("UPDATE campaign_recipients SET "+column+"='Altered ordinary text '||"+column+" WHERE id='"+claim.recipientId()+"'");
        assertThat(repository.productionEligible(claim,Clock.systemUTC()).block()).isFalse();
        assertThat(repository.beginSmtp(claim,Clock.systemUTC()).block()).isFalse();
        assertThatThrownBy(()->tracking().prepare(claim).block()).isInstanceOf(IllegalStateException.class);
        assertThat(f.value("SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM tracking_tokens t")).isEqualTo(tokens);
        assertThat(f.count("SELECT count(*) FROM delivery_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
        assertThatThrownBy(()->new R2dbcPartnerInviteRepository(f.db).reserve(claim,"tampered",Instant.now()).block())
                .isInstanceOf(PartnerInviteException.class);
        repository.completeFailure(claim.recipientId(),claim.attemptId(),claim.leaseDigest(),
                new com.camel_hub.advertisement.email.smtp.SmtpTransportException(
                    com.camel_hub.advertisement.email.smtp.SmtpTransportException.FailureCategory.SMTP_REJECTED,
                    CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,CampaignDeliveryModels.TransportStage.RCPT_TO,
                    450,"450 retry later",true),Instant.now()).block();
        assertThat(repository.claimNext(Instant.now().plusSeconds(120)).block()).isNull();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void migratedPreparedRetryPreservesOrRotatesVerifiedContent(boolean rotate) throws Exception {
        UUID target=f.migrate("prepared-retry").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+target+"'");
        Instant firstAt=Instant.now();
        var repository=delivery();
        var first=(CampaignDeliveryRepository.ProductionClaim)repository.claimNext(firstAt).block();
        var prepared=tracking(Clock.fixed(firstAt,ZoneOffset.UTC),Duration.ofMinutes(10)).prepare(first).block();
        repository.completeFailure(first.recipientId(),first.attemptId(),first.leaseDigest(),
                new com.camel_hub.advertisement.email.smtp.SmtpTransportException(
                    com.camel_hub.advertisement.email.smtp.SmtpTransportException.FailureCategory.SMTP_REJECTED,
                    CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,
                    CampaignDeliveryModels.TransportStage.RCPT_TO,450,"450 retry later",true),firstAt).block();
        Instant retryAt=firstAt.plus(Duration.ofMinutes(rotate?11:2));
        var retry=(CampaignDeliveryRepository.ProductionClaim)repository.claimNext(retryAt).block();
        assertThat(retry).isNotNull();
        var retried=tracking(Clock.fixed(retryAt,ZoneOffset.UTC),Duration.ofMinutes(10)).prepare(retry).block();
        assertThat(retried.subject()).isEqualTo(prepared.subject());
        if(rotate) assertThat(retried.html()).isNotEqualTo(prepared.html());
        else assertThat(retried.html()).isEqualTo(prepared.html());
        assertThat(repository.beginSmtp(retry,Clock.fixed(retryAt,ZoneOffset.UTC)).block()).isTrue();
        assertThat(f.count("SELECT count(*) FROM campaign_prepared_content_proofs WHERE recipient_id='"+retry.recipientId()+"'")).isEqualTo(2);
        assertThat(f.count("SELECT count(DISTINCT attempt_id) FROM campaign_prepared_content_proofs")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void discoverySkipsDeniedOrLockedHeadAndClaimsLaterCampaign(boolean locked) throws Exception {
        UUID head=f.migrate("discovery-head").targetCampaignId();
        UUID later=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+head+"'");
        addOrdinaryCampaign(later);
        if(!locked) {
            f.sql("UPDATE campaign_recipient_transfers SET source_hash=NULL");
            for(int i=0;i<2;i++) {
                UUID denied=UUID.randomUUID();
                f.sql("INSERT INTO campaigns SELECT (jsonb_populate_record(NULL::campaigns,to_jsonb(c)||jsonb_build_object('id','"+denied+"'))).* FROM campaigns c WHERE id='"+head+"'");
                f.sql("INSERT INTO campaign_recipients SELECT (jsonb_populate_record(NULL::campaign_recipients,to_jsonb(r)||jsonb_build_object('id',gen_random_uuid(),'campaign_id','"+denied+"'))).* FROM campaign_recipients r WHERE campaign_id='"+head+"'");
            }
        }
        var entered=new java.util.concurrent.CompletableFuture<Void>();
        var release=new java.util.concurrent.CompletableFuture<Void>();
        var blocker=locked ? f.tx.transactional(f.db.sql("SELECT id FROM campaigns WHERE id=:id FOR UPDATE").bind("id",head)
                .fetch().all().then(reactor.core.publisher.Mono.defer(()->{entered.complete(null);return reactor.core.publisher.Mono.fromFuture(release);}))).toFuture() : null;
        try {
            if(locked) entered.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var claim=(CampaignDeliveryRepository.ProductionClaim)delivery().claimNext(Instant.now()).block(Duration.ofSeconds(3));
            assertThat(claim).isNotNull();
            assertThat(claim.campaignId()).isEqualTo(later);
            assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isEqualTo(1);
        } finally {release.complete(null);if(blocker!=null)blocker.get(5,java.util.concurrent.TimeUnit.SECONDS);}
    }

    void addOrdinaryCampaign(UUID id) throws Exception {
        UUID contact=UUID.randomUUID(),recipient=UUID.randomUUID();
        f.sql("INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,smtp_account_id,mailbox_account_id,from_name,from_email,reply_to,status) SELECT '"+id+"','Later','Later',template_id,template_version_id,smtp_account_id,mailbox_account_id,from_name,from_email,reply_to,'RUNNING' FROM campaigns WHERE id='"+f.source+"'");
        f.sql("UPDATE campaigns SET recipient_policy='PAPER_CONTACT',paper_contact_ids=ARRAY['"+f.paper+"'::uuid],paper_contact_recipient_count=1 WHERE id='"+id+"'");
        f.sql("INSERT INTO contacts(id,email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid) SELECT '"+contact+"',email_ciphertext,email_nonce,decode(repeat('09',32),'hex'),email_domain,display_ciphertext,syntax_valid FROM contacts LIMIT 1");
        f.sql("INSERT INTO paper_author_contacts(id,paper_id,contact_id,extraction_run_id,confidence,verification_status) SELECT gen_random_uuid(),paper_id,'"+contact+"',extraction_run_id,confidence,verification_status FROM paper_author_contacts LIMIT 1");
        f.sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) SELECT id,'source.tex','test','test' FROM paper_author_contacts WHERE contact_id='"+contact+"'");
        f.sql("INSERT INTO campaign_recipients(id,campaign_id,contact_id,paper_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,personalization_status,rendered_subject,rendered_html,rendered_text,personalized_at) SELECT '"+recipient+"','"+id+"','"+contact+"',paper_id,email_ciphertext,email_nonce,decode(repeat('09',32),'hex'),email_domain,confidence,'GENERATED',rendered_subject,rendered_html,rendered_text,now() FROM campaign_recipients WHERE id='"+f.recipient+"'");
        f.sql("UPDATE campaign_recipients SET rendered_html='Hello {{unsubscribe_url}}',rendered_text='Hello {{unsubscribe_url}}' WHERE id='"+recipient+"'");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void continuationAcrossPumpsPassesAFullDeniedOrLockedCampaign(boolean locked) throws Exception {
        UUID head=fullDeniedCampaign();
        UUID later=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        addOrdinaryCampaign(later);
        String history=rejectedHistory(head);
        var repository=delivery();
        var entered=new java.util.concurrent.CompletableFuture<Void>();
        var release=new java.util.concurrent.CompletableFuture<Void>();
        var blocker=locked?f.tx.transactional(f.db.sql("SELECT id FROM campaigns WHERE id=:id FOR UPDATE").bind("id",head)
                .fetch().all().then(reactor.core.publisher.Mono.defer(()->{entered.complete(null);return reactor.core.publisher.Mono.fromFuture(release);}))).toFuture():null;
        try {
            if(locked)entered.get(5,java.util.concurrent.TimeUnit.SECONDS);
            // This null is also the work-bound assertion: an unbounded scan would already claim later.
            assertThat(repository.claimNext(Instant.now()).block(Duration.ofSeconds(30))).isNull();
            assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isZero();
            var claim=(CampaignDeliveryRepository.ProductionClaim)repository.claimNext(Instant.now()).block(Duration.ofSeconds(30));
            assertThat(claim).isNotNull();assertThat(claim.campaignId()).isEqualTo(later);
            assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isEqualTo(1);
            assertThat(rejectedHistory(head)).isEqualTo(history);
        }finally{release.complete(null);if(blocker!=null)blocker.get(5,java.util.concurrent.TimeUnit.SECONDS);}
    }

    @Test void continuationTraversesMultipleBudgetsThenResetsAfterSuccess() throws Exception {
        UUID head=fullDeniedCampaign();
        String proof=originalSourceHash;
        UUID second=UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe");
        UUID later=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        f.sql("INSERT INTO campaigns SELECT (jsonb_populate_record(NULL::campaigns,to_jsonb(c)||jsonb_build_object('id','"+second+"'))).* FROM campaigns c WHERE id='"+head+"'");
        f.sql("INSERT INTO campaign_recipients SELECT (jsonb_populate_record(NULL::campaign_recipients,to_jsonb(r)||jsonb_build_object('id',gen_random_uuid(),'campaign_id','"+second+"'))).* FROM campaign_recipients r WHERE campaign_id='"+head+"'");
        addOrdinaryCampaign(later);
        String history=rejectedHistory(head),secondHistory=rejectedHistory(second);
        var repository=delivery();
        assertThat(repository.claimNext(Instant.now()).block(Duration.ofSeconds(30))).isNull();
        assertThat(repository.claimNext(Instant.now()).block(Duration.ofSeconds(30))).isNull();
        var claim=(CampaignDeliveryRepository.ProductionClaim)repository.claimNext(Instant.now()).block(Duration.ofSeconds(30));
        assertThat(claim).isNotNull();assertThat(claim.campaignId()).isEqualTo(later);
        assertThat(rejectedHistory(head)).isEqualTo(history);assertThat(rejectedHistory(second)).isEqualTo(secondHistory);
        restoreOriginalProof(proof);
        var earlier=(CampaignDeliveryRepository.ProductionClaim)repository.claimNext(Instant.now()).block(Duration.ofSeconds(30));
        assertThat(earlier).isNotNull();assertThat(earlier.campaignId()).isEqualTo(head);
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isEqualTo(2);
    }

    @Test void continuationWrapsExhaustedTailToEarlierNowEligibleWork() throws Exception {
        UUID head=fullDeniedCampaign();
        var repository=delivery();
        assertThat(repository.claimNext(Instant.now()).block(Duration.ofSeconds(30))).isNull();
        restoreOriginalProof(originalSourceHash);
        var claim=(CampaignDeliveryRepository.ProductionClaim)repository.claimNext(Instant.now()).block(Duration.ofSeconds(30));
        assertThat(claim).isNotNull();assertThat(claim.campaignId()).isEqualTo(head);
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isEqualTo(1);
    }

    @Test void concurrentContinuationPumpsMakeProgressWithoutDuplicateAttempts() throws Exception {
        UUID head=fullDeniedCampaign();
        UUID later=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        addOrdinaryCampaign(later);
        String history=rejectedHistory(head);
        var repository=delivery();
        var claims=new ArrayList<CampaignDeliveryRepository.DeliveryClaim>();
        for(int round=0;round<3;round++)claims.addAll(reactor.core.publisher.Flux.range(0,2)
                .flatMap(ignored->repository.claimNext(Instant.now()),2).collectList().block(Duration.ofSeconds(45)));
        assertThat(claims).hasSize(1);
        assertThat(((CampaignDeliveryRepository.ProductionClaim)claims.getFirst()).campaignId()).isEqualTo(later);
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isEqualTo(1);
        assertThat(rejectedHistory(head)).isEqualTo(history);
    }

    String originalSourceHash;
    UUID fullDeniedCampaign() throws Exception {
        UUID head=f.migrate("full-denied-head").targetCampaignId();
        originalSourceHash=f.value("SELECT encode(source_hash,'hex') FROM campaign_recipient_transfers WHERE source_recipient_id='"+f.recipient+"'");
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+head+"'");
        f.sql("UPDATE campaign_recipients SET next_attempt_at='1900-01-01' WHERE campaign_id='"+head+"'");
        f.sql("""
            WITH ids AS (SELECT gen_random_uuid() AS source_id,gen_random_uuid() AS target_id,
              public.digest('denied-'||n::text,'sha256') AS hmac FROM generate_series(1,119) n),
            sources AS (INSERT INTO campaign_recipients SELECT (jsonb_populate_record(NULL::campaign_recipients,
              to_jsonb(r)||jsonb_build_object('id',i.source_id,'email_hmac',i.hmac))).*
              FROM ids i CROSS JOIN campaign_recipients r WHERE r.id='%s' RETURNING id,email_hmac),
            targets AS (INSERT INTO campaign_recipients SELECT (jsonb_populate_record(NULL::campaign_recipients,
              to_jsonb(r)||jsonb_build_object('id',i.target_id,'email_hmac',i.hmac,'next_attempt_at','2000-01-01'))).*
              FROM ids i CROSS JOIN campaign_recipients r WHERE r.campaign_id='%s' RETURNING id,email_hmac)
            INSERT INTO campaign_recipient_transfers(migration_id,source_campaign_id,source_recipient_id,target_campaign_id,target_recipient_id)
              SELECT t.migration_id,t.source_campaign_id,s.id,t.target_campaign_id,r.id
              FROM sources s JOIN targets r ON r.email_hmac=s.email_hmac
              CROSS JOIN campaign_recipient_transfers t WHERE t.source_recipient_id='%s'
            """.formatted(f.recipient,head,f.recipient));
        f.sql("UPDATE campaign_recipient_transfers SET source_hash=NULL");
        assertThat(f.count("SELECT count(*) FROM campaign_recipients WHERE campaign_id='"+head+"'")).isEqualTo(120);
        return head;
    }

    void restoreOriginalProof(String hash) throws Exception {
        f.sql("UPDATE campaign_recipient_transfers SET source_hash=decode('"+hash+"','hex') WHERE source_recipient_id='"+f.recipient+"'");
    }

    String rejectedHistory(UUID campaign) throws Exception {
        return f.value("SELECT jsonb_build_array((SELECT to_jsonb(c) FROM campaigns c WHERE id='"+campaign+"'),"
                +"(SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM campaign_recipients r WHERE campaign_id='"+campaign+"'))::text");
    }

    @ParameterizedTest @ValueSource(strings={"NONE","REQUESTING","READY","HELD"})
    void partnerLeaseExpiringWhileWaitingCannotReachRemote(String existing) throws Exception {
        UUID target=f.migrate("partner-expiry").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+target+"'");
        var claim=(CampaignDeliveryRepository.ProductionClaim)delivery().claimNext(Instant.now()).block();
        var clock=new MutableClock(Instant.now());
        Instant expiry=clock.instant().plusSeconds(60);
        f.sql("UPDATE campaign_recipients SET delivery_lease_expires_at='"+expiry+"' WHERE id='"+claim.recipientId()+"'");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        PartnerInviteClient fake=new PartnerInviteClient(){
            public reactor.core.publisher.Mono<PendingInvite> create(String hash,String email){calls.incrementAndGet();return reactor.core.publisher.Mono.error(new AssertionError("POST"));}
            public reactor.core.publisher.Mono<IdentityStatus> identity(String hash){calls.incrementAndGet();return reactor.core.publisher.Mono.just(IdentityStatus.PENDING);}
        };
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        if(!existing.equals("NONE")) f.sql("INSERT INTO partner_invites(email_hmac,binding_hash,state,expires_at,encrypted_claim_url,created_at,updated_at) VALUES (decode(repeat('03',32),'hex'),'legacy','"+existing+"',"
                +(existing.equals("READY")?"now()+interval '1 hour',decode(repeat('01',32),'hex')":"NULL,NULL")+",now(),now())");
        String before=f.value("SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]')::text FROM partner_invites p");
        var service=new PartnerInviteService(new R2dbcPartnerInviteRepository(f.db,clock),fake,new PartnerInviteCrypto(key),
                new PartnerInviteProperties(true,"synthetic-only",key,Duration.ofSeconds(1)),clock);
        var entered=new java.util.concurrent.CompletableFuture<Void>();
        var release=new java.util.concurrent.CompletableFuture<Void>();
        var blocker=f.tx.transactional(f.db.sql("SELECT pg_advisory_xact_lock(20260923,1000)").fetch().all()
                .then(reactor.core.publisher.Mono.defer(()->{entered.complete(null);return reactor.core.publisher.Mono.fromFuture(release);}))).toFuture();
        try {
            entered.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var pending=service.prepareProduction(claim,"synthetic@example.test").toFuture();
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(f.count("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid<>pg_backend_pid()")==0 && System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(pending).isNotDone();
            assertThat(f.count("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid<>pg_backend_pid()")).isPositive();
            clock.now=expiry;
            release.complete(null);
            assertThatThrownBy(()->pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(PartnerInviteException.class);
            assertThat(calls.get()).isZero();
            assertThat(f.value("SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]')::text FROM partner_invites p")).isEqualTo(before);
        } finally {release.complete(null);blocker.get(5,java.util.concurrent.TimeUnit.SECONDS);}
    }

    static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now){this.now=now;}
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
    }

    @Test void receiptOwnershipAndHistoryCannotBeReboundOrCascadeDeleted() throws Exception {
        UUID target=f.migrate("receipt-ownership").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+target+"'");
        var claim=(CampaignDeliveryRepository.ProductionClaim)delivery().claimNext(Instant.now()).block();
        tracking().prepare(claim).block();
        assertThatThrownBy(()->f.sql("UPDATE campaign_prepared_content_proofs SET campaign_id='"+f.source+"'"))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("foreign key");
        assertThatThrownBy(()->f.sql("UPDATE campaign_prepared_content_proofs SET attempt_id='"+UUID.randomUUID()+"'"))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("foreign key");
        assertThatThrownBy(()->f.sql("DELETE FROM delivery_attempts WHERE id='"+claim.attemptId()+"'"))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("foreign key");
        assertThat(f.count("SELECT count(*) FROM campaign_prepared_content_proofs")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"DELETE FROM campaign_prepared_content_proofs",
            "UPDATE campaign_prepared_content_proofs SET prepared_content_hash=decode(repeat('00',32),'hex')",
            "UPDATE campaign_prepared_content_proofs SET source_hash=decode(repeat('00',32),'hex')",
            "UPDATE campaign_prepared_content_proofs SET review_content_hash=decode(repeat('00',32),'hex')"})
    void missingOrChangedPreparedReceiptCannotBeRecreatedFromFrozenContent(String mutation) throws Exception {
        UUID target=f.migrate("receipt-proof").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+target+"'");
        var claim=(CampaignDeliveryRepository.ProductionClaim)delivery().claimNext(Instant.now()).block();
        tracking().prepare(claim).block();
        f.sql(mutation);
        String receipts=f.value("SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]')::text FROM campaign_prepared_content_proofs p");
        assertThat(delivery().productionEligible(claim,Clock.systemUTC()).block()).isFalse();
        assertThatThrownBy(()->tracking().prepare(claim).block()).isInstanceOf(IllegalStateException.class);
        assertThat(f.value("SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]')::text FROM campaign_prepared_content_proofs p")).isEqualTo(receipts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source_hash=NULL", "content_hash=NULL", "provenance=NULL",
            "source_hash=decode(repeat('00',32),'hex')", "content_hash=decode(repeat('00',32),'hex')",
            "provenance=jsonb_set(provenance,'{capturedAt}','\"unknown\"'::jsonb)"})
    void incompleteAndTamperedProofNeverClaims(String mutation) throws Exception {
        UUID target = f.migrate("proof-mutation").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='" + target + "'");
        f.sql("UPDATE campaign_recipient_transfers SET " + mutation);
        assertThat(delivery().claimNext(Instant.now()).block()).isNull();
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isZero();
    }

    @Test void missingOriginalBulkReservationCannotAuthorizeTarget() throws Exception {
        bulkReservation();
        UUID target = f.migrate("missing-reservation").targetCampaignId();
        f.sql("DELETE FROM bulk_outreach_reservations"); // Adversarial fixture only: product must never do this.
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='" + target + "'");
        assertThat(delivery().claimNext(Instant.now()).block()).isNull();
    }

    @Test void originalBulkReservationRemainsByteForByteWhileTargetPrepares() throws Exception {
        bulkReservation();
        String original = f.value("SELECT to_jsonb(r)::text FROM bulk_outreach_reservations r");
        UUID target = f.migrate("retained-reservation").targetCampaignId();
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='" + target + "'");
        var claim = (CampaignDeliveryRepository.ProductionClaim) delivery().claimNext(Instant.now()).block();
        assertThat(claim).isNotNull();
        assertThat(tracking().prepare(claim).block()).isNotNull();
        assertThat(delivery().beginSmtp(claim, Clock.systemUTC()).block()).isTrue();
        assertThat(f.value("SELECT to_jsonb(r)::text FROM bulk_outreach_reservations r")).isEqualTo(original);
    }

    void bulkReservation() throws Exception {
        UUID parent = UUID.randomUUID();
        f.sql("INSERT INTO bulk_outreach_runs(id,actor_id,idempotency_key,payload_hash,command,configuration,request_context,state,status) VALUES ('"+parent+"','"+f.actor+"','"+parent+"',decode(repeat('01',32),'hex'),'{}','{}','{}','{}','BLOCKED')");
        f.sql("INSERT INTO bulk_outreach_children(run_id,campaign_id,selected_count) VALUES ('"+parent+"','"+f.source+"',1)");
        f.sql("INSERT INTO bulk_outreach_reservations(email_hmac,run_id,campaign_id,recipient_id) SELECT email_hmac,'"+parent+"',campaign_id,id FROM campaign_recipients WHERE id='"+f.recipient+"'");
    }

    @Test void legacyPartnerReservationCannotBypassTransferThroughExistingInvite() throws Exception {
        byte[] hmac = HexFormat.of().parseHex("03".repeat(32));
        var repository = new com.camel_hub.advertisement.campaign.partner.R2dbcPartnerInviteRepository(f.db);
        f.migrate("partner-fence");
        assertThatThrownBy(() -> repository.reserve(hmac, "binding", Instant.now()).block())
                .isInstanceOf(com.camel_hub.advertisement.campaign.partner.PartnerInviteException.class);
        assertThat(f.count("SELECT count(*) FROM partner_invites")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"paper_title_snapshot='Changed source'", "paper_abstract_snapshot='Changed abstract'", "arxiv_id_snapshot='2610.00001'"})
    void changedRetainedFrozenSourceCannotAuthorizeTarget(String mutation) throws Exception {
        UUID target = f.migrate("source-changed").targetCampaignId();
        f.sql("UPDATE campaign_recipients SET " + mutation + " WHERE id='" + f.recipient + "'");
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='" + target + "'");
        assertThat(delivery().claimNext(Instant.now()).block()).isNull();
    }

    @Test void legacyPartnerExistingRowsCannotTriggerAnyRemoteBranch() throws Exception {
        f.migrate("legacy-existing");
        byte[] hmac = HexFormat.of().parseHex("03".repeat(32));
        var remoteCalls = new java.util.concurrent.atomic.AtomicInteger();
        PartnerInviteClient fake = new PartnerInviteClient() {
            public reactor.core.publisher.Mono<PendingInvite> create(String hash, String email) {
                remoteCalls.incrementAndGet(); return reactor.core.publisher.Mono.error(new AssertionError("Unexpected POST"));
            }
            public reactor.core.publisher.Mono<IdentityStatus> identity(String hash) {
                remoteCalls.incrementAndGet(); return reactor.core.publisher.Mono.just(IdentityStatus.PENDING);
            }
        };
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var repository = new R2dbcPartnerInviteRepository(f.db);
        var service = new PartnerInviteService(repository, fake, new PartnerInviteCrypto(key),
                new PartnerInviteProperties(true,"synthetic-only",key,Duration.ofSeconds(1)), Clock.systemUTC());
        for (String state : List.of("REQUESTING", "HELD", "READY")) {
            f.sql("DELETE FROM partner_invites");
            f.sql("INSERT INTO partner_invites(email_hmac,binding_hash,state,expires_at,encrypted_claim_url,created_at,updated_at) VALUES (decode(repeat('03',32),'hex'),'legacy', '"+state+"',"
                    + (state.equals("READY") ? "now()+interval '1 hour',decode(repeat('01',32),'hex')" : "NULL,NULL") + ",now(),now())");
            assertThatThrownBy(() -> service.prepare(hmac,"synthetic@example.test").block()).isInstanceOf(PartnerInviteException.class);
        }
        assertThat(remoteCalls.get()).isZero();
    }

    @Test void helperRequiresCallerTransaction() {
        assertThatThrownBy(() -> new CampaignMigrationDeliveryFence(f.db)
                .lockAndVerify(f.source,f.recipient,HexFormat.of().parseHex("03".repeat(32)),true).block())
                .isInstanceOf(org.springframework.transaction.NoTransactionException.class);
    }

    @Test void lateGenerationResultCannotRewriteTransferredFailureEvidence() throws Exception {
        UUID job=UUID.randomUUID();
        f.sql("UPDATE campaigns SET generation_job_id='"+job+"',generation_status='FAILED' WHERE id='"+f.source+"'");
        f.sql("UPDATE campaign_recipients SET personalization_status='FAILED',personalization_error_code='OLD_FAILURE' WHERE id='"+f.recipient+"'");
        f.migrate("late-generation");
        String history=f.history();
        var campaigns=new CampaignRepository(f.db);
        assertThat(campaigns.storeGenerated(f.source,f.recipient,job,new CampaignRepository.GeneratedDraft("Late","Late html","Late text","Late rationale")).block()).isFalse();
        assertThat(campaigns.storeFailed(f.source,f.recipient,job,"LATE","Late error").block()).isFalse();
        campaigns.refreshGenerationState(f.source,job).block();
        assertThat(f.history()).isEqualTo(history);
        assertThat(f.value("SELECT generation_status FROM campaigns WHERE id='"+f.source+"'")).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tracking", "partner", "smtp"})
    void committedPreparationWinnerMakesConcurrentMigrationFailWithoutDeadlock(String boundary) throws Exception {
        f.sql("UPDATE campaigns SET recipient_policy='PAPER_CONTACT',paper_contact_ids=ARRAY['"+f.paper+"'::uuid],paper_contact_recipient_count=1 WHERE id='"+f.source+"'");
        f.sql("UPDATE campaign_recipients SET author_name_snapshot=NULL,rendered_html='<p>Hello</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>',rendered_text='Hello {{unsubscribe_url}}' WHERE id='"+f.recipient+"'");
        var command = f.command("contended-"+boundary);
        f.sql("UPDATE campaigns SET status='RUNNING' WHERE id='"+f.source+"'");
        var claim = (CampaignDeliveryRepository.ProductionClaim) delivery().claimNext(Instant.now()).block();
        assertThat(claim).isNotNull();
        reactor.core.publisher.Mono<Boolean> operation = switch(boundary) {
            case "tracking" -> tracking().prepare(claim).map(prepared -> true);
            case "partner" -> new R2dbcPartnerInviteRepository(f.db).reserve(claim,"reserved-before-network",Instant.now());
            default -> delivery().beginSmtp(claim,Clock.systemUTC());
        };
        var entered = new java.util.concurrent.CompletableFuture<Boolean>();
        var release = new java.util.concurrent.CompletableFuture<Void>();
        var winner = f.tx.transactional(operation.flatMap(done -> {
            entered.complete(done);
            return reactor.core.publisher.Mono.fromFuture(release).thenReturn(done);
        })).toFuture();
        try {
            assertThat(entered.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var migration = f.service.migrate(f.source,f.actor,CampaignTemplateMigrationIntegrationTest.Fixture.CONTEXT,command).toFuture();
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(f.count("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid<>pg_backend_pid()") == 0 && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(migration).isNotDone();
            assertThat(f.count("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid<>pg_backend_pid()")).isPositive();
            release.complete(null);
            assertThat(winner.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> migration.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(CampaignConflictException.class);
        } finally { release.complete(null); }
        assertThat(f.count("SELECT count(*) FROM campaign_recipient_transfers")).isZero();
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM tracking_tokens")).isEqualTo(boundary.equals("tracking") ? 1 : 0);
        assertThat(f.count("SELECT count(*) FROM partner_invites")).isEqualTo(boundary.equals("partner") ? 1 : 0);
        assertThat(f.count("SELECT count(*) FROM delivery_attempts WHERE smtp_started_at IS NOT NULL")).isEqualTo(boundary.equals("smtp") ? 1 : 0);
    }

    @Test void generationResultWaitingForMigrationCommitCannotOverwriteSource() throws Exception {
        UUID job=UUID.randomUUID();
        f.sql("UPDATE campaigns SET generation_job_id='"+job+"',generation_status='FAILED' WHERE id='"+f.source+"'");
        f.sql("UPDATE campaign_recipients SET personalization_status='FAILED' WHERE id='"+f.recipient+"'");
        var command=f.command("migration-first");
        var entered=new java.util.concurrent.CompletableFuture<Void>();
        var release=new java.util.concurrent.CompletableFuture<Void>();
        var migration=f.tx.transactional(f.service.migrate(f.source,f.actor,CampaignTemplateMigrationIntegrationTest.Fixture.CONTEXT,command)
                .flatMap(result->{entered.complete(null);return reactor.core.publisher.Mono.fromFuture(release).thenReturn(result);})).toFuture();
        try {
            entered.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var late=new CampaignRepository(f.db).storeGenerated(f.source,f.recipient,job,
                    new CampaignRepository.GeneratedDraft("Late","Late html","Late text","Late rationale")).toFuture();
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(f.count("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid<>pg_backend_pid()") == 0 && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(late).isNotDone();
            release.complete(null);
            assertThat(migration.get(5,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("MIGRATED");
            assertThat(late.get(5,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        } finally {release.complete(null);}
        assertThat(f.value("SELECT personalization_status FROM campaign_recipients WHERE id='"+f.recipient+"'")).isEqualTo("FAILED");
    }
}
