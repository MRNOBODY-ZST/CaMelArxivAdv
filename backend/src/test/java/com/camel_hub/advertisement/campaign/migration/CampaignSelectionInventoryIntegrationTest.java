package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.outreach.bulk.BulkOutreachRepository;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real migration proofs; counters never stand in for a delivery fence. */
class CampaignSelectionInventoryIntegrationTest {
    static CampaignTemplateMigrationIntegrationTest.Fixture f;
    CampaignRepository campaigns;
    CampaignReportingRepository reporting;
    CampaignSelectionInventory inventory;
    @BeforeAll static void database() throws Exception { f=new CampaignTemplateMigrationIntegrationTest.Fixture(); }
    @AfterAll static void close() throws Exception { f.close(); }
    @BeforeEach void seed() throws Exception {
        f.seed();
        var key=Base64.getEncoder().encodeToString(new byte[32]);
        byte[] hmac=new byte[32]; Arrays.fill(hmac,(byte)1);
        inventory=new CampaignSelectionInventory(new CampaignProductionSelectionPolicy(
            new ContactCrypto(new ContactDataProtectionProperties(key,Base64.getEncoder().encodeToString(hmac))),new CampaignSafetyProperties(false,null,20,Map.of()),Duration.ofDays(180)));
        campaigns=new CampaignRepository(f.db,inventory);
        reporting=new CampaignReportingRepository(f.db,inventory);
    }

    @Test void historyAndActionabilityDoNotDoubleCount() throws Exception {
        UUID second=addRecipient(4), third=addRecipient(5);
        var result=migrate(List.of(second,f.recipient));
        assertThat(counts(f.source)).isEqualTo(new SelectionCounts(3,1,2,0));
        assertThat(counts(result.targetCampaignId())).isEqualTo(new SelectionCounts(2,2,0,0));
        assertThat(campaigns.find(f.source).block().selectionCounts()).isEqualTo(counts(f.source));
        assertThat(reporting.campaigns(f.source,0,20).blockFirst().recipients()).isEqualTo(3);
        var refs=reporting.campaigns(f.source,0,20).blockFirst().migrationReferences();
        assertThat(refs).containsExactly(new CampaignSelectionInventory.MigrationReference(result.id(),f.source,result.targetCampaignId()));
        assertThat(reporting.campaigns(result.targetCampaignId(),0,20).blockFirst().migrationReferences()).isEqualTo(refs);
        var rows=campaigns.recipients(f.source,0,20).collectList().block();
        assertThat(rows).filteredOn(r->r.id().equals(third)).singleElement().satisfies(r->assertThat(r.transfer()).isNull());
        assertThat(rows).filteredOn(r->!r.id().equals(third)).allSatisfy(r->assertThat(r.transfer().status()).isEqualTo("TRANSFERRED"));
        assertThat(campaigns.recipients(result.targetCampaignId(),0,20).collectList().block()).allSatisfy(r->assertThat(r.transfer().status()).isEqualTo("MIGRATED_TARGET"));
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isZero();
        assertThat(f.count("SELECT count(*) FROM outbox_messages")).isZero();
    }

    @Test void incomingOwnershipRequiresExactProofAndRejectsUnrelatedDuplicates() throws Exception {
        var result=migrate(List.of(f.recipient));
        assertThat(counts(result.targetCampaignId()).actionableQueued()).isEqualTo(1);
        String proof=f.value("SELECT encode(source_hash,'hex') FROM campaign_recipient_transfers");
        f.sql("UPDATE campaign_recipient_transfers SET source_hash=NULL");
        assertThat(counts(result.targetCampaignId())).isEqualTo(new SelectionCounts(1,0,0,1));
        f.sql("UPDATE campaign_recipient_transfers SET source_hash=decode('"+proof+"','hex')");
        UUID duplicate=UUID.randomUUID(),other=UUID.randomUUID();
        f.sql("INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,smtp_account_id,from_name,from_email,reply_to) SELECT '"+other+"','Unrelated','Synthetic',template_id,template_version_id,smtp_account_id,from_name,from_email,reply_to FROM campaigns WHERE id='"+f.source+"'");
        f.sql("INSERT INTO campaign_recipients(id,campaign_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,status) SELECT '"+duplicate+"','"+other+"',email_ciphertext,email_nonce,email_hmac,email_domain,confidence,'CONNECTING' FROM campaign_recipients WHERE id='"+f.recipient+"'");
        assertThat(counts(result.targetCampaignId()).actionableQueued()).isZero();
    }

    @Test void terminalGenerationAndSafetyAreNotSendReadinessAndWholeInventoryIsUnbounded() throws Exception {
        f.sql("UPDATE campaigns SET generation_status='FAILED' WHERE id='"+f.source+"'");
        f.sql("UPDATE campaign_recipients SET personalization_status='FAILED' WHERE id='"+f.recipient+"'");
        f.safety("COMPLETED","SMTP_ACCEPTED","SMTP_ACCEPTED");
        f.sql("UPDATE smtp_accounts SET enabled=false");
        f.sql("UPDATE email_templates SET status='ARCHIVED'");
        assertThat(counts(f.source)).isEqualTo(new SelectionCounts(1,1,0,0));
        for(int i=4;i<125;i++) addRecipient(i);
        assertThat(counts(f.source)).isEqualTo(new SelectionCounts(122,122,0,0));
        f.sql("UPDATE campaigns SET generation_status='RUNNING' WHERE id='"+f.source+"'");
        assertThat(counts(f.source)).isEqualTo(new SelectionCounts(122,0,0,122));
    }

    @Test void formalHistoryAndCurrentSafetyRemainConservative() throws Exception {
        for(String change:List.of("status='RUNNING'","status='COMPLETED'","status='CANCELED'")) {
            f.sql("UPDATE campaigns SET "+change+" WHERE id='"+f.source+"'");
            assertThat(counts(f.source).actionableQueued()).isZero();
        }
        f.sql("UPDATE campaigns SET status='PAUSED' WHERE id='"+f.source+"'");
        f.sql("UPDATE campaign_recipients SET attempt_count=1 WHERE id='"+f.recipient+"'");
        assertThat(counts(f.source).migrationBlocked()).isEqualTo(1);
        f.sql("UPDATE campaign_recipients SET attempt_count=0 WHERE id='"+f.recipient+"'");
        f.safety("RUNNING","QUEUED","CONNECTING");
        assertThat(counts(f.source).actionableQueued()).isZero();
    }

    @Test void incomingSourceReservationsMustRemainExactQueuedAndExpected() throws Exception {
        UUID parent=bulkOwner();
        var result=migrate(List.of(f.recipient));
        assertThat(counts(result.targetCampaignId()).actionableQueued()).isEqualTo(1);
        f.sql("UPDATE bulk_outreach_reservations SET outcome='SMTP_ACCEPTED'");
        assertThat(counts(result.targetCampaignId()).actionableQueued()).isZero();
        f.sql("UPDATE bulk_outreach_reservations SET outcome='QUEUED'");
        f.sql("UPDATE bulk_outreach_runs SET status='ACTIVE' WHERE id='"+parent+"'");
        assertThat(counts(result.targetCampaignId()).actionableQueued()).isZero();
        f.sql("UPDATE bulk_outreach_runs SET status='BLOCKED' WHERE id='"+parent+"'");
        f.sql("DELETE FROM bulk_outreach_reservations"); // Adversarial synthetic fixture only.
        assertThat(counts(result.targetCampaignId()).actionableQueued()).isZero();
    }

    @Test void invalidCapturedTimestampIsNotActualStoredProvenance() throws Exception {
        var result=migrate(List.of(f.recipient));
        f.sql("UPDATE campaign_recipient_transfers SET provenance=jsonb_set(provenance,'{capturedAt}','\"not-a-timestamp\"'::jsonb)");
        assertThat(counts(result.targetCampaignId())).isEqualTo(new SelectionCounts(1,0,0,1));
    }

    @Test void frozenSourceValidationDoesNotTrimInvalidContentIntoEligibility() throws Exception {
        f.sql("UPDATE campaign_recipients SET arxiv_id_snapshot=' 2610.54321 ' WHERE id='"+f.recipient+"'");
        assertThat(counts(f.source).actionableQueued()).isZero();
        f.sql("UPDATE campaign_recipients SET arxiv_id_snapshot='2610.54321',paper_abstract_snapshot=' '||repeat('x',20000)||' ' WHERE id='"+f.recipient+"'");
        assertThat(counts(f.source).actionableQueued()).isZero();
    }

    @Test void configuredSafetyDestinationExclusionsUseTheContactHmacKey() throws Exception {
        byte[] key=new byte[32];Arrays.fill(key,(byte)1);
        var crypto=new ContactCrypto(new ContactDataProtectionProperties(Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString(key)));
        byte[] hash=crypto.hmac("safety@example.test");
        f.db.sql("UPDATE contacts SET email_hmac=:hmac").bind("hmac",hash).fetch().rowsUpdated().block();
        f.db.sql("UPDATE campaign_recipients SET email_hmac=:hmac WHERE id=:id").bind("hmac",hash).bind("id",f.recipient).fetch().rowsUpdated().block();
        assertThat(counts(f.source).actionableQueued()).isEqualTo(1);
        var excluded=new CampaignSelectionInventory(new CampaignProductionSelectionPolicy(crypto,
            new CampaignSafetyProperties(false,"safety@example.test",20,Map.of()),Duration.ofDays(180)));
        assertThat(new CampaignReportingRepository(f.db,excluded).campaigns(f.source,0,20).blockFirst().selectionCounts())
            .isEqualTo(new SelectionCounts(1,0,0,1));
    }

    @Test void liveSuppressionsUnsubscribeCooldownAndRejectedEvidenceBlockWithoutChangingHistory() throws Exception {
        assertThat(counts(f.source).actionableQueued()).isEqualTo(1);
        String original=f.history();
        f.sql("INSERT INTO suppression_entries(email_hmac,email_domain,reason,source) SELECT email_hmac,email_domain,'MANUAL','TEST' FROM campaign_recipients WHERE id='"+f.recipient+"'");
        assertThat(counts(f.source)).isEqualTo(new SelectionCounts(1,0,0,1));
        f.sql("UPDATE suppression_entries SET expires_at=now()-interval '1 day'");
        assertThat(counts(f.source).actionableQueued()).isEqualTo(1);
        f.sql("UPDATE paper_author_contacts SET verification_status='REJECTED'");
        assertThat(counts(f.source).actionableQueued()).isZero();
        f.sql("UPDATE paper_author_contacts SET verification_status='UNVERIFIED'");
        f.sql("INSERT INTO recipient_delivery_cooldowns(email_hmac,last_smtp_accepted_at) SELECT email_hmac,now() FROM campaign_recipients WHERE id='"+f.recipient+"'");
        assertThat(counts(f.source).actionableQueued()).isZero();
        f.sql("UPDATE recipient_delivery_cooldowns SET last_smtp_accepted_at=now()-interval '181 days'");
        assertThat(counts(f.source).actionableQueued()).isEqualTo(1);
        f.sql("INSERT INTO unsubscribe_records(email_hmac,token_hash) SELECT email_hmac,decode(repeat('09',32),'hex') FROM campaign_recipients WHERE id='"+f.recipient+"'");
        assertThat(counts(f.source).actionableQueued()).isZero();
        assertThat(f.history()).isEqualTo(original);
    }

    UUID bulkOwner() throws Exception {
        UUID parent=UUID.randomUUID();
        f.sql("INSERT INTO bulk_outreach_runs(id,actor_id,idempotency_key,payload_hash,command,configuration,request_context,state,status) VALUES ('"+parent+"','"+f.actor+"','"+parent+"',decode(repeat('01',32),'hex'),'{}','{}','{}','{}','BLOCKED')");
        f.sql("INSERT INTO bulk_outreach_children(run_id,campaign_id,selected_count) VALUES ('"+parent+"','"+f.source+"',1)");
        f.sql("INSERT INTO bulk_outreach_reservations(email_hmac,run_id,campaign_id,recipient_id) SELECT email_hmac,'"+parent+"',campaign_id,id FROM campaign_recipients WHERE id='"+f.recipient+"'");
        return parent;
    }

    SelectionCounts counts(UUID id) { return reporting.campaigns(id,0,20).blockFirst().selectionCounts(); }
    MigrationResult migrate(List<UUID> ids) {
        var p=f.service.preview(f.source,f.actor,new PreviewCommand(f.template,f.version,ids)).block();
        assertThat(p.ready()).isTrue();
        return f.service.migrate(f.source,f.actor,CampaignTemplateMigrationIntegrationTest.Fixture.CONTEXT,
            new MigrationCommand(p.expectedLockVersion(),f.template,f.version,p.expectedTemplateLockVersion(),p.recipientIds(),p.expectedMembershipDigest(),UUID.randomUUID().toString())).block();
    }
    UUID addRecipient(int n) throws Exception {
        UUID contact=UUID.randomUUID(),recipient=UUID.randomUUID(),evidence=UUID.randomUUID();
        String hash=String.format("%064x",n);
        f.sql("INSERT INTO contacts(id,email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid) VALUES ('"+contact+"',decode('01','hex'),decode('02','hex'),decode('"+hash+"','hex'),'synthetic.test',decode('01','hex'),true)");
        f.sql("INSERT INTO paper_author_contacts(id,paper_id,contact_id,extraction_run_id,confidence,verification_status) SELECT '"+evidence+"',paper_id,'"+contact+"',extraction_run_id,'UNMAPPED','UNVERIFIED' FROM paper_author_contacts WHERE id='"+f.evidence+"'");
        f.sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) VALUES ('"+evidence+"','source.tex','test','synthetic')");
        f.sql("INSERT INTO campaign_recipients(id,campaign_id,contact_id,paper_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,paper_title_snapshot,paper_abstract_snapshot,arxiv_id_snapshot) SELECT '"+recipient+"','"+f.source+"',id,'"+f.paper+"',email_ciphertext,email_nonce,email_hmac,email_domain,'UNMAPPED','Frozen title','Frozen abstract','2610.54321' FROM contacts WHERE id='"+contact+"'");
        return recipient;
    }
}
