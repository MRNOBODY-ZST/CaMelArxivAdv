package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CampaignTemplateMigrationConcurrencyIntegrationTest {
    static CampaignTemplateMigrationIntegrationTest.Fixture f;
    @BeforeAll static void database()throws Exception{f=new CampaignTemplateMigrationIntegrationTest.Fixture();}
    @AfterAll static void close()throws Exception{f.close();}
    @BeforeEach void seed()throws Exception{f.seed();}
    @Test void sameKeyReturnsSameResult()throws Exception{
        var c=f.command("concurrent");
        var results=Flux.range(0,4).flatMap(i->f.service.migrate(f.source,f.actor,f.CONTEXT,c)).collectList().block(Duration.ofSeconds(30));
        assertThat(results).hasSize(4).allMatch(results.getFirst()::equals);
        assertThat(f.count("SELECT count(*) FROM campaign_template_migrations")).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(2);
    }
    @Test void changedKeyPayloadConflicts(){
        var c=f.command("same");f.service.migrate(f.source,f.actor,f.CONTEXT,c).block();
        var changed=new MigrationCommand(c.expectedLockVersion()+1,c.targetTemplateId(),c.targetTemplateVersionId(),c.expectedTemplateLockVersion(),c.recipientIds(),c.expectedMembershipDigest(),c.idempotencyKey());
        assertThatThrownBy(()->f.service.migrate(f.source,f.actor,f.CONTEXT,changed).block()).isInstanceOf(CampaignConflictException.class);
    }
    @Test void lostResponseReadback()throws Exception{
        var c=f.command("lost");var result=f.service.migrate(f.source,f.actor,f.CONTEXT,c).block();
        f.sql("UPDATE campaigns SET lock_version=lock_version+1 WHERE id='"+f.source+"'");
        f.sql("UPDATE campaign_recipients SET rendered_html='Later edited target' WHERE campaign_id='"+result.targetCampaignId()+"'");
        assertThat(f.service.migrate(f.source,f.actor,f.CONTEXT,c).block()).isEqualTo(result);
        assertThat(f.service.getByKey(f.source,f.actor,"lost").block()).isEqualTo(result);
    }
    @Test void overlapCreatesOneTargetPerSource()throws Exception{
        var a=f.command("a");var b=f.command("b");
        var results=Flux.merge(f.service.migrate(f.source,f.actor,f.CONTEXT,a).materialize(),f.service.migrate(f.source,f.actor,f.CONTEXT,b).materialize()).collectList().block(Duration.ofSeconds(30));
        assertThat(results.stream().filter(s->s.hasValue()).count()).isEqualTo(1);
        assertThat(results.stream().filter(s->s.isOnError()).count()).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM campaign_recipient_transfers")).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(2);
    }
    @Test void revokedActorDuringCommit()throws Exception{
        var c=f.command("revoked");f.jdbc.setAutoCommit(false);
        f.sql("UPDATE users SET status='DISABLED' WHERE id='"+f.actor+"'");
        var pending=f.service.migrate(f.source,f.actor,f.CONTEXT,c).toFuture();
        try { awaitWaiter(); assertThat(pending).isNotDone(); f.jdbc.commit(); }
        finally { f.jdbc.setAutoCommit(true); }
        assertThatThrownBy(()->pending.join()).hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(1);
    }

    @Test void parentAdvancementRaceInvalidatesTheOriginalMembership()throws Exception{
        UUID parent=parent("BLOCKED");var command=f.command("parent-race");
        f.jdbc.setAutoCommit(false);f.sql("UPDATE bulk_outreach_runs SET status='ACTIVE',lock_version=lock_version+1 WHERE id='"+parent+"'");
        var pending=f.service.migrate(f.source,f.actor,f.CONTEXT,command).toFuture();
        try { awaitWaiter();assertThat(pending).isNotDone();f.jdbc.commit(); } finally { f.jdbc.setAutoCommit(true); }
        assertThatThrownBy(()->pending.join()).hasCauseInstanceOf(CampaignConflictException.class);
        assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(1);
    }

    @Test void pausedSourceWithRunnableParentBlocksButBlockedParentRetainsOwnership()throws Exception{
        UUID parent=parent("ACTIVE");
        assertThat(f.preview().reasons()).extracting(MigrationReason::code).contains("PARENT_ACTIVE");
        assertThat(f.migrate("blocked").status()).isEqualTo("BLOCKED");
        f.sql("UPDATE bulk_outreach_runs SET status='BLOCKED',lock_version=lock_version+1 WHERE id='"+parent+"'");
        String reservation=f.value("SELECT to_jsonb(r)::text FROM bulk_outreach_reservations r");
        var result=f.migrate("terminal-parent");
        assertThat(result.status()).isEqualTo("MIGRATED");
        assertThat(f.value("SELECT to_jsonb(r)::text FROM bulk_outreach_reservations r")).isEqualTo(reservation);
        assertThat(f.count("SELECT count(*) FROM bulk_outreach_children")).isEqualTo(1);
    }

    @Test void sourceResumeRaceIsNotAStaleSuccess()throws Exception{
        var command=f.command("resume");f.jdbc.setAutoCommit(false);
        f.sql("UPDATE campaigns SET status='RUNNING',lock_version=lock_version+1 WHERE id='"+f.source+"'");
        var pending=f.service.migrate(f.source,f.actor,f.CONTEXT,command).toFuture();
        try {awaitWaiter();assertThat(pending).isNotDone();f.jdbc.commit();}finally{f.jdbc.setAutoCommit(true);}
        assertThatThrownBy(()->pending.join()).hasCauseInstanceOf(CampaignConflictException.class);
        assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(1);
    }

    @Test void sameHmacCompetingParentIsLockedBeforeTheGlobalAdvisory()throws Exception{
        parent("BLOCKED");UUID original=f.source,originalRecipient=f.recipient;
        UUID competitor=UUID.randomUUID(),otherRecipient=UUID.randomUUID();
        f.sql("INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,smtp_account_id,from_name,from_email,reply_to,status) SELECT '"+competitor+"','Competitor',purpose,template_id,template_version_id,smtp_account_id,from_name,from_email,reply_to,'CANCELED' FROM campaigns WHERE id='"+original+"'");
        f.sql("INSERT INTO campaign_recipients(id,campaign_id,contact_id,paper_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,status) SELECT '"+otherRecipient+"','"+competitor+"',contact_id,paper_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,'CANCELED' FROM campaign_recipients WHERE id='"+originalRecipient+"'");
        f.source=competitor;f.recipient=otherRecipient;
        UUID otherParent=UUID.randomUUID();
        f.sql("INSERT INTO bulk_outreach_runs(id,actor_id,idempotency_key,payload_hash,command,configuration,request_context,state,status) VALUES ('"+otherParent+"','"+f.actor+"','"+otherParent+"',decode(repeat('01',32),'hex'),'{}','{}','{}','{}','BLOCKED')");
        f.sql("INSERT INTO bulk_outreach_children(run_id,campaign_id,selected_count) VALUES ('"+otherParent+"','"+competitor+"',1)");
        f.source=original;f.recipient=originalRecipient;var command=f.command("two-parents");
        f.jdbc.setAutoCommit(false);f.sql("UPDATE bulk_outreach_runs SET lock_version=lock_version+1 WHERE id='"+otherParent+"'");
        var pending=f.service.migrate(f.source,f.actor,f.CONTEXT,command).toFuture();
        try {awaitWaiter();assertThat(pending).isNotDone();assertThat(f.value("SELECT pg_try_advisory_xact_lock(20260923,1000)")).isEqualTo("t");f.jdbc.commit();}
        finally{f.jdbc.setAutoCommit(true);}
        assertThatThrownBy(()->pending.join()).hasCauseInstanceOf(CampaignConflictException.class);
        assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(2);
    }

    private UUID parent(String status)throws Exception{
        UUID parent=UUID.randomUUID();
        f.sql("INSERT INTO bulk_outreach_runs(id,actor_id,idempotency_key,payload_hash,command,configuration,request_context,state,status) VALUES ('"+parent+"','"+f.actor+"','"+parent+"',decode(repeat('01',32),'hex'),'{}','{}','{}','{}','"+status+"')");
        f.sql("INSERT INTO bulk_outreach_children(run_id,campaign_id,selected_count) VALUES ('"+parent+"','"+f.source+"',1)");
        f.sql("INSERT INTO bulk_outreach_reservations(email_hmac,run_id,campaign_id,recipient_id) SELECT email_hmac,'"+parent+"',campaign_id,id FROM campaign_recipients WHERE id='"+f.recipient+"'");
        return parent;
    }
    private void awaitWaiter()throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(System.nanoTime()<deadline){
            if(f.count("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid<>pg_backend_pid()")>0)return;
            Thread.sleep(10);
        }
        fail("Migration did not reach the expected real database lock wait");
    }
    @Test void sourceTemplateGenerationAndSafetyChangesInvalidatePreview()throws Exception{
        for(String change:List.of("UPDATE campaigns SET lock_version=lock_version+1","UPDATE campaigns SET status='RUNNING'","UPDATE campaigns SET generation_status='RUNNING'","UPDATE email_templates SET lock_version=lock_version+1","UPDATE email_templates SET current_version=3")){
            f.seed();var c=f.command("stale");f.sql(change);
            assertThatThrownBy(()->f.service.migrate(f.source,f.actor,f.CONTEXT,c).block()).isInstanceOf(CampaignConflictException.class);
            assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(1);
        }
        f.seed();var c=f.command("safety");f.safety("RUNNING","QUEUED","CONNECTING");
        assertThatThrownBy(()->f.service.migrate(f.source,f.actor,f.CONTEXT,c).block()).isInstanceOf(CampaignConflictException.class);
    }
    @Test void eachSavePointFailureRollsBackEverything()throws Exception{
        for(String point:List.of("segments:INSERT","segment_rules:INSERT","campaigns:INSERT","campaign_recipients:INSERT","campaign_recipients:UPDATE","outreach_content_reviews:INSERT","campaign_recipient_transfers:INSERT","campaign_recipient_transfers:UPDATE","audit_logs:INSERT")){
            String table=point.split(":")[0];
            f.seed();var c=f.command("rollback");String before=f.inventory(),history=f.history();
            f.sql("CREATE OR REPLACE FUNCTION fail_migration_save() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected migration save failure'; END $$");
            String event=point.split(":")[1];
            f.sql("CREATE TRIGGER fail_migration_save BEFORE "+event+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION fail_migration_save()");
            try{assertThatThrownBy(()->f.service.migrate(f.source,f.actor,f.CONTEXT,c).block()).hasMessageContaining("injected migration save failure");}
            finally{f.sql("DROP TRIGGER fail_migration_save ON "+table);}
            assertThat(f.inventory()).as(table).isEqualTo(before);assertThat(f.history()).as(table).isEqualTo(history);
            assertThat(f.value("SELECT lock_version::text FROM campaigns WHERE id='"+f.source+"'")).isEqualTo("0");
        }
    }

    @Test void permissionRowsRemainLockedUntilCommit() {
        f.tx.transactional(f.repository.authorized(f.actor,true).flatMap(allowed->Mono.fromCallable(()->{
            assertThat(allowed).isTrue();
            assertThatThrownBy(()->f.sql("SELECT id FROM permissions WHERE code='campaign:create' FOR UPDATE NOWAIT"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("could not obtain lock");
            return allowed;
        }))).block();
    }
}
