package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.CampaignConflictException;
import com.camel_hub.advertisement.campaign.CampaignNotFoundException;
import com.camel_hub.advertisement.campaign.CampaignValidationException;
import io.r2dbc.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.sql.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CampaignTemplateMigrationRepositoryIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine");
    private static java.sql.Connection jdbc;
    private static String url, user, password, schema;
    private static DatabaseClient db;
    private static int paperSequence=10000;
    private static TransactionalOperator transactions;
    private CampaignTemplateMigrationRepository repository;
    private UUID actor, template, version, smtp, source, target, foreign, recipient, targetRecipient, foreignRecipient;

    @BeforeAll static void database() throws Exception {
        url = System.getenv("OUTREACH_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            POSTGRES.start(); url = POSTGRES.getJdbcUrl(); user = POSTGRES.getUsername(); password = POSTGRES.getPassword();
        } else {
            user = System.getenv("OUTREACH_TEST_DB_USERNAME"); password = System.getenv("OUTREACH_TEST_DB_PASSWORD");
        }
        jdbc = DriverManager.getConnection(url, user, password);
        schema = "migration_task1_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        jdbc.setSchema(schema);
        var migration = flyway(schema, "28").migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(28);
        var upgrade = flyway(schema, "29").migrate();
        assertThat(upgrade.migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.targetSchemaVersion).isEqualTo("29");
        var provenance = flyway(schema, "31").migrate();
        assertThat(provenance.migrationsExecuted).isEqualTo(1);
        assertThat(provenance.targetSchemaVersion).isEqualTo("31");
        var admission = flyway(schema, "32").migrate();
        assertThat(admission.migrationsExecuted).isEqualTo(1);
        assertThat(admission.targetSchemaVersion).isEqualTo("32");
        var prepared = flyway(schema, null).migrate();
        assertThat(prepared.migrationsExecuted).isEqualTo(1);
        assertThat(prepared.targetSchemaVersion).isEqualTo("33");
        var factory = ConnectionFactories.get(ConnectionFactoryOptions.parse("r2dbc:" + url.substring(5)).mutate()
                .option(ConnectionFactoryOptions.USER, user).option(ConnectionFactoryOptions.PASSWORD, password)
                .option(Option.valueOf("schema"), schema).build());
        db = DatabaseClient.create(factory);
        transactions = TransactionalOperator.create(new R2dbcTransactionManager(factory));
    }
    private static Flyway flyway(String name, String target) {
        var configuration = Flyway.configure().dataSource(url, user, password).schemas(name).defaultSchema(name).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }
    @AfterAll static void close() throws Exception { if (jdbc != null) jdbc.close(); if (POSTGRES.isRunning()) POSTGRES.stop(); }

    @BeforeEach void seed() throws Exception {
        repository = new CampaignTemplateMigrationRepository(db, Duration.ofDays(180), Set.of());
        actor = UUID.randomUUID(); template = UUID.randomUUID(); version = UUID.randomUUID(); smtp = UUID.randomUUID();
        source = UUID.randomUUID(); target = UUID.randomUUID(); foreign = UUID.randomUUID();
        recipient = UUID.randomUUID(); targetRecipient = UUID.randomUUID(); foreignRecipient = UUID.randomUUID();
        execute("INSERT INTO users(id,username,email,password_hash,display_name) VALUES ('" + actor + "','" + actor + "','" + actor + "@example.test','test','Test')");
        execute("INSERT INTO email_templates(id,name,status) VALUES ('" + template + "','" + template + "','ACTIVE')");
        execute("INSERT INTO email_template_versions(id,template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes) VALUES ('" + version + "','" + template + "',1,'Subject','Sender','sender@example.test','{{unsubscribe_url}}','{{unsubscribe_url}}',100)");
        execute("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,last_test_status,last_tested_at) VALUES ('" + smtp + "','" + smtp + "','smtp.example.test',465,'TLS_IMPLICIT','sender@example.test','Sender','sender@example.test',10,100,120,100,true,'SUCCEEDED',now()+interval '1 minute')");
        for (UUID campaign : List.of(source, target, foreign)) execute("INSERT INTO campaigns(id,name,purpose,template_id,template_version_id,smtp_account_id,from_name,from_email,reply_to,status) VALUES ('" + campaign + "','Test','Test','" + template + "','" + version + "','" + smtp + "','Sender','sender@example.test','sender@example.test','DRAFT')");
        insertRecipient(recipient, source); insertRecipient(targetRecipient, target); insertRecipient(foreignRecipient, foreign);
    }
    private void insertRecipient(UUID id, UUID campaign) throws Exception {
        execute("INSERT INTO campaign_recipients(id,campaign_id,email_ciphertext,email_nonce,email_hmac,email_domain,confidence) VALUES ('" + id + "','" + campaign + "',decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),decode('" + id.toString().replace("-", "").repeat(2) + "','hex'),'example.test','HIGH')");
    }

    @Test void freshSchemaMigratesToLatestWithoutChangingV29Baseline() {
        String fresh = "migration_fresh_" + UUID.randomUUID().toString().replace("-", "");
        var result = flyway(fresh, null).migrate();
        assertThat(result.migrationsExecuted).isEqualTo(32);
        assertThat(result.targetSchemaVersion).isEqualTo("33");
    }

    @Test void provenanceUpgradeLeavesLegacyTransfersUnverified()throws Exception {
        String baseline="migration_legacy_"+UUID.randomUUID().toString().replace("-","");
        flyway(baseline,"29").migrate();jdbc.setSchema(baseline);
        try {
            seed();UUID id=ledger("legacy");transfer(id,source,recipient,target,targetRecipient);
            assertThat(flyway(baseline,null).migrate().migrationsExecuted).isEqualTo(3);
            try(var statement=jdbc.createStatement();var rows=statement.executeQuery("SELECT source_hash,content_hash,provenance FROM campaign_recipient_transfers WHERE migration_id='"+id+"'")){
                assertThat(rows.next()).isTrue();assertThat(rows.getBytes(1)).isNull();assertThat(rows.getBytes(2)).isNull();assertThat(rows.getString(3)).isNull();
            }
        }finally{jdbc.setSchema(schema);}
    }

    @Test void preparationUpgradeNeverTrustsOrRewritesLegacyPreparedBodies() throws Exception {
        String baseline="migration_prepared_"+UUID.randomUUID().toString().replace("-","");
        flyway(baseline,"31").migrate();jdbc.setSchema(baseline);
        try {
            seed();UUID id=ledger("legacy-prepared");transfer(id,source,recipient,target,targetRecipient);
            execute("UPDATE campaign_recipients SET rendered_subject='Legacy',rendered_html='Legacy prepared HTML',rendered_text='Legacy prepared text' WHERE id='"+targetRecipient+"'");
            assertThat(flyway(baseline,null).migrate().migrationsExecuted).isEqualTo(2);
            try(var statement=jdbc.createStatement();var rows=statement.executeQuery("SELECT r.rendered_subject,r.rendered_html,r.rendered_text,(SELECT count(*) FROM campaign_prepared_content_proofs) FROM campaign_recipients r WHERE r.id='"+targetRecipient+"'")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("Legacy");
                assertThat(rows.getString(2)).isEqualTo("Legacy prepared HTML");assertThat(rows.getString(3)).isEqualTo("Legacy prepared text");
                assertThat(rows.getLong(4)).isZero();
            }
        }finally{jdbc.setSchema(schema);}
    }

    @Test void everyConnectionUsesThePrivateFixtureSchemaRatherThanPublic() throws Exception {
        assertThat(jdbc.getSchema()).isEqualTo(schema);
        var probes=reactor.core.publisher.Flux.range(0,16).flatMap(ignored -> db.sql("""
                SELECT current_schema() AS schema,current_setting('search_path') AS search_path,
                    EXISTS(SELECT 1 FROM campaigns WHERE id=:source) AS private_fixture
                """).bind("source",source).map((r,m) -> Map.of("schema",r.get("schema",String.class),
                        "search_path",r.get("search_path",String.class),"private_fixture",r.get("private_fixture",Boolean.class))).one())
                .collectList().block(Duration.ofSeconds(10));
        assertThat(probes).hasSize(16).allSatisfy(probe -> {
            assertThat(probe).containsEntry("schema",schema).containsEntry("search_path",schema).containsEntry("private_fixture",true);
        });
    }

    // Catches missing ownership FKs, unique source/target constraints and actor-scoped idempotency.
    @Test void ledgerRejectsDuplicateAndWrongOwnershipWithoutDeletingHistory() throws Exception {
        UUID migration = ledger("first");
        transfer(migration, source, recipient, target, targetRecipient);
        UUID second = ledger("second");
        assertThatThrownBy(() -> transfer(second, source, recipient, target, targetRecipient)).isInstanceOf(SQLException.class).hasMessageContaining("unique");
        UUID otherSourceRecipient = UUID.randomUUID(); insertRecipient(otherSourceRecipient, source);
        assertThatThrownBy(() -> transfer(second, source, otherSourceRecipient, target, targetRecipient)).isInstanceOf(SQLException.class).hasMessageContaining("unique");
        assertThatThrownBy(() -> transfer(second, source, foreignRecipient, target, otherSourceRecipient)).isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
        UUID otherTarget = UUID.randomUUID(); insertRecipient(otherTarget, target);
        assertThatThrownBy(() -> transfer(second, source, otherSourceRecipient, target, foreignRecipient)).isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
        assertThatThrownBy(() -> transfer(second, foreign, foreignRecipient, target, otherTarget)).isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
        assertThatThrownBy(() -> ledger("first")).isInstanceOf(SQLException.class).hasMessageContaining("unique");
        assertThatThrownBy(() -> execute("DELETE FROM campaigns WHERE id='" + source + "'")).isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
        assertThat(repository.findByKey(actor, "first").block().targetCampaignId()).isEqualTo(target);
        assertThatThrownBy(() -> repository.findByKey(actor, "absent").block()).isInstanceOf(CampaignNotFoundException.class);
        assertThatThrownBy(() -> repository.findByKey(UUID.randomUUID(), "first").block()).isInstanceOf(CampaignNotFoundException.class);
    }

    @Test void ledgerChecksStatusCountsKeyAndTemplateOwnership() throws Exception {
        for (String key : List.of("", "bad.key", "a".repeat(129))) assertThatThrownBy(() -> ledger(key)).isInstanceOf(SQLException.class);
        UUID migration = ledger("valid");
        for (String update : List.of("status='BLOCKED'", "migrated_count=0", "selected_count=121", "target_campaign_id=NULL", "request_digest=decode('00','hex')"))
            assertThatThrownBy(() -> execute("UPDATE campaign_template_migrations SET " + update + " WHERE id='" + migration + "'")).isInstanceOf(SQLException.class);
        UUID other = UUID.randomUUID();
        execute("INSERT INTO email_templates(id,name,status) VALUES ('" + other + "','" + other + "','ACTIVE')");
        assertThatThrownBy(() -> execute("UPDATE campaign_template_migrations SET target_template_id='" + other + "' WHERE id='" + migration + "'")).isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
    }

    @Test void snapshotsRejectMissingForeignAndStaleMembershipAndExposeBlockedFacts() throws Exception {
        var preview = new CampaignTemplateMigrationDtos.PreviewCommand(template, version, List.of(recipient));
        var snapshot = repository.readSnapshot(source, preview).block();
        assertThat(snapshot.recipients()).hasSize(1);
        assertThat(new CampaignTemplateMigrationPolicy().blockers(snapshot)).isNotEmpty(); // no source/contact: fail closed
        assertThatThrownBy(() -> repository.readSnapshot(source, new CampaignTemplateMigrationDtos.PreviewCommand(template, version, List.of(recipient, foreignRecipient))).block()).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(() -> repository.readSnapshot(UUID.randomUUID(), preview).block()).isInstanceOf(CampaignNotFoundException.class);
        var command = new CampaignTemplateMigrationDtos.MigrationCommand(1, template, version, 0, List.of(recipient), "digest", "key");
        assertThatThrownBy(() -> transactions.transactional(repository.lockSnapshot(source, command)).block()).isInstanceOf(CampaignConflictException.class);
        assertThatThrownBy(() -> repository.lockSnapshot(source, command).block()).isInstanceOf(IllegalStateException.class);
        execute("UPDATE campaigns SET generation_status='FAILED' WHERE id='" + source + "'");
        assertThat(repository.readSnapshot(source, preview).block().campaign().generationStatus()).isEqualTo("FAILED");
    }

    @Test void lowConfidenceLegacyNameIsNotTrustedButSamePaperPublicSourceRemainsEligible() throws Exception {
        eligibleSource("LOW", false, "UNVERIFIED");
        execute("UPDATE campaign_recipients SET author_name_snapshot='Legacy guessed identity' WHERE id='" + recipient + "'");
        var preview = new CampaignTemplateMigrationDtos.PreviewCommand(template, version, List.of(recipient));
        var snapshot = repository.readSnapshot(source, preview).block();
        var frozen = snapshot.recipients().getFirst();
        assertThat(frozen.sourceAvailable()).isTrue();
        assertThat(frozen.eligible()).isTrue();
        assertThat(frozen.source().identityTrusted()).isFalse();
        assertThat(frozen.source().confidence()).isEqualTo("LOW");
        assertThat(frozen.source().humanVerified()).isFalse();
        assertThat(frozen.frozenTarget().authorName()).isEqualTo("Legacy guessed identity");
        assertThat(new CampaignTemplateMigrationPolicy().blockers(snapshot)).isEmpty();
        assertThat(new CampaignTemplateMigrationPolicy().membershipDigest(repository.readSnapshot(source, preview).block()))
                .isEqualTo(new CampaignTemplateMigrationPolicy().membershipDigest(snapshot));
        execute("UPDATE paper_author_contacts SET human_verified=true,verification_status='CONFIRMED' WHERE contact_id='" + frozen.contactId() + "'");
        assertThat(repository.readSnapshot(source, preview).block().recipients().getFirst().source().identityTrusted()).isTrue();
    }

    @Test void formalAttemptsAlwaysBlockWhileTerminalSafetyEvidenceIsRetained() throws Exception {
        eligibleSource("UNMAPPED", false, "UNVERIFIED");
        UUID[] safety = safetyEvidence("COMPLETED", "SMTP_ACCEPTED", "SMTP_ACCEPTED");
        var preview = new CampaignTemplateMigrationDtos.PreviewCommand(template, version, List.of(recipient));
        var clean = repository.readSnapshot(source, preview).block();
        assertThat(clean.recipients().getFirst().safetyActiveOrUnknown()).isFalse();
        assertThat(new CampaignTemplateMigrationPolicy().blockers(clean)).isEmpty();
        for (String status : List.of("QUEUED", "RUNNING")) {
            execute("UPDATE campaign_safety_runs SET status='" + status + "' WHERE id='" + safety[0] + "'");
            assertThat(repository.readSnapshot(source, preview).block().recipients().getFirst().safetyActiveOrUnknown()).isTrue();
        }
        execute("UPDATE campaign_safety_runs SET status='COMPLETED' WHERE id='" + safety[0] + "'");
        execute("UPDATE campaign_safety_attempts SET status='OUTCOME_UNKNOWN',outcome_unknown_reason='TEST_ONLY' WHERE id='" + safety[2] + "'");
        assertThat(repository.readSnapshot(source, preview).block().recipients().getFirst().safetyActiveOrUnknown()).isTrue();
        execute("UPDATE campaign_safety_attempts SET status='SMTP_ACCEPTED',outcome_unknown_reason=NULL WHERE id='" + safety[2] + "'");
        execute("INSERT INTO delivery_attempts(campaign_recipient_id,smtp_account_id,attempt_number,idempotency_key,status,started_at,completed_at) VALUES ('" + recipient + "','" + smtp + "',1,'" + UUID.randomUUID() + "','PERMANENT_FAILURE',now(),now())");
        var attempted = repository.readSnapshot(source, preview).block();
        assertThat(attempted.recipients().getFirst().formalSideEffects()).isTrue();
        assertThat(new CampaignTemplateMigrationPolicy().blockers(attempted)).extracting("code").contains("FORMAL_SIDE_EFFECTS");
        assertThat(new CampaignTemplateMigrationPolicy().membershipDigest(attempted)).isNotEqualTo(new CampaignTemplateMigrationPolicy().membershipDigest(clean));
    }

    @Test void unknownSafetyForAnotherSourceRecipientBlocksTheWholeSource() throws Exception {
        UUID other=UUID.randomUUID(); insertRecipient(other,source);
        UUID[] safety=safetyEvidence("FAILED","OUTCOME_UNKNOWN","OUTCOME_UNKNOWN");
        execute("UPDATE campaign_safety_messages SET campaign_recipient_id='" + other + "' WHERE id='" + safety[1] + "'");
        var snapshot=repository.readSnapshot(source,new CampaignTemplateMigrationDtos.PreviewCommand(template,version,List.of(recipient))).block();
        assertThat(snapshot.recipients().getFirst().safetyActiveOrUnknown()).isTrue();
    }

    @Test void everyLiveEligibilityExclusionRemainsFailClosed() throws Exception {
        eligibleSource("UNMAPPED",false,"UNVERIFIED");
        var preview=new CampaignTemplateMigrationDtos.PreviewCommand(template,version,List.of(recipient));
        var clean=repository.readSnapshot(source,preview).block();
        String hmac=clean.recipients().getFirst().emailHmac();
        UUID contact=clean.recipients().getFirst().contactId();
        var excluded=new CampaignTemplateMigrationRepository(db,Duration.ofDays(180),Set.of(hmac));
        assertThat(excluded.readSnapshot(source,preview).block().recipients().getFirst().eligible()).isFalse();
        for (String condition:List.of("syntax_valid=false","example_address=true","suppression_status='UNSUBSCRIBED'","deleted_at=now()")) {
            execute("UPDATE contacts SET " + condition + " WHERE id='" + contact + "'");
            assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().eligible()).as(condition).isFalse();
            execute("UPDATE contacts SET syntax_valid=true,example_address=false,suppression_status='ACTIVE',deleted_at=NULL WHERE id='" + contact + "'");
        }
        execute("INSERT INTO recipient_delivery_cooldowns(email_hmac,last_smtp_accepted_at) VALUES (decode('" + hmac + "','hex'),now())");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().eligible()).isFalse();
        execute("UPDATE recipient_delivery_cooldowns SET last_smtp_accepted_at=now()-interval '181 days' WHERE email_hmac=decode('" + hmac + "','hex')");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().eligible()).isTrue();
        execute("INSERT INTO suppression_entries(email_hmac,email_domain,reason,source) VALUES (decode('" + hmac + "','hex'),'example.test','MANUAL','TEST')");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().eligible()).isFalse();
        assertThat(new CampaignTemplateMigrationPolicy().membershipDigest(repository.readSnapshot(source,preview).block()))
                .isNotEqualTo(new CampaignTemplateMigrationPolicy().membershipDigest(clean));
    }

    @Test void actualFrozenContentTemplateAndSenderChangesInvalidateDigest() throws Exception {
        eligibleSource("HIGH",false,"UNVERIFIED");
        var preview=new CampaignTemplateMigrationDtos.PreviewCommand(template,version,List.of(recipient));
        var policy=new CampaignTemplateMigrationPolicy();
        String original=policy.membershipDigest(repository.readSnapshot(source,preview).block());
        for (String field:List.of("rendered_subject","rendered_html","rendered_text","author_name_snapshot","paper_title_snapshot",
                "paper_abstract_snapshot","arxiv_id_snapshot","category_snapshot","organization_snapshot","personalization_rationale")) {
            execute("UPDATE campaign_recipients SET " + field + "='Changed frozen evidence' WHERE id='" + recipient + "'");
            assertThat(policy.membershipDigest(repository.readSnapshot(source,preview).block())).as(field).isNotEqualTo(original);
            String originalValue=field.equals("paper_title_snapshot")?"'Bound frozen paper'":"NULL";
            execute("UPDATE campaign_recipients SET " + field + "=" + originalValue + " WHERE id='" + recipient + "'");
            assertThat(policy.membershipDigest(repository.readSnapshot(source,preview).block())).as("restored " + field).isEqualTo(original);
        }
        execute("UPDATE email_template_versions SET text_content='Changed immutable content {{unsubscribe_url}}' WHERE id='" + version + "'");
        assertThat(policy.membershipDigest(repository.readSnapshot(source,preview).block())).isNotEqualTo(original);
        execute("UPDATE email_template_versions SET text_content='{{unsubscribe_url}}' WHERE id='" + version + "'");
        execute("UPDATE smtp_accounts SET lock_version=lock_version+1 WHERE id='" + smtp + "'");
        assertThat(policy.membershipDigest(repository.readSnapshot(source,preview).block())).isNotEqualTo(original);
        var stale=new CampaignTemplateMigrationDtos.MigrationCommand(0,template,version,1,List.of(recipient),original,"key");
        assertThatThrownBy(() -> transactions.transactional(repository.lockSnapshot(source,stale)).block()).isInstanceOf(CampaignConflictException.class);
    }

    @Test void completeFrozenPublicInputsTakePrecedenceWithoutBypassingSafetyProvenance() throws Exception {
        eligibleSource("UNMAPPED",false,"UNVERIFIED");
        var preview=new CampaignTemplateMigrationDtos.PreviewCommand(template,version,List.of(recipient));
        var baseline=repository.readSnapshot(source,preview).block().recipients().getFirst();
        execute("UPDATE campaign_recipients SET paper_title_snapshot='Original frozen title',paper_abstract_snapshot='Original frozen abstract',arxiv_id_snapshot='2609.00001' WHERE id='" + recipient + "'");
        execute("UPDATE papers SET title='',abstract_text='',arxiv_id='invalid-live-id-" + paperSequence++ + "' WHERE id='" + baseline.paperId() + "'");
        var frozen=repository.readSnapshot(source,preview).block().recipients().getFirst();
        assertThat(frozen.sourceAvailable()).isTrue();
        assertThat(frozen.frozenTarget().paperTitle()).isEqualTo("Original frozen title");
        assertThat(frozen.frozenTarget().paperAbstract()).isEqualTo("Original frozen abstract");
        assertThat(frozen.frozenTarget().arxivId()).isEqualTo("2609.00001");
        assertThat(frozen.source().paperAbstract()).isEmpty();
        execute("UPDATE paper_author_contacts SET verification_status='REJECTED' WHERE id='" + frozen.source().evidenceId() + "'");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().sourceAvailable()).isFalse();
    }

    @Test void missingFrozenFieldsRequireSafeCapturedSamePaperInputs() throws Exception {
        eligibleSource("UNMAPPED",false,"UNVERIFIED");
        var preview=new CampaignTemplateMigrationDtos.PreviewCommand(template,version,List.of(recipient));
        var baseline=repository.readSnapshot(source,preview).block().recipients().getFirst();
        assertThat(baseline.frozenTarget().paperAbstract()).isNull();
        assertThat(baseline.source().paperAbstract()).isEqualTo("Bound public abstract");
        assertThat(baseline.sourceAvailable()).isTrue();
        execute("UPDATE papers SET abstract_text='' WHERE id='" + baseline.paperId() + "'");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().sourceAvailable()).isFalse();
        execute("UPDATE papers SET abstract_text='Captured later public abstract' WHERE id='" + baseline.paperId() + "'");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().sourceAvailable()).isTrue();
        assertThat(baseline.source().paperAbstract()).isEqualTo("Bound public abstract"); // original immutable input never re-hydrates
        execute("UPDATE papers SET arxiv_id='invalid-live-id-" + paperSequence++ + "' WHERE id='" + baseline.paperId() + "'");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().sourceAvailable()).isFalse();
        execute("UPDATE campaign_recipients SET arxiv_id_snapshot='2610.00001' WHERE id='" + recipient + "'");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().sourceAvailable()).isTrue();
        execute("DELETE FROM extraction_evidence WHERE paper_author_contact_id='" + baseline.source().evidenceId() + "'");
        assertThat(repository.readSnapshot(source,preview).block().recipients().getFirst().sourceAvailable()).isFalse();
    }

    @Test void lockedSnapshotRetainsCampaignRecipientAndAccountLocksUntilCallerCompletes() {
        var command = new CampaignTemplateMigrationDtos.MigrationCommand(0, template, version, 0, List.of(recipient), "digest", "key");
        transactions.transactional(repository.lockSnapshot(source, command).flatMap(snapshot -> Mono.fromCallable(() -> {
            for (String tableAndId : List.of("campaigns:" + source, "campaign_recipients:" + recipient, "email_templates:" + template, "smtp_accounts:" + smtp)) {
                String[] target = tableAndId.split(":");
                assertThatThrownBy(() -> execute("SELECT id FROM " + target[0] + " WHERE id='" + target[1] + "' FOR UPDATE NOWAIT"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("could not obtain lock");
            }
            return snapshot;
        }))).block();
        assertThatCode(() -> execute("SELECT id FROM campaigns WHERE id='" + source + "' FOR UPDATE NOWAIT")).doesNotThrowAnyException();
    }

    @Test void upgradeRetainsExistingSafetyAttemptsWithNullSmtpStartedAt() throws Exception {
        String baseline = "migration_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        flyway(baseline, "28").migrate();
        jdbc.setSchema(baseline);
        try {
            seed();
            UUID attempt = safetyEvidence("COMPLETED", "SMTP_ACCEPTED", "SMTP_ACCEPTED")[2];
            var result = flyway(baseline, "29").migrate();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            try (var statement = jdbc.createStatement(); var rows = statement.executeQuery("SELECT smtp_started_at,status FROM campaign_safety_attempts WHERE id='" + attempt + "'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getTimestamp(1)).isNull(); assertThat(rows.getString(2)).isEqualTo("SMTP_ACCEPTED");
            }
        } finally { jdbc.setSchema(schema); }
    }

    private void eligibleSource(String confidence, boolean verified, String verification) throws Exception {
        UUID contact=UUID.randomUUID(), paper=UUID.randomUUID(), extraction=UUID.randomUUID(), author=UUID.randomUUID(), mapping=UUID.randomUUID(), evidence=UUID.randomUUID(), mailbox=UUID.randomUUID();
        execute("INSERT INTO contacts(id,email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid) SELECT '" + contact + "',email_ciphertext,email_nonce,email_hmac,email_domain,decode(repeat('01',16),'hex'),true FROM campaign_recipients WHERE id='" + recipient + "'");
        execute("INSERT INTO papers(id,arxiv_id,title,abstract_text,submitted_at,updated_at,pdf_url) VALUES ('" + paper + "','2610." + paperSequence++ + "','Bound public paper','Bound public abstract',now(),now(),'https://arxiv.org/pdf/2610.00001')");
        execute("INSERT INTO authors(id,normalized_name,display_name) VALUES ('" + author + "','Test author','Test author')");
        execute("INSERT INTO paper_authors(id,paper_id,author_id,author_order,raw_name) VALUES ('" + mapping + "','" + paper + "','" + author + "',1,'Test author')");
        execute("INSERT INTO extraction_runs(id,paper_id,parser_version,status,started_at,completed_at) VALUES ('" + extraction + "','" + paper + "','test','SUCCEEDED',now(),now())");
        execute("INSERT INTO paper_author_contacts(id,paper_id,contact_id,paper_author_id,extraction_run_id,confidence,human_verified,verification_status) VALUES ('" + evidence + "','" + paper + "','" + contact + "','" + mapping + "','" + extraction + "','" + confidence + "'," + verified + ",'" + verification + "')");
        execute("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) VALUES ('" + evidence + "','source.tex','source-email','test source')");
        execute("INSERT INTO mailbox_accounts(id,name,protocol,host,port,tls_mode,username,password_ciphertext,password_nonce,enabled,last_test_status,last_tested_at) VALUES ('" + mailbox + "','" + mailbox + "','IMAP','imap.example.test',993,'TLS_IMPLICIT','sender@example.test',decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),true,'SUCCEEDED',now()+interval '1 minute')");
        execute("UPDATE campaigns SET mailbox_account_id='" + mailbox + "' WHERE id='" + source + "'");
        execute("UPDATE campaign_recipients SET contact_id='" + contact + "',paper_id='" + paper + "',paper_title_snapshot='Bound frozen paper' WHERE id='" + recipient + "'");
    }
    private UUID[] safetyEvidence(String runStatus,String messageStatus,String attemptStatus) throws Exception {
        UUID run=UUID.randomUUID(), message=UUID.randomUUID(), attempt=UUID.randomUUID();
        execute("INSERT INTO campaign_safety_runs(id,campaign_id,smtp_account_id,recipient_limit,destination_hmac,destination_masked,status,from_name_snapshot,from_email_snapshot,reply_to_snapshot,tracking_opens_enabled,tracking_clicks_enabled) VALUES ('" + run + "','" + source + "','" + smtp + "',1,decode(repeat('01',32),'hex'),'s***@example.test','" + runStatus + "','Sender','sender@example.test','sender@example.test',false,false)");
        execute("INSERT INTO campaign_safety_messages(id,run_id,campaign_recipient_id,smtp_account_id,status) VALUES ('" + message + "','" + run + "','" + recipient + "','" + smtp + "','" + messageStatus + "')");
        execute("INSERT INTO campaign_safety_attempts(id,safety_message_id,attempt_number,idempotency_key,status,started_at,completed_at) VALUES ('" + attempt + "','" + message + "',1,'" + attempt + "','" + attemptStatus + "',now(),now())");
        return new UUID[]{run,message,attempt};
    }

    private UUID ledger(String key) throws Exception {
        UUID id = UUID.randomUUID();
        try (var statement = jdbc.prepareStatement("INSERT INTO campaign_template_migrations(id,actor_id,idempotency_key,request_digest,membership_digest,source_campaign_id,target_campaign_id,target_template_id,target_template_version_id,source_lock_version,target_template_lock_version,selected_count,migrated_count,status) VALUES (?,?,?,decode(repeat('01',32),'hex'),decode(repeat('02',32),'hex'),?,?,?,?,0,0,1,1,'MIGRATED')")) {
            statement.setObject(1,id); statement.setObject(2,actor); statement.setString(3,key); statement.setObject(4,source); statement.setObject(5,target); statement.setObject(6,template); statement.setObject(7,version); statement.executeUpdate();
        }
        return id;
    }
    private void transfer(UUID migration, UUID sourceCampaign, UUID sourceRecipient, UUID targetCampaign, UUID targetRecipient) throws Exception {
        execute("INSERT INTO campaign_recipient_transfers(migration_id,source_campaign_id,source_recipient_id,target_campaign_id,target_recipient_id) VALUES ('" + migration + "','" + sourceCampaign + "','" + sourceRecipient + "','" + targetCampaign + "','" + targetRecipient + "')");
    }
    private static void execute(String sql) throws Exception { try (var statement = jdbc.createStatement()) { statement.execute(sql); } }
}
