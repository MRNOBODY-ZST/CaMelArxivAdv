package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CampaignTemplateMigrationIntegrationTest {
    static Fixture f;
    @BeforeAll static void database() throws Exception { f = new Fixture(); }
    @AfterAll static void close() throws Exception { f.close(); }
    @BeforeEach void seed() throws Exception { f.seed(); }

    @Test void previewHasZeroWrites() throws Exception {
        String before=f.inventory();
        var preview=f.preview();
        assertThat(preview.ready()).isTrue();
        assertThat(preview.recipientIds()).containsExactly(f.recipient);
        assertThat(f.inventory()).isEqualTo(before);
    }

    @Test void migratesExactFrozenSourcesToUnapprovedDraft() throws Exception {
        f.safety("COMPLETED","SMTP_ACCEPTED","SMTP_ACCEPTED");
        String history=f.history();
        var result=f.migrate("exact");
        assertThat(result.status()).isEqualTo("MIGRATED");
        assertThat(result.selectedCount()).isEqualTo(1);
        assertThat(result.migratedCount()).isEqualTo(1);
        UUID target=result.targetCampaignId();
        assertThat(f.value("SELECT status||':'||generation_status||':'||generation_provider||':'||generation_model||':'||recipient_policy FROM campaigns WHERE id='"+target+"'"))
                .isEqualTo("DRAFT:COMPLETED:TEMPLATE:exact-v1:PAPER_CONTACT");
        assertThat(f.count("SELECT count(*) FROM campaigns WHERE id='"+target+"' AND template_version_id='"+f.version+"' AND segment_id IS NOT NULL AND approved_at IS NULL AND submitted_for_review_at IS NULL AND scheduled_at IS NULL AND started_at IS NULL")).isEqualTo(1);
        assertThat(f.value("SELECT rendered_subject FROM campaign_recipients WHERE campaign_id='"+target+"'" )).isEqualTo("New: Frozen title");
        assertThat(f.value("SELECT rendered_html FROM campaign_recipients WHERE campaign_id='"+target+"'" )).contains("research team","{{unsubscribe_url}}","Frozen title").doesNotContain("Legacy guess");
        assertThat(f.value("SELECT rendered_text FROM campaign_recipients WHERE campaign_id='"+target+"'" )).contains("research team","{{unsubscribe_url}}");
        assertThat(f.count("SELECT count(*) FROM outreach_content_reviews rv JOIN campaign_recipient_transfers tr ON tr.target_recipient_id=rv.recipient_id WHERE tr.source_recipient_id='"+f.recipient+"' AND rv.source_hash=tr.source_hash AND rv.content_hash=tr.content_hash AND tr.provenance IS NOT NULL")).isEqualTo(1);
        assertThat(f.history()).isEqualTo(history);
        assertThat(f.value("SELECT status||':'||lock_version FROM campaigns WHERE id='"+f.source+"'" )).isEqualTo("PAUSED:1");
        assertThat(f.count("SELECT count(*) FROM outbox_messages")).isZero();
        assertThat(f.count("SELECT count(*) FROM jobs")).isZero();
        assertThat(f.count("SELECT count(*) FROM bulk_outreach_children WHERE campaign_id='"+target+"'" )).isZero();
    }

    @Test void hydratesOnlyNewSamePaperSnapshot() throws Exception {
        f.sql("UPDATE campaign_recipients SET paper_abstract_snapshot=NULL,arxiv_id_snapshot=NULL WHERE id='"+f.recipient+"'");
        var result=f.migrate("hydrate");
        assertThat(f.value("SELECT paper_title_snapshot||':'||paper_abstract_snapshot||':'||arxiv_id_snapshot FROM campaign_recipients WHERE campaign_id='"+result.targetCampaignId()+"'" )).isEqualTo("Frozen title:Bound abstract:2610.12345");
        assertThat(f.count("SELECT count(*) FROM campaign_recipients WHERE id='"+f.recipient+"' AND paper_abstract_snapshot IS NULL AND arxiv_id_snapshot IS NULL")).isEqualTo(1);
        String provenance=f.value("SELECT provenance::text FROM campaign_recipient_transfers WHERE source_recipient_id='"+f.recipient+"'");
        assertThat(provenance).contains("paperAbstract","arxivId","capturedAt",f.paper.toString(),f.evidence.toString()).doesNotContain("Bound abstract","Legacy guess","example.test");
    }

    @Test void missingFrozenTitleIsUnavailableEvenWithCompleteSafeCurrentPaper()throws Exception{
        assertThat(f.value("SELECT title||':'||abstract_text||':'||arxiv_id FROM papers WHERE id='"+f.paper+"'")).isEqualTo("Mutable title:Bound abstract:2610.12345");
        int iteration=0;
        for(String missing:List.of("NULL","''","'   '","'"+"x".repeat(2001)+"'")){
            f.sql("UPDATE campaign_recipients SET paper_title_snapshot="+missing+" WHERE id='"+f.recipient+"'");
            String history=f.history();
            var preview=f.preview();
            assertThat(preview.ready()).isFalse();
            assertThat(preview.reasons()).extracting(MigrationReason::code).contains("SOURCE_UNAVAILABLE");
            assertThat(f.migrate("missing-title-"+iteration++).status()).isEqualTo("BLOCKED");
            assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(1);
            assertThat(f.count("SELECT count(*) FROM segments")).isZero();
            assertThat(f.count("SELECT count(*) FROM campaign_recipient_transfers")).isZero();
            assertThat(f.history()).isEqualTo(history);
        }
    }

    @Test void unsafeContentRollsBackOldFailedGenerationRemainsFailed() throws Exception {
        f.sql("UPDATE campaign_recipients SET personalization_status='FAILED',personalization_error_code='OLD_FAILURE' WHERE id='"+f.recipient+"'");
        f.sql("UPDATE email_template_versions SET text_content='your paper {{unsubscribe_url}}' WHERE id='"+f.version+"'");
        String before=f.inventory(), history=f.history();
        assertThatThrownBy(()->f.migrate("unsafe")).isInstanceOf(CampaignValidationException.class);
        assertThat(f.inventory()).isEqualTo(before);
        assertThat(f.history()).isEqualTo(history);
        f.sql("UPDATE email_template_versions SET text_content='Hello {{author_name}} {{unsubscribe_url}}' WHERE id='"+f.version+"'");
        var result=f.migrate("safe");
        assertThat(f.value("SELECT personalization_status FROM campaign_recipients WHERE campaign_id='"+result.targetCampaignId()+"'" )).isEqualTo("GENERATED");
        assertThat(f.history()).isEqualTo(history);
    }

    @Test void disabledGateBlocksPreviewAndCommitButNotAuthorizedReadback() throws Exception {
        var command=f.command("gate"); var result=f.service.migrate(f.source,f.actor,Fixture.CONTEXT,command).block();
        var disabled=f.service(false);
        assertThatThrownBy(()->disabled.preview(f.source,f.actor,f.previewCommand()).block()).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(()->disabled.migrate(f.source,f.actor,Fixture.CONTEXT,command).block()).isInstanceOf(CampaignValidationException.class);
        assertThat(disabled.getByKey(f.source,f.actor,"gate").block()).isEqualTo(result);
        assertThat(disabled.get(f.source,result.id(),f.actor).block()).isEqualTo(result);
    }

    @Test void emptySelectionNeverMeansAllOrEmptyDraft() throws Exception {
        var empty=new PreviewCommand(f.template,f.version,List.of());
        var p=f.service.preview(f.source,f.actor,empty).block();
        var c=new MigrationCommand(p.expectedLockVersion(),f.template,f.version,p.expectedTemplateLockVersion(),List.of(),p.expectedMembershipDigest(),"empty");
        var result=f.service.migrate(f.source,f.actor,Fixture.CONTEXT,c).block();
        assertThat(result.status()).isEqualTo("NO_MAIL");
        assertThat(result.targetCampaignId()).isNull();
        assertThat(f.count("SELECT count(*) FROM campaigns")).isEqualTo(1);
        assertThat(f.value("SELECT status||':'||lock_version FROM campaigns WHERE id='"+f.source+"'" )).isEqualTo("PAUSED:0");
    }

    @Test void onlyTrulyEmptyHistoryFreeDraftCanUpdateConfiguration() throws Exception {
        f.sql("DELETE FROM campaign_recipients WHERE id='"+f.recipient+"'");
        f.sql("UPDATE campaigns SET status='DRAFT' WHERE id='"+f.source+"'");
        var p=f.service.preview(f.source,f.actor,new PreviewCommand(f.template,f.version,List.of())).block();
        var c=new MigrationCommand(p.expectedLockVersion(),f.template,f.version,p.expectedTemplateLockVersion(),List.of(),p.expectedMembershipDigest(),"config");
        assertThat(f.service.migrate(f.source,f.actor,Fixture.CONTEXT,c).block().status()).isEqualTo("CONFIG_UPDATED");
        assertThat(f.count("SELECT count(*) FROM segments")).isZero();
        assertThat(f.value("SELECT lock_version::text FROM campaigns WHERE id='"+f.source+"'" )).isEqualTo("1");
    }

    @Test void requiredAuthorityAndExactMembershipAreFailClosed() throws Exception {
        assertThatThrownBy(()->f.service.preview(f.source,f.actor,new PreviewCommand(f.template,f.version,List.of(f.recipient,f.recipient))).block()).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(()->f.service.preview(f.source,f.actor,new PreviewCommand(f.template,f.version,List.of(UUID.randomUUID()))).block()).isInstanceOf(CampaignValidationException.class);
        f.sql("DELETE FROM user_roles WHERE user_id='"+f.actor+"'");
        assertThatThrownBy(f::preview).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void emptyDraftWithRetainedUnsubscribeHistoryIsNotConfigUpdated()throws Exception{
        f.sql("DELETE FROM campaign_recipients WHERE id='"+f.recipient+"'");
        f.sql("UPDATE campaigns SET status='DRAFT' WHERE id='"+f.source+"'");
        f.sql("INSERT INTO unsubscribe_records(email_hmac,campaign_id,token_hash) VALUES(decode(repeat('03',32),'hex'),'"+f.source+"',decode(repeat('09',32),'hex'))");
        var p=f.service.preview(f.source,f.actor,new PreviewCommand(f.template,f.version,List.of())).block();
        var c=new MigrationCommand(p.expectedLockVersion(),f.template,f.version,p.expectedTemplateLockVersion(),List.of(),p.expectedMembershipDigest(),"history-empty");
        assertThat(f.service.migrate(f.source,f.actor,Fixture.CONTEXT,c).block().status()).isEqualTo("NO_MAIL");
        assertThat(f.value("SELECT lock_version::text FROM campaigns WHERE id='"+f.source+"'")).isEqualTo("0");
    }

    @Test void transferProofIsBoundedAndContainsActualHashes()throws Exception{
        var result=f.migrate("proof");
        for(String invalid:List.of("source_hash=decode('00','hex')","content_hash=decode('00','hex')","provenance='{\"email\":\"private\"}'::jsonb","provenance=jsonb_set(provenance,'{hydratedFields}','[\"identity\"]')","provenance=jsonb_set(provenance,'{hydratedFields}','[\"paperTitle\"]')"))
            assertThatThrownBy(()->f.sql("UPDATE campaign_recipient_transfers SET "+invalid+" WHERE migration_id='"+result.id()+"'")).isInstanceOf(SQLException.class);
        assertThat(f.count("SELECT count(*) FROM campaign_recipient_transfers WHERE octet_length(source_hash)=32 AND octet_length(content_hash)=32 AND provenance->'hydratedFields'='[]'::jsonb")).isEqualTo(1);
    }

    @Test void migrationMakesNoSmtpConnectionAndQueuesNoWakeup()throws Exception{
        try(var sink=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){
            sink.setSoTimeout(250);
            f.sql("UPDATE smtp_accounts SET host='127.0.0.1',port="+sink.getLocalPort()+" WHERE id='"+f.smtp+"'");
            assertThat(f.migrate("no-network").status()).isEqualTo("MIGRATED");
            assertThatThrownBy(sink::accept).isInstanceOf(java.net.SocketTimeoutException.class);
        }
        assertThat(f.count("SELECT count(*) FROM outbox_messages")).isZero();
        assertThat(f.count("SELECT count(*) FROM campaign_safety_runs")).isZero();
        assertThat(f.count("SELECT count(*) FROM delivery_attempts")).isZero();
    }

    @Test void previewCountsAreDisjointWithTransferPrecedence()throws Exception{
        f.sql("UPDATE campaigns SET generation_status='RUNNING' WHERE id='"+f.source+"'");
        assertThat(f.preview().counts()).isEqualTo(new SelectionCounts(1,0,0,1));
        f.sql("UPDATE campaigns SET generation_status='NOT_REQUESTED' WHERE id='"+f.source+"'");
        f.migrate("counts");
        assertThat(f.preview().counts()).isEqualTo(new SelectionCounts(1,0,1,0));
    }

    static final class Fixture implements AutoCloseable {
        static final AuthenticationRequestContext CONTEXT=new AuthenticationRequestContext("127.0.0.1","Synthetic migration test","migration-test");
        final PostgreSQLContainer container=new PostgreSQLContainer("postgres:17.5-alpine");
        final String schema="migration_atomic_"+UUID.randomUUID().toString().replace("-","");
        final java.sql.Connection jdbc;
        final DatabaseClient db;
        final TransactionalOperator tx;
        final CampaignTemplateMigrationRepository repository;
        CampaignTemplateMigrationService service;
        UUID actor,source,recipient,paper,evidence,template,version,smtp,role;
        Fixture() throws Exception {
            String url=System.getenv("OUTREACH_TEST_JDBC_URL"),user=System.getenv("OUTREACH_TEST_DB_USERNAME"),password=System.getenv("OUTREACH_TEST_DB_PASSWORD");
            if(url==null||url.isBlank()){container.start();url=container.getJdbcUrl();user=container.getUsername();password=container.getPassword();}
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            jdbc=DriverManager.getConnection(url,user,password);jdbc.setSchema(schema);
            var factory=ConnectionFactories.get(ConnectionFactoryOptions.parse("r2dbc:"+url.substring(5)).mutate().option(ConnectionFactoryOptions.USER,user).option(ConnectionFactoryOptions.PASSWORD,password).option(Option.valueOf("schema"),schema+",public").build());
            db=DatabaseClient.create(factory); tx=TransactionalOperator.create(new R2dbcTransactionManager(factory));
            repository=new CampaignTemplateMigrationRepository(db,Duration.ofDays(180),Set.of());service=service(true);
            assertThat(value("SELECT current_schema()")).isEqualTo(schema);
            List<String> privateTables=List.of("campaigns","campaign_recipients","delivery_attempts","campaign_recipient_transfers",
                    "campaign_template_migrations","outreach_content_reviews","outreach_runs","bulk_outreach_runs",
                    "bulk_outreach_children","bulk_outreach_reservations","partner_invites","tracking_tokens","campaign_links","outbox_messages","campaign_prepared_content_proofs");
            for(String table:privateTables) {
                String namespace=db.sql("SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.oid=to_regclass(:table)")
                        .bind("table",table).map((r,m)->r.get("nspname",String.class)).one().block();
                assertThat(namespace).as(table+" must resolve to the private task schema").isEqualTo(schema);
            }
            assertThat(count("SELECT count(*) FROM outbox_messages")).isZero();
        }
        CampaignTemplateMigrationService service(boolean enabled){return new CampaignTemplateMigrationService(repository,new CampaignRepository(db),new OutreachTemplateCampaignRepository(db),new OutreachTemplateContentPolicy(new TemplateEngine(102400)),new AuditService(db,new ObjectMapper()),tx,enabled);}
        void seed() throws Exception {
            sql("TRUNCATE campaigns,segments,contacts,papers,mailbox_accounts,smtp_accounts,email_templates,users CASCADE");
            actor=UUID.randomUUID();source=UUID.randomUUID();recipient=UUID.randomUUID();paper=UUID.randomUUID();evidence=UUID.randomUUID();template=UUID.randomUUID();version=UUID.randomUUID();smtp=UUID.randomUUID();role=UUID.randomUUID();
            UUID mailbox=UUID.randomUUID(),contact=UUID.randomUUID(),run=UUID.randomUUID(),oldVersion=UUID.randomUUID();
            sql("INSERT INTO users(id,username,email,password_hash,display_name,force_password_change) VALUES ('"+actor+"','"+actor+"','"+actor+"@example.test','synthetic','Test',false)");
            sql("INSERT INTO roles(id,code,name) VALUES ('"+role+"','"+role+"','Migration tester')");
            sql("INSERT INTO role_permissions SELECT '"+role+"',id,now() FROM permissions WHERE code IN ('campaign:read','campaign:create')");
            sql("INSERT INTO user_roles(user_id,role_id) VALUES ('"+actor+"','"+role+"')");
            sql("INSERT INTO email_templates(id,name,status,current_version) VALUES ('"+template+"','New template','ACTIVE',2)");
            sql("INSERT INTO email_template_versions(id,template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes) VALUES ('"+oldVersion+"','"+template+"',1,'Old','Sender','sender@example.test','Old HTML {{unsubscribe_url}}','Old text {{unsubscribe_url}}',100)");
            sql("INSERT INTO email_template_versions(id,template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes) VALUES ('"+version+"','"+template+"',2,'New: {{paper_title}}','Sender','sender@example.test','<p>Hello {{author_name}}</p><p>{{paper_title}}</p><a href=\"{{unsubscribe_url}}\" rel=\"noopener noreferrer\">Unsubscribe</a>','Hello {{author_name}} {{paper_title}} {{unsubscribe_url}}',1000)");
            sql("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,last_test_status,last_tested_at) VALUES ('"+smtp+"','Test','smtp.example.test',465,'TLS_IMPLICIT','sender@example.test','Sender','sender@example.test',10,100,120,100,true,'SUCCEEDED',now()+interval '1 minute')");
            sql("INSERT INTO mailbox_accounts(id,name,protocol,host,port,tls_mode,username,password_ciphertext,password_nonce,enabled,last_test_status,last_tested_at) VALUES ('"+mailbox+"','Test','IMAP','imap.example.test',993,'TLS_IMPLICIT','sender@example.test',decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),true,'SUCCEEDED',now()+interval '1 minute')");
            sql("INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,smtp_account_id,mailbox_account_id,from_name,from_email,reply_to,status) VALUES ('"+source+"','Source','Source','"+template+"','"+oldVersion+"','"+smtp+"','"+mailbox+"','Sender','sender@example.test','sender@example.test','PAUSED')");
            sql("INSERT INTO contacts(id,email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid) VALUES ('"+contact+"',decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),decode(repeat('03',32),'hex'),'example.test',decode(repeat('01',16),'hex'),true)");
            sql("INSERT INTO papers(id,arxiv_id,title,abstract_text,submitted_at,updated_at,pdf_url) VALUES ('"+paper+"','2610.12345','Mutable title','Bound abstract',now(),now(),'https://arxiv.org/pdf/2610.12345')");
            sql("INSERT INTO extraction_runs(id,paper_id,parser_version,status,started_at,completed_at) VALUES ('"+run+"','"+paper+"','test','SUCCEEDED',now(),now())");
            sql("INSERT INTO paper_author_contacts(id,paper_id,contact_id,extraction_run_id,confidence,verification_status) VALUES ('"+evidence+"','"+paper+"','"+contact+"','"+run+"','UNMAPPED','UNVERIFIED')");
            sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) VALUES ('"+evidence+"','source.tex','test','test')");
            sql("INSERT INTO campaign_recipients(id,campaign_id,contact_id,paper_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence,author_name_snapshot,paper_title_snapshot,paper_abstract_snapshot,arxiv_id_snapshot,personalization_status,rendered_subject,rendered_html,rendered_text,personalized_at) SELECT '"+recipient+"','"+source+"',id,'"+paper+"',email_ciphertext,email_nonce,email_hmac,email_domain,'UNMAPPED','Legacy guess','Frozen title','Frozen abstract','2610.54321','GENERATED','Old','Old HTML','Old text',now() FROM contacts WHERE id='"+contact+"'");
            sql("INSERT INTO outreach_content_reviews(campaign_id,recipient_id,policy_version,source_hash,content_hash,reviewed_at) VALUES ('"+source+"','"+recipient+"','old-policy',decode(repeat('04',32),'hex'),decode(repeat('05',32),'hex'),now())");
        }
        PreviewCommand previewCommand(){return new PreviewCommand(template,version,List.of(recipient));}
        MigrationPreview preview(){return service.preview(source,actor,previewCommand()).block(Duration.ofSeconds(15));}
        MigrationCommand command(String key){var p=preview();return new MigrationCommand(p.expectedLockVersion(),template,version,p.expectedTemplateLockVersion(),p.recipientIds(),p.expectedMembershipDigest(),key);}
        MigrationResult migrate(String key){return service.migrate(source,actor,CONTEXT,command(key)).block(Duration.ofSeconds(15));}
        void safety(String status,String messageStatus,String attemptStatus)throws Exception{UUID run=UUID.randomUUID(),message=UUID.randomUUID();sql("INSERT INTO campaign_safety_runs(id,campaign_id,smtp_account_id,recipient_limit,destination_hmac,destination_masked,status,from_name_snapshot,from_email_snapshot,reply_to_snapshot,tracking_opens_enabled,tracking_clicks_enabled) VALUES ('"+run+"','"+source+"','"+smtp+"',1,decode(repeat('01',32),'hex'),'s***@example.test','"+status+"','Sender','sender@example.test','sender@example.test',false,false)");sql("INSERT INTO campaign_safety_messages(id,run_id,campaign_recipient_id,smtp_account_id,status) VALUES ('"+message+"','"+run+"','"+recipient+"','"+smtp+"','"+messageStatus+"')");sql("INSERT INTO campaign_safety_attempts(safety_message_id,attempt_number,idempotency_key,status,started_at,completed_at) VALUES ('"+message+"',1,'"+UUID.randomUUID()+"','"+attemptStatus+"',now(),now())");}
        String history()throws Exception{return value("SELECT jsonb_build_array((SELECT to_jsonb(r) FROM campaign_recipients r WHERE id='"+recipient+"'),(SELECT jsonb_agg(to_jsonb(s)) FROM campaign_safety_runs s),(SELECT jsonb_agg(to_jsonb(s)) FROM campaign_safety_messages s),(SELECT jsonb_agg(to_jsonb(s)) FROM campaign_safety_attempts s),(SELECT jsonb_agg(to_jsonb(rv)) FROM outreach_content_reviews rv WHERE campaign_id='"+source+"'),(SELECT to_jsonb(v) FROM email_template_versions v JOIN campaigns c ON c.template_version_id=v.id WHERE c.id='"+source+"'))::text");}
        String inventory()throws Exception{return value("SELECT jsonb_build_array((SELECT count(*) FROM campaigns),(SELECT count(*) FROM segments),(SELECT count(*) FROM campaign_recipients),(SELECT count(*) FROM campaign_template_migrations),(SELECT count(*) FROM campaign_recipient_transfers),(SELECT count(*) FROM outreach_content_reviews),(SELECT count(*) FROM audit_logs),(SELECT count(*) FROM outbox_messages),(SELECT count(*) FROM jobs),(SELECT count(*) FROM bulk_outreach_reservations))::text");}
        void sql(String sql)throws Exception{try(var s=jdbc.createStatement()){s.execute(sql);}}
        String value(String sql)throws Exception{try(var s=jdbc.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
        long count(String sql)throws Exception{return Long.parseLong(value(sql));}
        public void close()throws Exception{jdbc.close();if(container.isRunning())container.stop();}
    }
}
