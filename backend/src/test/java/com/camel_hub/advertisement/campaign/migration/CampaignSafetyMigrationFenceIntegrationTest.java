package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.CampaignConflictException;
import com.camel_hub.advertisement.campaign.delivery.*;
import com.camel_hub.advertisement.campaign.safety.*;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.smtp.*;
import com.camel_hub.advertisement.email.tracking.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CampaignSafetyMigrationFenceIntegrationTest {
    static CampaignTemplateMigrationIntegrationTest.Fixture f;
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    CampaignSafetyRepository safety;
    CampaignDeliveryRepository delivery;
    CampaignSafetyRuntimePolicy policy;
    CampaignSafetyTrackingService tracking;
    Instant now;
    @BeforeAll static void database() throws Exception { f=new CampaignTemplateMigrationIntegrationTest.Fixture(); }
    @AfterAll static void close() throws Exception { f.close(); }
    @BeforeEach void seed() throws Exception {
        f.seed(); now=Instant.now();
        for(String table:List.of("campaign_safety_runs","campaign_safety_messages","campaign_safety_attempts","campaign_safety_links","campaign_safety_events"))
            assertThat(f.value("SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.oid=to_regclass('"+table+"')")).isEqualTo(f.schema);
        f.sql("UPDATE campaign_recipients SET rendered_html='<p>Old</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>',rendered_text='Old {{unsubscribe_url}}'");
        var properties=new CampaignSafetyProperties(true,"fixed@example.test",20);
        var signer=new CampaignSafetySigner(KEY);
        var config=new MailTrackingProperties(true,"https://tracking.example.test",KEY,Duration.ofDays(30),Duration.ofMinutes(15));
        policy=new CampaignSafetyRuntimePolicy(properties,new SmtpProperties(true,Set.of("localhost"),Duration.ofSeconds(2),Duration.ofSeconds(2),Duration.ofSeconds(2),""),config,signer,Duration.ofMinutes(2));
        safety=new CampaignSafetyRepository(f.db,f.tx,new ObjectMapper().findAndRegisterModules());
        delivery=new CampaignDeliveryRepository(f.db,f.tx,new CampaignDeliveryProperties(true,20,Duration.ofMinutes(2),Duration.ofDays(180),3,Duration.ofMinutes(1),Duration.ofMinutes(5),Duration.ofSeconds(1)),properties,policy);
        tracking=new CampaignSafetyTrackingService(safety,policy,config,signer,new MailOpenClassifier(),Clock.fixed(now,ZoneOffset.UTC),f.tx);
    }
    CampaignSafetyRepository.MaterializedRun materialize(UUID campaign) throws Exception {
        return safety.materialize(new CampaignSafetyRepository.MaterializeCommand(campaign,f.actor,
                f.count("SELECT lock_version FROM campaigns WHERE id='"+campaign+"'"),20,
                policy.requireReady().hmac(),policy.requireReady().masked(),now,"synthetic")).block();
    }
    CampaignDeliveryRepository.SafetyClaim claim() { return (CampaignDeliveryRepository.SafetyClaim)delivery.claimNext(now).block(); }

    @Test void deniedMigratedSafetyHeadAllowsLaterSafetyWithoutChangingHistory() throws Exception {
        UUID denied=f.migrate("denied-head").targetCampaignId();materialize(denied);
        f.sql("UPDATE campaign_safety_runs SET created_at='1900-01-01'");
        UUID good=ordinaryCampaign();materialize(good);
        f.sql("UPDATE smtp_accounts SET enabled=false WHERE id='"+f.smtp+"'");
        String history=deniedHistory(good);
        assertThat(claimedCampaign()).isEqualTo(good);
        assertThat(delivery.claimNext(now).block()).isNull();
        assertThat(deniedHistory(good)).isEqualTo(history);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isEqualTo(1);
    }

    @Test void allDeniedSafetyAllowsSeparatelyFencedProduction() throws Exception {
        UUID denied=f.migrate("denied-production").targetCampaignId();materialize(denied);
        UUID good=ordinaryCampaign();
        f.sql("UPDATE smtp_accounts SET enabled=false WHERE id='"+f.smtp+"'");
        String history=deniedHistory(good);
        assertThat(delivery.claimNext(now).block()).isInstanceOfSatisfying(CampaignDeliveryRepository.ProductionClaim.class,
                c->assertThat(c.campaignId()).isEqualTo(good));
        assertThat(deniedHistory(good)).isEqualTo(history);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isZero();
    }

    @Test void multipleSafetyBudgetsContinueThenResetToEarlierRestoredWork() throws Exception {
        materialize(f.source);deniedPrefix(241);
        String history=deniedHistory(f.source);
        assertThat(claim()).isNull();assertThat(claim()).isNull();
        assertThat(claimedCampaign()).isEqualTo(f.source);
        assertThat(deniedHistory(f.source)).isEqualTo(history);
        UUID restored=restoreFirstDenied();
        assertThat(claimedCampaign()).isEqualTo(restored);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isEqualTo(2);
    }

    @Test void fullSafetyBudgetAllowsProductionWithoutLosingSafetyContinuation() throws Exception {
        materialize(f.source);deniedPrefix(120);UUID production=ordinaryCampaign();
        String history=deniedHistory(f.source,production);
        assertThat(delivery.claimNext(now).block()).isInstanceOfSatisfying(CampaignDeliveryRepository.ProductionClaim.class,
                c->assertThat(c.campaignId()).isEqualTo(production));
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isZero();
        assertThat(claimedCampaign()).isEqualTo(f.source);
        assertThat(delivery.claimNext(now).block()).isNull();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isEqualTo(1);
        assertThat(deniedHistory(f.source,production)).isEqualTo(history);
    }

    @Test void safetyContinuationWrapsWhenEarlierWorkBecomesEligible() throws Exception {
        materialize(f.source);deniedPrefix(120);
        f.sql("UPDATE campaign_safety_messages SET next_attempt_at='2100-01-01' WHERE campaign_recipient_id='"+f.recipient+"'");
        assertThat(claim()).isNull();UUID restored=restoreFirstDenied();
        assertThat(claimedCampaign()).isEqualTo(restored);
    }

    @Test void safetyCursorIncludesMessageDueTimeAndIdWithinOneRun() throws Exception {
        materialize(f.source);UUID wrong=anotherSmtp(),last=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        f.sql("UPDATE campaign_safety_messages SET id='"+last+"',next_attempt_at='2000-01-01'");
        for(int n=1;n<=2;n++) {
            UUID recipient=prefixId("same-run",n);
            f.sql("INSERT INTO campaign_recipients SELECT (jsonb_populate_record(NULL::campaign_recipients,to_jsonb(r)||jsonb_build_object('id','"+recipient+"','email_hmac',decode(repeat('0"+(n+3)+"',32),'hex')))).* FROM campaign_recipients r WHERE id='"+f.recipient+"'");
            f.sql("INSERT INTO campaign_safety_messages SELECT (jsonb_populate_record(NULL::campaign_safety_messages,to_jsonb(m)||jsonb_build_object('id','00000000-0000-0000-0000-00000000000"+n+"','campaign_recipient_id','"+recipient+"','smtp_account_id','"+wrong+"','rfc_message_id',NULL,'next_attempt_at','"+(n==1?"1900":"2000")+"-01-01'))).* FROM campaign_safety_messages m WHERE id='"+last+"'");
        }
        String before=f.value("SELECT jsonb_agg(to_jsonb(m) ORDER BY id)::text FROM campaign_safety_messages m WHERE id<>'"+last+"'");
        assertThat(claim().messageId()).isEqualTo(last);
        assertThat(f.value("SELECT jsonb_agg(to_jsonb(m) ORDER BY id)::text FROM campaign_safety_messages m WHERE id<>'"+last+"'")).isEqualTo(before);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isEqualTo(1);
    }

    @Test void lockedRunAndRejectedAssociationDoNotStarveLaterSafety() throws Exception {
        materialize(f.source);deniedPrefix(1);
        f.sql("UPDATE campaign_safety_messages SET smtp_account_id='"+f.smtp+"' WHERE run_id='"+prefixId("run",1)+"'");
        String history=deniedHistory(f.source);
        f.jdbc.setAutoCommit(false);
        try {
            f.sql("SELECT id FROM campaign_safety_runs WHERE id='"+prefixId("run",1)+"' FOR UPDATE");
            assertThat(claimedCampaign()).isEqualTo(f.source);
        } finally {f.jdbc.rollback();f.jdbc.setAutoCommit(true);}
        assertThat(deniedHistory(f.source)).isEqualTo(history);
    }

    @Test void concurrentSafetyContinuationPumpsDoNotDuplicateAttempts() throws Exception {
        materialize(f.source);deniedPrefix(241);String history=deniedHistory(f.source);
        var results=new ArrayList<CampaignDeliveryRepository.DeliveryClaim>();
        for(int pump=0;pump<4;pump++) {
            var first=delivery.claimNext(now).toFuture();var second=delivery.claimNext(now).toFuture();
            var a=first.get(30,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(30,java.util.concurrent.TimeUnit.SECONDS);
            if(a!=null)results.add(a);if(b!=null)results.add(b);
        }
        assertThat(results).hasSize(1);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isEqualTo(1);
        assertThat(deniedHistory(f.source)).isEqualTo(history);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"health","control","quota"})
    void realSafetyHandlingStillSuppressesProductionForThatPump(String action) throws Exception {
        materialize(f.source);UUID production=ordinaryCampaign();
        if(action.equals("health"))f.sql("UPDATE smtp_accounts SET enabled=false WHERE id='"+f.smtp+"'");
        if(action.equals("control"))f.sql("UPDATE campaign_safety_runs SET destination_hmac=decode(repeat('00',32),'hex')");
        if(action.equals("quota")) {
            deniedPrefix(1);restoreFirstDenied();
            f.sql("UPDATE smtp_accounts SET per_minute_limit=1 WHERE id='"+f.smtp+"'");
            assertThat(claimedCampaign()).isEqualTo(prefixId("campaign",1));
        }
        assertThat(delivery.claimNext(now).block()).isNull();
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isZero();
        if(action.equals("quota"))
            assertThat(f.count("SELECT count(*) FROM campaign_safety_messages WHERE campaign_recipient_id='"+f.recipient+"' AND next_attempt_at>'"+now+"'")).isEqualTo(1);
        else assertThat(f.value("SELECT status FROM campaign_safety_runs WHERE campaign_id='"+f.source+"'")).isEqualTo("FAILED");
        assertThat(delivery.claimNext(now).block()).isInstanceOfSatisfying(CampaignDeliveryRepository.ProductionClaim.class,
                c->assertThat(c.campaignId()).isEqualTo(production));
    }

    private UUID claimedCampaign() throws Exception {
        var c=claim();assertThat(c).isNotNull();
        return UUID.fromString(f.value("SELECT campaign_id FROM campaign_safety_runs WHERE id='"+c.runId()+"'"));
    }

    private UUID prefixId(String kind,int n) {return UUID.nameUUIDFromBytes((kind+n).getBytes(java.nio.charset.StandardCharsets.UTF_8));}

    private void deniedPrefix(int count) throws Exception {
        UUID wrong=anotherSmtp();
        // Synthetic queued rows: each independent campaign/run has one message with a rejected sender association.
        // Alternating run ranks and repeated timestamps/due dates exercise every component of the SQL cursor tuple.
        for(int n=1;n<=count;n++) {
            UUID campaign=prefixId("campaign",n),recipient=prefixId("recipient",n),run=prefixId("run",n),message=prefixId("message",n);
            f.sql("INSERT INTO campaigns SELECT (jsonb_populate_record(NULL::campaigns,to_jsonb(c)||jsonb_build_object('id','"+campaign+"'))).* FROM campaigns c WHERE id='"+f.source+"'");
            f.sql("INSERT INTO campaign_recipients SELECT (jsonb_populate_record(NULL::campaign_recipients,to_jsonb(r)||jsonb_build_object('id','"+recipient+"','campaign_id','"+campaign+"'))).* FROM campaign_recipients r WHERE id='"+f.recipient+"'");
            f.sql("INSERT INTO campaign_safety_runs SELECT (jsonb_populate_record(NULL::campaign_safety_runs,to_jsonb(r)||jsonb_build_object('id','"+run+"','campaign_id','"+campaign+"','status','"+(n%2==0?"RUNNING":"QUEUED")+"','created_at','1900-01-01'))).* FROM campaign_safety_runs r WHERE campaign_id='"+f.source+"'");
            f.sql("INSERT INTO campaign_safety_messages SELECT (jsonb_populate_record(NULL::campaign_safety_messages,to_jsonb(m)||jsonb_build_object('id','"+message+"','run_id','"+run+"','campaign_recipient_id','"+recipient+"','smtp_account_id','"+wrong+"','rfc_message_id',NULL))).* FROM campaign_safety_messages m WHERE campaign_recipient_id='"+f.recipient+"'");
        }
    }

    private UUID restoreFirstDenied() throws Exception {
        UUID run=UUID.fromString(f.value("SELECT id FROM campaign_safety_runs WHERE campaign_id<>'"+f.source+"' ORDER BY CASE WHEN status='RUNNING' THEN 0 ELSE 1 END,created_at,id LIMIT 1"));
        f.sql("UPDATE campaign_safety_messages SET smtp_account_id='"+f.smtp+"' WHERE run_id='"+run+"'");
        return UUID.fromString(f.value("SELECT campaign_id FROM campaign_safety_runs WHERE id='"+run+"'"));
    }

    private UUID ordinaryCampaign() throws Exception {
        UUID campaign=UUID.randomUUID(),contact=UUID.randomUUID(),recipient=UUID.randomUUID(),smtp=anotherSmtp();
        f.sql("INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,smtp_account_id,mailbox_account_id,from_name,from_email,reply_to,status) SELECT '"+campaign+"','Later','Later',template_id,template_version_id,'"+smtp+"',mailbox_account_id,from_name,from_email,reply_to,'RUNNING' FROM campaigns WHERE id='"+f.source+"'");
        f.sql("UPDATE campaigns SET recipient_policy='PAPER_CONTACT',paper_contact_ids=ARRAY['"+f.paper+"'::uuid],paper_contact_recipient_count=1 WHERE id='"+campaign+"'");
        f.sql("INSERT INTO contacts(id,email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid) SELECT '"+contact+"',email_ciphertext,email_nonce,decode(repeat('09',32),'hex'),email_domain,display_ciphertext,syntax_valid FROM contacts LIMIT 1");
        f.sql("INSERT INTO paper_author_contacts(id,paper_id,contact_id,extraction_run_id,confidence,verification_status) SELECT gen_random_uuid(),paper_id,'"+contact+"',extraction_run_id,confidence,verification_status FROM paper_author_contacts LIMIT 1");
        f.sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) SELECT id,'source.tex','test','test' FROM paper_author_contacts WHERE contact_id='"+contact+"'");
        f.sql("INSERT INTO campaign_recipients(id,campaign_id,contact_id,paper_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,personalization_status,rendered_subject,rendered_html,rendered_text,personalized_at) SELECT '"+recipient+"','"+campaign+"','"+contact+"',paper_id,email_ciphertext,email_nonce,decode(repeat('09',32),'hex'),email_domain,confidence,'GENERATED',rendered_subject,'Hello {{unsubscribe_url}}','Hello {{unsubscribe_url}}',now() FROM campaign_recipients WHERE id='"+f.recipient+"'");
        f.sql("UPDATE campaign_recipients SET next_attempt_at='"+now+"' WHERE id='"+recipient+"'");
        return campaign;
    }

    private String deniedHistory(UUID... allowedCampaigns) throws Exception {
        var hashes=new ArrayList<String>();
        String allowed=Arrays.stream(allowedCampaigns).map(id->"'"+id+"'").collect(java.util.stream.Collectors.joining(","));
        for(String table:List.of("campaigns","campaign_recipients","campaign_safety_runs","campaign_safety_messages","campaign_safety_attempts","campaign_recipient_transfers")) {
            String filter=switch(table) {
                case "campaigns" -> "id NOT IN ("+allowed+")";
                case "campaign_recipients","campaign_safety_runs" -> "campaign_id NOT IN ("+allowed+")";
                case "campaign_safety_messages" -> "run_id IN (SELECT id FROM campaign_safety_runs WHERE campaign_id NOT IN ("+allowed+"))";
                case "campaign_safety_attempts" -> "safety_message_id IN (SELECT m.id FROM campaign_safety_messages m JOIN campaign_safety_runs r ON r.id=m.run_id WHERE r.campaign_id NOT IN ("+allowed+"))";
                default -> "true";
            };
            hashes.add(f.value("SELECT md5(coalesce(string_agg(to_jsonb(r)::text,'|' ORDER BY to_jsonb(r)::text),'')) FROM "+table+" r WHERE "+filter));
        }
        return String.join(":",hashes);
    }

    @Test void transferredSourceCannotMaterializeAgain() throws Exception {
        f.migrate("materialize-fence");
        assertThatThrownBy(()->materialize(f.source)).isInstanceOf(CampaignConflictException.class);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_runs")).isZero();
    }

    @Test void missingCampaignRetainsNotFoundRatherThanEmptyDraftError() {
        var command=new CampaignSafetyRepository.MaterializeCommand(UUID.randomUUID(),f.actor,0,1,
                policy.requireReady().hmac(),policy.requireReady().masked(),now,"missing");
        assertThatThrownBy(()->safety.materialize(command).block()).isInstanceOf(com.camel_hub.advertisement.campaign.CampaignNotFoundException.class);
    }

    @Test void mismatchedMessageSenderCannotClaimOrReserveQuota() throws Exception {
        materialize(f.source); UUID other=UUID.randomUUID();
        f.sql("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit) VALUES ('"+other+"','Other','smtp.example.test',465,'TLS_IMPLICIT','other@example.test','Other','other@example.test',10,100,120,100)");
        f.sql("UPDATE campaign_safety_messages SET smtp_account_id='"+other+"'");
        assertThat(claim()).isNull();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isZero();
    }

    @Test void committedMarkerExistsBeforeTransportAndClaimLocksAreReleased() throws Exception {
        materialize(f.source);
        AtomicInteger calls=new AtomicInteger();
        var executor=new CampaignDeliveryExecutor(delivery,c->Mono.error(new AssertionError("production")),safety,tracking,
                mock(ContactCrypto.class),(account,message)->{
                    assertThat(f.db.sql("SELECT count(*) AS n FROM campaign_safety_attempts WHERE smtp_started_at IS NOT NULL").map((r,m)->r.get("n",Long.class)).one().block()).isEqualTo(1);
                    f.db.sql("UPDATE smtp_accounts SET updated_at=updated_at WHERE id=:id").bind("id",account.id()).fetch().rowsUpdated().block(Duration.ofSeconds(2));
                    calls.incrementAndGet();
                    return new SmtpTransport.SmtpOutcome(CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,CampaignDeliveryModels.TransportStage.POST_DATA,250,"queued");
                },Clock.fixed(now,ZoneOffset.UTC));
        assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
        assertThat(calls).hasValue(1);
    }

    @Test void staleQueuedSafetySourceCannotClaimAfterTransfer() throws Exception {
        UUID target=f.migrate("claim-transfer").targetCampaignId();
        materialize(target);
        f.sql("UPDATE campaign_safety_runs SET campaign_id='"+f.source+"'");
        f.sql("UPDATE campaign_safety_messages SET campaign_recipient_id='"+f.recipient+"'");
        assertThat(claim()).isNull();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isZero();
    }

    @Test void targetProofTamperCannotClaimOrCreateAttempt() throws Exception {
        UUID target=f.migrate("claim-proof").targetCampaignId();
        materialize(target);
        f.sql("UPDATE campaign_recipient_transfers SET content_hash=NULL");
        assertThat(claim()).isNull();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts")).isZero();
    }

    @Test void finalMarkerIsOneShotAndExactAttemptOwned() throws Exception {
        materialize(f.source); var c=preparedClaim();
        assertThat(safety.beginSafetySmtp(c,Clock.fixed(now,ZoneOffset.UTC)).block()).isTrue();
        assertThat(safety.beginSafetySmtp(c,Clock.fixed(now,ZoneOffset.UTC)).block()).isFalse();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts WHERE smtp_started_at IS NOT NULL")).isEqualTo(1);
    }

    @Test void finalFenceReadsClockAfterActualDatabaseLockWait() throws Exception {
        materialize(f.source); var c=preparedClaim();
        var current=new java.util.concurrent.atomic.AtomicReference<>(now);
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return current.get();}};
        f.jdbc.setAutoCommit(false); f.sql("SELECT id FROM campaigns WHERE id='"+f.source+"' FOR UPDATE");
        var pending=safety.beginSafetySmtp(c,clock).toFuture();
        try { awaitWaiter(); current.set(now.plusSeconds(121)); f.jdbc.commit(); }
        finally { f.jdbc.setAutoCommit(true); }
        assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
    }

    @Test void lockHandleCannotAuthorizeOutsideItsTransaction() throws Exception {
        var fence=new CampaignMigrationDeliveryFence(f.db);
        var source=new CampaignMigrationDeliveryFence.Source(f.source,f.recipient,"03".repeat(32));
        var receipt=fence.lockSources(List.of(source)).as(f.tx::transactional).block();
        assertThat(fence.verifyLocked(receipt,true).as(f.tx::transactional).block()).isFalse();
        assertThat(fence.verifyLocked(receipt,true).block()).isFalse();
    }

    @Test void finalFenceWaitsForFinalHmacTierBeforeReadingExpiry() throws Exception {
        materialize(f.source); var c=preparedClaim();
        var current=new java.util.concurrent.atomic.AtomicReference<>(now);
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return current.get();}};
        f.jdbc.setAutoCommit(false); f.sql("SELECT id FROM contacts FOR UPDATE");
        var pending=safety.beginSafetySmtp(c,clock).toFuture();
        try { awaitWaiter(); assertThat(pending).isNotDone(); current.set(now.plusSeconds(121)); }
        finally { f.jdbc.commit(); f.jdbc.setAutoCommit(true); }
        assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"rendered_subject","rendered_html","rendered_text","outbound"})
    void changedActualOrPersistedBodyNeverReachesTransport(String changed) throws Exception {
        materialize(f.source);
        AtomicInteger calls=new AtomicInteger();
        CampaignSafetyOutboundPreparer altered=new CampaignSafetyOutboundPreparer(){
            public Mono<PreparedSafetyOutbound> prepare(CampaignDeliveryRepository.SafetyClaim c){return tracking.prepare(c);}
            public PreparedSafetyOutbound validateForSend(CampaignDeliveryRepository.SafetyClaim c,PreparedSafetyOutbound p){
                tracking.validateForSend(c,p);
                if(changed.equals("outbound"))return new PreparedSafetyOutbound(p.recipient(),p.subject()+" changed",p.html(),p.text(),p.headers());
                f.db.sql("UPDATE campaign_safety_messages SET "+changed+"="+changed+"||' changed' WHERE id=:id").bind("id",c.messageId()).fetch().rowsUpdated().block();
                return p;
            }
        };
        var executor=new CampaignDeliveryExecutor(delivery,c->Mono.error(new AssertionError("production")),safety,altered,mock(ContactCrypto.class),
                (a,m)->{calls.incrementAndGet();return new SmtpTransport.SmtpOutcome(CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,CampaignDeliveryModels.TransportStage.POST_DATA,250,"queued");},Clock.fixed(now,ZoneOffset.UTC));
        assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
        assertThat(calls).hasValue(0);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
        assertThat(f.value("SELECT status FROM campaign_safety_attempts")).isEqualTo("CONNECTING");
    }

    @Test void partnerPreviewPersistsExactNormalizedPreparedBytes() throws Exception {
        f.sql("UPDATE campaign_recipients SET rendered_html=rendered_html||'<a href=\"{{partner_claim_url}}\">Preview</a>',rendered_text=rendered_text||' {{partner_claim_url}}'");
        materialize(f.source);var c=claim();var prepared=tracking.prepare(c).block();
        assertThat(f.value("SELECT rendered_html FROM campaign_safety_messages")).isEqualTo(prepared.html());
        assertThat(f.value("SELECT rendered_text FROM campaign_safety_messages")).isEqualTo(prepared.text());
    }

    @Test void terminalSafetyHistoryRemainsByteIdentical() throws Exception {
        materialize(f.source);var c=claim();tracking.prepare(c).block();
        assertThat(safety.completeAccepted(c.messageId(),c.attemptId(),c.leaseDigest(),new SmtpTransport.SmtpOutcome(
                CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,CampaignDeliveryModels.TransportStage.POST_DATA,250,"queued"),now).block()).isTrue();
        f.sql("INSERT INTO campaign_safety_events(run_id,safety_message_id,event_type,diagnostic_code) VALUES ('"+c.runId()+"','"+c.messageId()+"','BOUNCE','synthetic-feedback')");
        f.sql("INSERT INTO campaign_safety_events(run_id,safety_message_id,safety_link_id,event_type,fingerprint_hash,minute_bucket,classification) SELECT '"+c.runId()+"','"+c.messageId()+"',id,'UNSUBSCRIBE',decode(repeat('ab',32),'hex'),1,'LIKELY_HUMAN' FROM campaign_safety_links WHERE token_type='UNSUBSCRIBE'");
        String before=safetyHistory();
        assertThat(f.migrate("history").status()).isEqualTo("MIGRATED");
        assertThat(safetyHistory()).isEqualTo(before);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"active","unknown"})
    void activeOrUnknownSafetyBlocksMigration(String state) throws Exception {
        materialize(f.source);var c=claim();
        if(state.equals("unknown"))safety.reconcileExpiredLeases(now.plusSeconds(121),20).block();
        String before=safetyHistory();
        assertThat(f.preview().ready()).isFalse();
        assertThat(f.migrate("blocked-"+state).status()).isEqualTo("BLOCKED");
        assertThat(f.count("SELECT count(*) FROM campaign_recipient_transfers")).isZero();
        assertThat(safetyHistory()).isEqualTo(before);
    }

    @Test void wholeMultiHmacBatchLocksAllRootsBeforeAdvisoryAndRecipientTiers() throws Exception {
        UUID second=UUID.randomUUID(),competitor=UUID.randomUUID(),other=UUID.randomUUID(),parent=UUID.randomUUID();
        f.sql("INSERT INTO campaign_recipients(id,campaign_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,status,personalization_status,rendered_subject,rendered_html,rendered_text,personalized_at) SELECT '"+second+"',campaign_id,email_ciphertext,email_nonce,decode(repeat('08',32),'hex'),email_domain,confidence,'QUEUED',personalization_status,rendered_subject,rendered_html,rendered_text,personalized_at FROM campaign_recipients WHERE id='"+f.recipient+"'");
        f.sql("INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,smtp_account_id,from_name,from_email,reply_to,status) SELECT '"+competitor+"','Other','Other',template_id,template_version_id,smtp_account_id,from_name,from_email,reply_to,'PAUSED' FROM campaigns WHERE id='"+f.source+"'");
        f.sql("INSERT INTO campaign_recipients(id,campaign_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,status) SELECT '"+other+"','"+competitor+"',email_ciphertext,email_nonce,email_hmac,email_domain,confidence,'CANCELED' FROM campaign_recipients WHERE id='"+second+"'");
        f.sql("INSERT INTO bulk_outreach_runs(id,actor_id,idempotency_key,payload_hash,command,configuration,request_context,state,status) VALUES ('"+parent+"','"+f.actor+"','"+parent+"',decode(repeat('01',32),'hex'),'{}','{}','{}','{}','BLOCKED')");
        f.sql("INSERT INTO bulk_outreach_children(run_id,campaign_id,selected_count) VALUES ('"+parent+"','"+competitor+"',1)");
        f.jdbc.setAutoCommit(false); f.sql("SELECT id FROM bulk_outreach_runs WHERE id='"+parent+"' FOR UPDATE");
        var pending=safety.materialize(materializeCommand(f.source)).toFuture();
        try {
            awaitWaiter();assertThat(pending).isNotDone();
            assertThat(f.value("SELECT pg_try_advisory_xact_lock(20260923,1000)")).isEqualTo("t");
            f.sql("SELECT id FROM campaign_recipients WHERE campaign_id='"+f.source+"' FOR UPDATE NOWAIT");
        } finally {f.jdbc.commit();f.jdbc.setAutoCommit(true);}
        assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS).recipientIds()).containsExactlyInAnyOrder(f.recipient,second);
    }

    @Test void changedMaterializeSelectionDuringCampaignWaitFailsWithoutShrinking() throws Exception {
        f.jdbc.setAutoCommit(false);f.sql("SELECT id FROM campaigns WHERE id='"+f.source+"' FOR UPDATE");
        var pending=safety.materialize(materializeCommand(f.source)).toFuture();
        try {awaitWaiter();f.sql("UPDATE campaign_recipients SET personalization_status='FAILED' WHERE id='"+f.recipient+"'");}
        finally {f.jdbc.commit();f.jdbc.setAutoCommit(true);}
        assertThatThrownBy(()->pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(CampaignConflictException.class);
        assertThat(f.count("SELECT count(*) FROM campaign_safety_runs")).isZero();
    }

    @Test void migratedTargetWaitsForSafetyBeforeAcquiringTemplateAndAccountTiers() throws Exception {
        UUID target=f.migrate("target-lock-order").targetCampaignId();
        materialize(target);var c=preparedClaim();
        f.jdbc.setAutoCommit(false);f.sql("SELECT id FROM campaign_safety_runs WHERE id='"+c.runId()+"' FOR UPDATE");
        var pending=safety.beginSafetySmtp(c,Clock.fixed(now,ZoneOffset.UTC)).toFuture();
        try {awaitWaiter();f.sql("SELECT id FROM email_template_versions WHERE id='"+f.version+"' FOR UPDATE NOWAIT");
            f.sql("SELECT id FROM smtp_accounts WHERE id='"+f.smtp+"' FOR UPDATE NOWAIT");}
        finally {f.jdbc.commit();f.jdbc.setAutoCommit(true);}
        assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test void staleSafetyContentCannotReachTransportAfterTransfer() throws Exception {
        materialize(f.source);var c=preparedClaim();
        // Synthetic stale aggregate, deliberately outside normal settlement: exercise old QUEUED/lease facts.
        f.sql("UPDATE campaign_safety_runs SET status='COMPLETED'; UPDATE campaign_safety_messages SET status='PERMANENT_FAILURE',delivery_lease_hash=NULL,delivery_lease_expires_at=NULL; UPDATE campaign_safety_attempts SET status='PERMANENT_FAILURE'");
        assertThat(f.migrate("stale-safety").status()).isEqualTo("MIGRATED");
        f.sql("UPDATE campaign_safety_runs SET status='RUNNING'; UPDATE campaign_safety_messages SET status='CONNECTING',delivery_lease_hash=decode('"+HexFormat.of().formatHex(digest(c.leaseDigest()))+"','hex'),delivery_lease_expires_at=TIMESTAMPTZ '"+now.plusSeconds(120)+"'; UPDATE campaign_safety_attempts SET status='CONNECTING'");
        String history=safetyHistory();
        assertThat(safety.lockPreparation(c,java.security.MessageDigest.getInstance("SHA-256").digest(c.leaseDigest()),now).as(f.tx::transactional).block()).isNull();
        assertThatThrownBy(()->safety.persistPreparedBodies(c,digest(c.leaseDigest()),"arbitrary","arbitrary","arbitrary",now).as(f.tx::transactional).block()).isInstanceOf(IllegalStateException.class);
        assertThat(safety.beginSafetySmtp(c,Clock.fixed(now,ZoneOffset.UTC)).block()).isFalse();
        assertThat(safetyHistory()).isEqualTo(history);
        f.sql("UPDATE campaign_safety_messages SET status='QUEUED',delivery_lease_hash=NULL,delivery_lease_expires_at=NULL");
        AtomicInteger calls=new AtomicInteger();
        var executor=new CampaignDeliveryExecutor(delivery,p->Mono.error(new AssertionError("production")),safety,tracking,mock(ContactCrypto.class),
                (a,m)->{calls.incrementAndGet();return new SmtpTransport.SmtpOutcome(CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,CampaignDeliveryModels.TransportStage.POST_DATA,250,"queued");},Clock.fixed(now,ZoneOffset.UTC));
        assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
        assertThat(calls).hasValue(0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"attempt","lease","source","run","destination"})
    void exactWholeAssociationRejectsForeignOrStaleClaim(String changed) throws Exception {
        materialize(f.source);var c=preparedClaim();
        var invalid=new CampaignDeliveryRepository.SafetyClaim(c.messageId(),changed.equals("run")?UUID.randomUUID():c.runId(),
                changed.equals("source")?UUID.randomUUID():c.campaignRecipientId(),changed.equals("attempt")?UUID.randomUUID():c.attemptId(),
                c.attemptNumber(),c.idempotencyKey(),c.rfcMessageId(),c.correlationId(),changed.equals("lease")?new byte[32]:c.leaseDigest(),
                changed.equals("destination")?new byte[32]:c.destinationHmac(),c.destinationMasked(),c.destinationDomain(),c.smtpAccount(),
                c.fromName(),c.fromEmail(),c.replyTo(),c.trackingOpensEnabled(),c.trackingClicksEnabled(),c.renderedSubject(),c.renderedHtml(),c.renderedText());
        String before=safetyHistory();
        assertThat(safety.beginSafetySmtp(invalid,Clock.fixed(now,ZoneOffset.UTC)).block()).isFalse();
        assertThatThrownBy(()->safety.persistPreparedBodies(invalid,digest(invalid.leaseDigest()),"changed","changed","changed",now).as(f.tx::transactional).block()).isInstanceOf(IllegalStateException.class);
        assertThat(safetyHistory()).isEqualTo(before);
    }

    @Test void materializationAndMigrationHaveOnlyOneExecutableWinner() throws Exception {
        var migrationCommand=f.command("racing-safety");var safetyCommand=materializeCommand(f.source);
        f.jdbc.setAutoCommit(false);f.sql("SELECT id FROM campaigns WHERE id='"+f.source+"' FOR UPDATE");
        var pendingSafety=safety.materialize(safetyCommand).toFuture();
        awaitWaiter();
        var pendingMigration=f.service.migrate(f.source,f.actor,f.CONTEXT,migrationCommand).toFuture();
        f.jdbc.commit();f.jdbc.setAutoCommit(true);
        assertThat(pendingSafety.get(5,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        assertThatThrownBy(()->pendingMigration.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(CampaignConflictException.class);
        assertThat(f.count("SELECT count(*) FROM campaign_recipient_transfers")).isZero();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_runs")).isEqualTo(1);
    }

    @Test void suspendedOuterLockHandleCannotAuthorizeRequiresNewTransaction() {
        var fence=new CampaignMigrationDeliveryFence(f.db);
        var separate=org.springframework.transaction.reactive.TransactionalOperator.create(
                new org.springframework.r2dbc.connection.R2dbcTransactionManager(f.db.getConnectionFactory()),
                new org.springframework.transaction.support.DefaultTransactionDefinition(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        assertThat(fence.lockSources(List.of(new CampaignMigrationDeliveryFence.Source(f.source,f.recipient,"03".repeat(32))))
                .flatMap(receipt -> fence.verifyLocked(receipt,true).as(separate::transactional)).as(f.tx::transactional).block(Duration.ofSeconds(5))).isFalse();
    }

    @Test void frozenSafetySenderIsLockedBeforeHmacEvenIfCampaignNowSelectsAnotherAccount() throws Exception {
        materialize(f.source);var c=preparedClaim();UUID other=anotherSmtp();
        f.sql("UPDATE campaigns SET smtp_account_id='"+other+"' WHERE id='"+f.source+"'");
        f.jdbc.setAutoCommit(false);f.sql("SELECT id FROM smtp_accounts WHERE id='"+c.smtpAccount().id()+"' FOR UPDATE");
        var pending=safety.beginSafetySmtp(c,Clock.fixed(now,ZoneOffset.UTC)).toFuture();
        try {awaitWaiter();assertThat(pending).isNotDone();f.sql("SELECT id FROM contacts FOR UPDATE NOWAIT");}
        finally {f.jdbc.commit();f.jdbc.setAutoCommit(true);}
        assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"enabled=false","last_test_status='FAILED'","lock_version=lock_version+1","updated_at=updated_at+interval '1 hour'"})
    void frozenSenderHealthOrConfigurationDriftCannotMarkSmtp(String change) throws Exception {
        materialize(f.source);var c=preparedClaim();
        f.sql("UPDATE smtp_accounts SET "+change+" WHERE id='"+c.smtpAccount().id()+"'");
        assertThat(safety.beginSafetySmtp(c,Clock.fixed(now,ZoneOffset.UTC)).block()).isFalse();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_attempts WHERE smtp_started_at IS NOT NULL")).isZero();
    }

    private UUID anotherSmtp() throws Exception {
        UUID other=UUID.randomUUID();
        f.sql("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,last_test_status,last_tested_at) VALUES ('"+other+"','Other "+other+"','smtp.example.test',465,'TLS_IMPLICIT','other@example.test','Other','other@example.test',10,100,120,100,true,'SUCCEEDED',now()+interval '1 minute')");
        return other;
    }

    private byte[] digest(byte[] bytes) {
        try{return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception impossible){throw new AssertionError(impossible);}
    }

    private CampaignSafetyRepository.MaterializeCommand materializeCommand(UUID campaign) throws Exception {
        return new CampaignSafetyRepository.MaterializeCommand(campaign,f.actor,f.count("SELECT lock_version FROM campaigns WHERE id='"+campaign+"'"),20,policy.requireReady().hmac(),policy.requireReady().masked(),now,"synthetic");
    }

    private CampaignDeliveryRepository.SafetyClaim preparedClaim() {
        var c=claim();var p=tracking.prepare(c).block();return c.withPreparedBodies(p.subject(),p.html(),p.text());
    }

    private String safetyHistory() throws Exception {
        var hashes=new ArrayList<String>();
        for(String table:List.of("campaign_safety_runs","campaign_safety_messages","campaign_safety_attempts","campaign_safety_links","campaign_safety_events"))
            hashes.add(f.value("SELECT md5(coalesce(string_agg(to_jsonb(r)::text,'|' ORDER BY to_jsonb(r)::text),'')) FROM "+table+" r"));
        return String.join(":",hashes);
    }

    private void awaitWaiter() throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(System.nanoTime()<deadline){
            if(f.count("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid<>pg_backend_pid()")>0)return;
            Thread.sleep(10);
        }
        fail("Expected real PostgreSQL lock wait");
    }
}
