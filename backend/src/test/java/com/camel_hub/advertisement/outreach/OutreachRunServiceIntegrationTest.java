package com.camel_hub.advertisement.outreach;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionRepository;
import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionService;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportRepository;
import com.camel_hub.advertisement.arxiv.search.ArxivQueryNormalizer;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mutations these tests catch: missing payload conflict, duplicate stage execution,
 * incomplete import manifests, extraction failure replay, partial preflight send,
 * stale cancellation, and exposed credentials. */
@Timeout(30)
class OutreachRunServiceIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("camel_outreach_test").withUsername("camel").withPassword("test-only");
    private final UUID actor = UUID.randomUUID();
    private final UUID smtp = UUID.randomUUID();
    private final UUID mailbox = UUID.randomUUID();
    private final UUID template = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();
    private DatabaseClient db;
    private OutreachRunRepository repository;
    private OutreachRunService service;
    private ArxivImportService imports;
    private OutreachTemplateCampaignService adapter;
    private CampaignService campaigns;
    private CampaignPreflightService preflight;
    private CampaignWorkflowService workflow;
    private TransactionalOperator tx;
    private ObjectMapper mapper;
    private ConnectionPool pool;
    private static boolean migrated;

    @BeforeEach void setup() {
        String jdbc=System.getenv("OUTREACH_TEST_JDBC_URL");
        String username=System.getenv().getOrDefault("OUTREACH_TEST_DB_USERNAME","outreach_test");
        String password=System.getenv().getOrDefault("OUTREACH_TEST_DB_PASSWORD","outreach_test_only");
        if (jdbc==null) {
            if(!POSTGRES.isRunning()) POSTGRES.start();
            jdbc=POSTGRES.getJdbcUrl();username=POSTGRES.getUsername();password=POSTGRES.getPassword();
        }
        String boundedJdbc=jdbc+(jdbc.contains("?")?"&":"?")+"connectTimeout=5&socketTimeout=20";
        if(!migrated) {
            Flyway.configure().dataSource(boundedJdbc,username,password).locations("classpath:db/migration").load().migrate();
            migrated=true;
        }
        var factory = ConnectionFactories.get(io.r2dbc.spi.ConnectionFactoryOptions.parse(jdbc.replace("jdbc:","r2dbc:"))
                .mutate().option(io.r2dbc.spi.ConnectionFactoryOptions.USER,username)
                .option(io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD,password).build());
        pool=new ConnectionPool(ConnectionPoolConfiguration.builder(factory).initialSize(1).maxSize(5).build());
        db = DatabaseClient.create(pool);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
        mapper = new ObjectMapper().findAndRegisterModules();
        db.sql("TRUNCATE outreach_runs, outbox_messages, contacts, segments, suppression_entries, recipient_delivery_cooldowns, users, smtp_accounts, mailbox_accounts, email_templates, papers, jobs CASCADE")
                .fetch().rowsUpdated().block();
        seed();
        var audit = mock(AuditService.class);
        when(audit.record(any())).thenReturn(Mono.empty());
        var hasher = mock(SensitiveValueHasher.class);
        when(hasher.hash(any())).thenReturn(new byte[]{1});
        imports = new ArxivImportService(new ArxivImportRepository(db),new ArxivQueryNormalizer(mapper,100),
                ()->Mono.just(Set.of("cs.AI")),audit,hasher,mapper,100,tx);
        adapter = mock(OutreachTemplateCampaignService.class);
        campaigns = mock(CampaignService.class);
        preflight = mock(CampaignPreflightService.class);
        workflow = mock(CampaignWorkflowService.class);
        repository = new OutreachRunRepository(db);
        service = service(audit, hasher, new OutreachProperties());
    }
    @AfterEach void disposePool() { if(pool!=null) pool.dispose(); }

    @Test void sameKeyAndPayloadReturnsOriginalRunButChangedPayloadConflicts() {
        var first = service.create(actor, command("same-key-123", 2), context()).block();
        var second = service.create(actor, command("same-key-123", 2), context()).block();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("outreach_runs")).isEqualTo(1);
        assertThatThrownBy(() -> service.create(actor, command("same-key-123", 3), context()).block())
                .isInstanceOf(OutreachConflictException.class);
    }

    @Test void missingConfirmationAndOverBudgetRejectWithoutWriting() {
        var bad = new OutreachDtos.CreateRequest("test", "EMAILS", 121, smtp, template, "invitation",
                criteria(), "AUTO_OUTREACH_SEND", "oversize-key");
        assertThatThrownBy(() -> service.create(actor, bad, context()).block())
                .isInstanceOf(OutreachValidationException.class);
        var noConfirm = new OutreachDtos.CreateRequest("test", "EMAILS", 2, smtp, template, "invitation",
                criteria(), "", "no-confirm-key");
        assertThatThrownBy(() -> service.create(actor, noConfirm, context()).block())
                .isInstanceOf(OutreachValidationException.class);
        assertThat(count("outreach_runs")).isZero();
    }

    @Test void stagesPersistAcrossServiceRestartAndImportIsNotCreatedAgain() {
        var run = service.create(actor, command("restart-key-123", 2), context()).block();
        service.advanceOne().block();
        UUID job=service.get(run.id()).block().importJobId();
        assertThat(job).isNotNull();
        db.sql("UPDATE jobs SET status='SUCCEEDED',ended_at=now() WHERE id=:job").bind("job",job).fetch().rowsUpdated().block();
        var restarted = service(mockAudit(), mockHasher(), new OutreachProperties());
        restarted.advanceOne().block();
        assertThat(service.get(run.id()).block().status()).isEqualTo("BLOCKED");
        assertThat(count("jobs")).isEqualTo(1);
    }

    @Test void sameCriteriaRunsReusePriorImportAndAlwaysBoundTo100Papers() {
        var first=service.create(actor,command("reuse-first-123",2),context()).block();
        service.advanceOne().block();
        UUID job=service.get(first.id()).block().importJobId();
        service.cancel(first.id(),actor,service.get(first.id()).block().lockVersion(),context()).block();
        var second=service.create(actor,command("reuse-second-123",3),context()).block();
        service.advanceOne().block();
        assertThat(service.get(second.id()).block().importJobId()).isEqualTo(job);
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(1);
        assertThat(db.sql("SELECT parameters->>'maxPapers' AS budget FROM jobs WHERE id=:job").bind("job",job)
                .map((r,m)->r.get("budget",String.class)).one().block()).isEqualTo("100");
    }

    @Test void actualCompleteImportManifestNotItemsOrGlobalPaperSearchIsUsed() {
        UUID job = job("SUCCEEDED");
        UUID included = paper("2610.00001");
        UUID unrelated = paper("2610.00002");
        db.sql("INSERT INTO paper_imports(paper_id,job_id,metadata_source) VALUES (:paper,:job,'LEGACY_API')")
                .bind("paper", included).bind("job", job).fetch().rowsUpdated().block();
        db.sql("INSERT INTO job_items(job_id,external_key,status) VALUES (:job,:paper,'SUCCEEDED')")
                .bind("job",job).bind("paper",unrelated.toString()).fetch().rowsUpdated().block();
        assertThat(repository.importManifest(job).collectList().block()).containsExactly(included);
    }

    @Test void everyCompletedExtractionOutcomeIsReusedAndFailureBlocksInsteadOfNoEmail() {
        UUID failed = paper("2610.00003");
        UUID unavailable = paper("2610.00004");
        UUID fresh = paper("2610.00005");
        extraction(failed, "FAILED");
        extraction(unavailable, "SOURCE_UNAVAILABLE");
        var state = repository.extractionState(List.of(failed, unavailable, fresh)).block();
        assertThat(state.pendingPaperIds()).containsExactly(fresh);
        assertThat(state.completedCount()).isEqualTo(2);
        assertThat(state.failedCount()).isEqualTo(2);
    }

    @Test void activeExtractionBlocksNoBlindResubmission() {
        UUID paper = paper("2610.00006");
        UUID job = job("RUNNING");
        db.sql("INSERT INTO job_items(job_id,external_key,status) VALUES (:job,:paper,'RUNNING')")
                .bind("job",job).bind("paper",paper.toString()).fetch().rowsUpdated().block();
        db.sql("UPDATE jobs SET type='ARXIV_FETCH_AND_PARSE_SOURCE' WHERE id=:job")
                .bind("job",job).fetch().rowsUpdated().block();
        assertThat(repository.extractionState(List.of(paper)).block().active()).isTrue();
    }

    @Test void terminalExtractionJobWithoutResultIsFailureNotFreshPaperToRetry() {
        UUID paper=paper("2610.00007");
        UUID job=job("FAILED");
        db.sql("UPDATE jobs SET type='ARXIV_FETCH_AND_PARSE_SOURCE' WHERE id=:job").bind("job",job).fetch().rowsUpdated().block();
        db.sql("INSERT INTO job_items(job_id,external_key,status) VALUES(:job,:paper,'FAILED')")
                .bind("job",job).bind("paper",paper.toString()).fetch().rowsUpdated().block();
        var state=repository.extractionState(List.of(paper)).block();
        assertThat(state.pendingPaperIds()).isEmpty();
        assertThat(state.failedCount()).isEqualTo(1);
        assertThat(state.completedCount()).isZero();
    }

    @Test void strictPreflightRejectsPartialEligibilityEvenWhenNormallyReady() {
        var partial = new CampaignPreflightService.PreflightView(true, Map.of(),
                Map.of("TOTAL", 2L, "ELIGIBLE", 1L), 1, "digest");
        assertThatThrownBy(() -> OutreachRunService.requireStrictPreflight(partial, 2))
                .isInstanceOf(OutreachValidationException.class);
        OutreachRunService.requireStrictPreflight(new CampaignPreflightService.PreflightView(true, Map.of(),
                Map.of("TOTAL",2L,"ELIGIBLE",2L),1,"digest"),2);
    }

    @Test void cancellationUsesOptimisticLockAndStopsScheduler() {
        var run = service.create(actor, command("cancel-key-123", 2), context()).block();
        assertThatThrownBy(() -> service.cancel(run.id(), actor, 99, context()).block())
                .isInstanceOf(OutreachConflictException.class);
        var canceled = service.cancel(run.id(), actor, run.lockVersion(), context()).block();
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(service.advanceOne().block()).isFalse();
        assertThat(count("jobs")).isZero();
    }

    @Test void optionsExposeOnlySummariesAndRequireCurrentTestedMatchingMailbox() {
        var options = service.options().block();
        assertThat(options.senders()).singleElement().satisfies(sender -> assertThat(sender.available()).isTrue());
        assertThat(mapper.valueToTree(options).toString()).doesNotContain("password", "ciphertext", "nonce", "host", "test-only");
        db.sql("UPDATE mailbox_accounts SET username='other@example.org'").fetch().rowsUpdated().block();
        assertThat(service.options().block().senders()).singleElement()
                .satisfies(sender -> assertThat(sender.available()).isFalse());
    }

    @Test void passwordResetRevokesSavedAuthorizationBeforeAutomaticMutation() {
        var run=service.create(actor,command("reset-key-123",2),context()).block();
        db.sql("UPDATE users SET force_password_change=true WHERE id=:actor").bind("actor",actor).fetch().rowsUpdated().block();
        service.advanceOne().block();
        var blocked=service.get(run.id()).block();
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.message()).contains("no longer has");
        assertThat(count("jobs")).isZero();
    }

    @Test void rollbackRemovesChildImportAndThenBlocksInsteadOfReplayingStep() {
        var failingAudit=mock(AuditService.class);
        when(failingAudit.record(any())).thenAnswer(invocation->{
            com.camel_hub.advertisement.audit.AuditEvent event=invocation.getArgument(0);
            return "OUTREACH_RUN_WAIT_IMPORT".equals(event.action())?Mono.error(new IllegalStateException("test fail after child created")):Mono.empty();
        });
        var transactional=service(failingAudit,mockHasher(),new OutreachProperties());
        var run=transactional.create(actor,command("rollback-key-123",2),context()).block();
        transactional.advanceOne().block();
        var blocked=transactional.get(run.id()).block();
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.importJobId()).isNull();
        assertThat(count("jobs")).isZero();
        assertThat(count("outbox_messages")).isZero();
        assertThat(transactional.advanceOne().block()).isFalse();
    }

    @Test void secondSchedulerSkipsLockedRunAndCreatesNoDuplicateJob() throws Exception {
        var run=service.create(actor,command("concurrent-key-123",2),context()).block();
        var acquired=new java.util.concurrent.CompletableFuture<UUID>();
        var release=reactor.core.publisher.Sinks.<Void>one();
        var holding=repository.lock(run.id()).flatMap(locked->{acquired.complete(locked.id());return release.asMono().thenReturn(locked);})
                .as(tx::transactional).toFuture();
        assertThat(acquired.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(run.id());
        try {
            assertThat(service.advanceOne().block(java.time.Duration.ofSeconds(5))).isFalse();
            assertThat(count("jobs")).isZero();
        } finally { release.tryEmitEmpty(); holding.get(5,java.util.concurrent.TimeUnit.SECONDS); }
        service.advanceOne().block();
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(1);
    }

    @Test void cancelUsesNormalCampaignTransactionAndCannotLeaveItRunning() {
        var run=service.create(actor,command("campaign-cancel-123",2),context()).block();
        UUID campaign=campaign("RUNNING",version);
        db.sql("UPDATE outreach_runs SET campaign_id=:campaign,status='SENDING',stage='SENDING' WHERE id=:run")
                .bind("campaign",campaign).bind("run",run.id()).fetch().rowsUpdated().block();
        campaigns=new CampaignService(new CampaignRepository(db),null,new PersonalizationProperties(false,"OPENAI","test",100),mapper,tx);
        workflow=new CampaignWorkflowService(new CampaignWorkflowRepository(db),preflight,campaigns,mockAudit(),mockHasher(),mapper,tx);
        service=service(mockAudit(),mockHasher(),new OutreachProperties());
        assertThat(service.cancel(run.id(),actor,run.lockVersion(),context()).block().status()).isEqualTo("CANCELED");
        assertThat(campaigns.get(campaign).block().status()).isEqualTo("CANCELED");
        assertThat(service.advanceOne().block()).isFalse();
    }

    @Test void productionHoldBlocksStartWithoutChangingOrClearingHold() {
        var properties=new OutreachProperties();properties.setProductionHoldReason("unresolved delivery diagnosis");
        var held=service(mockAudit(),mockHasher(),properties);
        var run=held.create(actor,command("production-hold-123",2),context()).block();
        UUID campaign=campaign("APPROVED",version);
        db.sql("UPDATE outreach_runs SET campaign_id=:campaign,stage='START',eligible_count=2,selected_count=2 WHERE id=:run")
                .bind("campaign",campaign).bind("run",run.id()).fetch().rowsUpdated().block();
        when(adapter.review(campaign)).thenReturn(Mono.empty());
        when(preflight.preflight(campaign)).thenReturn(Mono.just(new CampaignPreflightService.PreflightView(true,Map.of(),Map.of("TOTAL",2L,"ELIGIBLE",2L),1,"digest")));
        held.advanceOne().block();
        assertThat(held.get(run.id()).block().status()).isEqualTo("BLOCKED");
        assertThat(held.get(run.id()).block().message()).contains("Production sending hold");
        assertThat(properties.getProductionHoldReason()).isEqualTo("unresolved delivery diagnosis");
        assertThat(db.sql("SELECT status FROM campaigns WHERE id=:campaign").bind("campaign",campaign).map((r,m)->r.get("status",String.class)).one().block()).isEqualTo("APPROVED");
    }

    @Test void templateVersionChangedAfterCreationCannotBePrepared() {
        when(adapter.eligibleCount(anyList())).thenReturn(Mono.just(2));
        var run=service.create(actor,command("template-change-123",2),context()).block();
        UUID paper=paper("2610.00008");
        db.sql("UPDATE outreach_runs SET stage='PREPARE',paper_ids=:papers,paper_count=1,extracted_count=1 WHERE id=:run")
                .bind("papers",new UUID[]{paper}).bind("run",run.id()).fetch().rowsUpdated().block();
        db.sql("UPDATE email_templates SET current_version=2 WHERE id=:template").bind("template",template).fetch().rowsUpdated().block();
        db.sql("INSERT INTO email_template_versions(template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes) VALUES(:template,2,'Changed','Team','sender@example.org','<p>Changed</p>','Changed',30)")
                .bind("template",template).fetch().rowsUpdated().block();
        service.advanceOne().block();
        var blocked=service.get(run.id()).block();
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.message()).contains("Frozen template version");
        assertThat(count("campaigns")).isZero();
    }

    @Test @Timeout(120) void realAdapterAndRunnerReachNormalSendWorkflowWithFrozenExactMembership() {
        UUID paper=paper("2610.00009");
        UUID extraction=UUID.randomUUID();
        db.sql("INSERT INTO extraction_runs(id,paper_id,parser_version,status,started_at,completed_at) VALUES(:id,:paper,'0.1.0','SUCCEEDED',now(),now())")
                .bind("id",extraction).bind("paper",paper).fetch().rowsUpdated().block();
        db.sql("INSERT INTO contacts(email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid,example_address,suppression_status) SELECT decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),decode(lpad(to_hex(i),64,'0'),'hex'),'example.org',decode(repeat('01',16),'hex'),true,false,'ACTIVE' FROM generate_series(1,3) i")
                .fetch().rowsUpdated().block();
        db.sql("INSERT INTO paper_author_contacts(paper_id,contact_id,extraction_run_id,confidence,human_verified,verification_status) SELECT :paper,id,:extraction,'LOW',false,'UNVERIFIED' FROM contacts")
                .bind("paper",paper).bind("extraction",extraction).fetch().rowsUpdated().block();
        db.sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) SELECT id,'source.tex','source-email','masked source' FROM paper_author_contacts")
                .fetch().rowsUpdated().block();
        db.sql("UPDATE email_template_versions SET subject_template='Invitation: {{paper_title}}',html_content=:html,text_content=:text WHERE id=:version")
                .bind("version",version).bind("html","<p>Hello {{author_name}}</p><p>{{paper_title}}</p><a href=\"{{paper_url}}\" rel=\"noopener noreferrer\">Paper</a><a href=\"{{unsubscribe_url}}\" rel=\"noopener noreferrer\">Unsubscribe</a>")
                .bind("text","Hello {{first_name}} {{paper_title}} {{paper_url}} {{unsubscribe_url}}").fetch().rowsUpdated().block();
        var audit=new AuditService(db,mapper);
        var hasher=mock(SensitiveValueHasher.class);when(hasher.hash(any())).thenReturn(new byte[32]);
        var campaignRepository=new CampaignRepository(db);
        var segments=new SegmentRepository(db,mapper);
        campaigns=new CampaignService(campaignRepository,segments,new PersonalizationProperties(false,"unused","unused",120),mapper,tx);
        var delivery=new CampaignDeliveryProperties(false,10,Duration.ofMinutes(2),Duration.ofDays(180),3,Duration.ofMinutes(1),Duration.ofMinutes(5),Duration.ofSeconds(1));
        preflight=new CampaignPreflightService(new CampaignWorkflowRepository(db),delivery,
                new MailTrackingProperties(true,"https://tracking.example.org",Base64.getEncoder().encodeToString(new byte[32]),Duration.ofDays(30)),mapper);
        workflow=new CampaignWorkflowService(new CampaignWorkflowRepository(db),preflight,campaigns,audit,hasher,mapper,tx);
        byte[] hmacKey=new byte[32];java.util.Arrays.fill(hmacKey,(byte)1);
        var crypto=new ContactCrypto(new ContactDataProtectionProperties(Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString(hmacKey)));
        adapter=new OutreachTemplateCampaignService(campaignRepository,segments,campaigns,workflow,new OutreachTemplateCampaignRepository(db),
                new CampaignRecipientContentRepository(db),new OutreachTemplateContentPolicy(new TemplateEngine(102400)),
                new CampaignProductionSelectionPolicy(crypto,new CampaignSafetyProperties(false,null,20,Map.of()),Duration.ofDays(180)),tx);
        service=service(audit,hasher,new OutreachProperties());
        var run=service.create(actor,command("combined-happy-123",2),context()).block();
        service.advanceOne().block();
        UUID job=service.get(run.id()).block().importJobId();
        db.sql("INSERT INTO paper_imports(paper_id,job_id,metadata_source) VALUES(:paper,:job,'LEGACY_API')")
                .bind("paper",paper).bind("job",job).fetch().rowsUpdated().block();
        db.sql("UPDATE jobs SET status='SUCCEEDED',ended_at=now() WHERE id=:job").bind("job",job).fetch().rowsUpdated().block();
        for(String stage:List.of("EXTRACT","PREPARE","REVIEW","APPROVE","START","SENDING")) {
            assertThat(service.advanceOne().block()).isTrue();
            var progress=service.get(run.id()).block();
            assertThat(progress.stage()).as(progress.message()).isEqualTo(stage);
        }
        var sending=service.get(run.id()).block();
        var campaign=campaigns.get(sending.campaignId()).block();
        assertThat(sending.selectedCount()).isEqualTo(2);
        assertThat(sending.paperCount()).isEqualTo(1);
        assertThat(sending.status()).isEqualTo("SENDING");
        assertThat(campaign.status()).isEqualTo("RUNNING");
        assertThat(campaign.templateVersion()).isEqualTo(1);
        assertThat(campaign.recipientCounts().generated()).isEqualTo(2);
        assertThat(campaign.generationProvider()).isEqualTo("TEMPLATE");
        assertThat(count("outreach_content_reviews")).isEqualTo(2);
        assertThat(count("campaigns")).isEqualTo(1);
        assertThat(count("jobs")).isEqualTo(1); // Existing completed source is reused, no extraction job.
        assertThat(db.sql("SELECT count(*) AS n FROM paper_author_contacts WHERE human_verified OR verification_status='CONFIRMED'")
                .map((r,m)->r.get("n",Long.class)).one().block()).isZero();
        assertThat(db.sql("SELECT count(*) AS n FROM audit_logs WHERE resource_id=:id AND action LIKE 'OUTREACH_RUN_%'")
                .bind("id",run.id().toString()).map((r,m)->r.get("n",Long.class)).one().block()).isGreaterThanOrEqualTo(7);
        service.advanceOne().block();
        assertThat(service.get(run.id()).block().status()).isEqualTo("SENDING");
        assertThat(count("campaigns")).isEqualTo(1);
        assertThat(count("jobs")).isEqualTo(1);
    }

    @Test @Timeout(90) void unsafeTemplatePreservesActionableBlockedReasonAndRollsBackWholePrepare() {
        UUID paper=realPipelineWithContacts("2610.00010",1);
        db.sql("UPDATE email_template_versions SET html_content='<p>{{partner_claim_url}} {{unsubscribe_url}}</p>' WHERE id=:version")
                .bind("version",version).fetch().rowsUpdated().block();
        var run=service.create(actor,command("unsafe-partner-123",1),context()).block();
        readyForPrepare(run.id(),paper);
        service.advanceOne().block();
        var blocked=service.get(run.id()).block();
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.message()).contains("Partner activation is not supported");
        assertThat(count("campaigns")).isZero();
        assertThat(count("segments")).isZero();
        assertThat(count("outreach_content_reviews")).isZero();
    }

    @Test @Timeout(90) void terminalExtractionMarkersWithoutCompletionMetadataAreNeverFreshRetries() {
        int index=20;
        for(String status:List.of("FAILED","SECURITY_REJECTED","SOURCE_UNAVAILABLE","SUCCEEDED","PARTIALLY_SUCCEEDED")) {
            UUID paper=paper("2610.000"+(index++));
            UUID job=job("FAILED");
            db.sql("UPDATE jobs SET type='ARXIV_FETCH_AND_PARSE_SOURCE' WHERE id=:job").bind("job",job).fetch().rowsUpdated().block();
            db.sql("INSERT INTO job_items(job_id,external_key,status) VALUES(:job,:paper,'FAILED')").bind("job",job).bind("paper",paper.toString()).fetch().rowsUpdated().block();
            db.sql("INSERT INTO extraction_runs(paper_id,job_id,parser_version,status,started_at) VALUES(:paper,:job,'0.1.0',:status,now())")
                    .bind("paper",paper).bind("job",job).bind("status",status).fetch().rowsUpdated().block();
            var state=repository.extractionState(List.of(paper)).block();
            assertThat(state.pendingPaperIds()).as(status).isEmpty();
            assertThat(state.failedCount()).as(status).isEqualTo(1);
            assertThat(state.completedCount()).as(status).isZero();
        }
    }

    @Test @Timeout(120) void eligibilityChangingDuringWorkflowRollsBackApprovalAndBlocksExactRun() {
        UUID paper=realPipelineWithContacts("2610.00011",2);
        var run=service.create(actor,command("eligibility-race-123",2),context()).block();
        var campaign=adapter.prepare(actor,run.id(),template,smtp,mailbox,"invitation",List.of(paper),2).block();
        var reviewed=workflow.submitReview(campaign.id(),actor,context(),campaign.lockVersion()).block();
        db.sql("UPDATE outreach_runs SET stage='APPROVE',paper_ids=:papers,paper_count=1,extracted_count=1,campaign_id=:campaign,eligible_count=2,selected_count=2 WHERE id=:run")
                .bind("papers",new UUID[]{paper}).bind("campaign",campaign.id()).bind("run",run.id()).fetch().rowsUpdated().block();
        CampaignPreflightService real=preflight;
        preflight=mock(CampaignPreflightService.class);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(preflight.preflight(campaign.id())).thenAnswer(invocation->{
            Mono<CampaignPreflightService.PreflightView> result=real.preflight(campaign.id());
            return calls.incrementAndGet()==1?result.flatMap(snapshot->db.sql("INSERT INTO suppression_entries(email_hmac,email_domain,reason,source) SELECT email_hmac,email_domain,'MANUAL','TEST' FROM contacts ORDER BY id LIMIT 1")
                    .fetch().rowsUpdated().thenReturn(snapshot)):result;
        });
        // The normal workflow still uses its real preflight: it allows any positive eligible subset.
        service=service(mockAudit(),mockHasher(),new OutreachProperties());
        service.advanceOne().block();
        var blocked=service.get(run.id()).block();
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.message()).contains("TOTAL = ELIGIBLE");
        assertThat(campaigns.get(campaign.id()).block().status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(campaigns.get(campaign.id()).block().lockVersion()).isEqualTo(reviewed.lockVersion());
        assertThat(count("suppression_entries")).isZero();
    }

    private void readyForPrepare(UUID run,UUID paper) {
        db.sql("UPDATE outreach_runs SET stage='PREPARE',paper_ids=:papers,paper_count=1,extracted_count=1 WHERE id=:run")
                .bind("papers",new UUID[]{paper}).bind("run",run).fetch().rowsUpdated().block();
    }
    private UUID realPipelineWithContacts(String arxiv,int contactCount) {
        UUID paper=paper(arxiv),extraction=UUID.randomUUID();
        db.sql("INSERT INTO extraction_runs(id,paper_id,parser_version,status,started_at,completed_at) VALUES(:id,:paper,'0.1.0','SUCCEEDED',now(),now())")
                .bind("id",extraction).bind("paper",paper).fetch().rowsUpdated().block();
        db.sql("INSERT INTO contacts(email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid,example_address,suppression_status) SELECT decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),decode(lpad(to_hex(i),64,'0'),'hex'),'example.org',decode(repeat('01',16),'hex'),true,false,'ACTIVE' FROM generate_series(1,:count) i")
                .bind("count",contactCount).fetch().rowsUpdated().block();
        db.sql("INSERT INTO paper_author_contacts(paper_id,contact_id,extraction_run_id,confidence,human_verified,verification_status) SELECT :paper,id,:extraction,'LOW',false,'UNVERIFIED' FROM contacts")
                .bind("paper",paper).bind("extraction",extraction).fetch().rowsUpdated().block();
        db.sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) SELECT id,'source.tex','source-email','masked source' FROM paper_author_contacts")
                .fetch().rowsUpdated().block();
        var audit=new AuditService(db,mapper);
        var hasher=mock(SensitiveValueHasher.class);when(hasher.hash(any())).thenReturn(new byte[32]);
        var campaignRepository=new CampaignRepository(db);var segments=new SegmentRepository(db,mapper);
        campaigns=new CampaignService(campaignRepository,segments,new PersonalizationProperties(false,"unused","unused",120),mapper,tx);
        var delivery=new CampaignDeliveryProperties(false,10,Duration.ofMinutes(2),Duration.ofDays(180),3,Duration.ofMinutes(1),Duration.ofMinutes(5),Duration.ofSeconds(1));
        preflight=new CampaignPreflightService(new CampaignWorkflowRepository(db),delivery,new MailTrackingProperties(true,"https://tracking.example.org",Base64.getEncoder().encodeToString(new byte[32]),Duration.ofDays(30)),mapper);
        workflow=new CampaignWorkflowService(new CampaignWorkflowRepository(db),preflight,campaigns,audit,hasher,mapper,tx);
        byte[] hmacKey=new byte[32];java.util.Arrays.fill(hmacKey,(byte)1);
        var crypto=new ContactCrypto(new ContactDataProtectionProperties(Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString(hmacKey)));
        adapter=new OutreachTemplateCampaignService(campaignRepository,segments,campaigns,workflow,new OutreachTemplateCampaignRepository(db),new CampaignRecipientContentRepository(db),new OutreachTemplateContentPolicy(new TemplateEngine(102400)),new CampaignProductionSelectionPolicy(crypto,new CampaignSafetyProperties(false,null,20,Map.of()),Duration.ofDays(180)),tx);
        service=service(audit,hasher,new OutreachProperties());return paper;
    }

    private OutreachRunService service(AuditService audit, SensitiveValueHasher hasher, OutreachProperties properties) {
        return new OutreachRunService(repository, imports,
                new SourceExtractionService(new SourceExtractionRepository(db),audit,hasher,mapper,"0.1.0",tx),
                adapter,campaigns,preflight,workflow,audit,hasher,mapper,properties,tx);
    }
    private AuditService mockAudit() { var result=mock(AuditService.class); when(result.record(any())).thenReturn(Mono.empty()); return result; }
    private SensitiveValueHasher mockHasher() { var result=mock(SensitiveValueHasher.class); when(result.hash(any())).thenReturn(new byte[]{1}); return result; }
    private OutreachDtos.CreateRequest command(String key,int count) { return new OutreachDtos.CreateRequest("test", "EMAILS", count, smtp,template,"invitation", criteria(),"AUTO_OUTREACH_SEND",key); }
    private ArxivSearchCriteria criteria() { return new ArxivSearchCriteria(List.of("cs.AI"),ArxivSearchCriteria.CategoryMode.ANY,null,null,null,null,"AI",null,null,null,null,null,ArxivSearchCriteria.SortBy.SUBMITTED_DATE,ArxivSearchCriteria.SortOrder.DESCENDING,1,100); }
    private AuthenticationRequestContext context() { return new AuthenticationRequestContext("127.0.0.1","test","outreach-test"); }
    private long count(String table) { return db.sql("SELECT count(*) AS n FROM "+table).map((r,m)->r.get("n",Long.class)).one().block(); }
    private UUID job(String status) { UUID id=UUID.randomUUID(); db.sql("INSERT INTO jobs(id,type,status,created_by,parameters,idempotency_key,ended_at) VALUES (:id,'ARXIV_IMPORT_METADATA',:status,:actor,'{}',:key,CASE WHEN :status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELED') THEN now() ELSE NULL END)")
            .bind("id",id).bind("status",status).bind("actor",actor).bind("key",id.toString()).fetch().rowsUpdated().block(); return id; }
    private UUID paper(String arxiv) { UUID id=UUID.randomUUID(); db.sql("INSERT INTO papers(id,arxiv_id,title,abstract_text,submitted_at,updated_at,pdf_url) VALUES(:id,:arxiv,'title','abstract',now(),now(),'https://arxiv.org/pdf/'||:arxiv)")
            .bind("id",id).bind("arxiv",arxiv).fetch().rowsUpdated().block(); return id; }
    private void extraction(UUID paper,String status) { db.sql("INSERT INTO extraction_runs(paper_id,parser_version,status,started_at,completed_at) VALUES(:paper,'0.1.0',:status,now(),now())")
            .bind("paper",paper).bind("status",status).fetch().rowsUpdated().block(); }
    private UUID campaign(String status,UUID templateVersion) {
        UUID id=UUID.randomUUID();db.sql("INSERT INTO campaigns(id,name,purpose,status,template_id,template_version_id,smtp_account_id,mailbox_account_id,from_name,from_email,reply_to,created_by) VALUES(:id,'outreach','invitation',:status,:template,:version,:smtp,:mailbox,'Team','sender@example.org','sender@example.org',:actor)")
                .bind("id",id).bind("status",status).bind("template",template).bind("version",templateVersion).bind("smtp",smtp).bind("mailbox",mailbox).bind("actor",actor).fetch().rowsUpdated().block();return id;
    }
    private void seed() {
        db.sql("INSERT INTO users(id,username,email,password_hash,display_name,force_password_change) VALUES (:actor,'outreach-user','outreach@example.org','hash','Outreach',false)").bind("actor",actor).fetch().rowsUpdated().block();
        db.sql("INSERT INTO user_roles(user_id,role_id) SELECT :actor,id FROM roles WHERE code='SUPER_ADMIN'").bind("actor",actor).fetch().rowsUpdated().block();
        db.sql("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,username,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,last_test_status,last_tested_at,updated_at) VALUES(:smtp,'sender','smtp.example.org',465,'TLS_IMPLICIT','sender@example.org','sender@example.org','Team','sender@example.org',1,10,100,10,true,'SUCCEEDED',now(),now())")
                .bind("smtp",smtp).fetch().rowsUpdated().block();
        db.sql("INSERT INTO mailbox_accounts(id,name,protocol,host,port,tls_mode,username,password_ciphertext,password_nonce,enabled,last_test_status,last_tested_at,updated_at) VALUES(:mailbox,'replies','IMAP','imap.example.org',993,'TLS_IMPLICIT','sender@example.org',decode(repeat('01',16),'hex'),decode(repeat('01',12),'hex'),true,'SUCCEEDED',now(),now())")
                .bind("mailbox",mailbox).fetch().rowsUpdated().block();
        db.sql("INSERT INTO email_templates(id,name,status) VALUES(:template,'invitation','ACTIVE')").bind("template",template).fetch().rowsUpdated().block();
        db.sql("INSERT INTO email_template_versions(id,template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes) VALUES(:version,:template,1,'Hello','Team','sender@example.org','<p>Hello {{unsubscribe_url}}</p>','Hello {{unsubscribe_url}}',80)")
                .bind("version",version).bind("template",template).fetch().rowsUpdated().block();
    }
}
