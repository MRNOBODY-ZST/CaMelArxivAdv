package com.camel_hub.advertisement.outreach.bulk;

import com.camel_hub.advertisement.arxiv.extraction.*;
import com.camel_hub.advertisement.arxiv.importing.*;
import com.camel_hub.advertisement.arxiv.search.*;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.campaign.delivery.*;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.outreach.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.*;
import io.r2dbc.pool.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Real DB and campaign pipeline; only unavailable external transports are absent.
 * Breaks caught: idempotency mismatch, duplicated jobs, terminal extraction replay,
 * capped parent selection, duplicate reservations, risk bypass and unknown replay. */
@Timeout(60)
class BulkOutreachIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("camel_bulk_test").withUsername("camel").withPassword("test-only");
    private UUID actor, smtp, mailbox, template, version;
    private DatabaseClient db;
    private ConnectionPool pool;
    private TransactionalOperator tx;
    private ObjectMapper mapper;
    private BulkOutreachRepository repository;
    private BulkOutreachService service;
    private OutreachTemplateCampaignService adapter;
    private CampaignService campaigns;
    private CampaignWorkflowService workflow;
    private CampaignPreflightService preflight;
    private AuditService audit;
    private SensitiveValueHasher hasher;
    private ArxivImportService imports;
    private SourceExtractionService extraction;
    private OutreachProperties properties;
    private static boolean migrated;

    @BeforeEach void setup() {
        String jdbc=System.getenv("OUTREACH_TEST_JDBC_URL");
        String user=System.getenv().getOrDefault("OUTREACH_TEST_DB_USERNAME","outreach_test");
        String password=System.getenv().getOrDefault("OUTREACH_TEST_DB_PASSWORD","outreach_test_only");
        if(jdbc==null) { if(!POSTGRES.isRunning()) POSTGRES.start();jdbc=POSTGRES.getJdbcUrl();user=POSTGRES.getUsername();password=POSTGRES.getPassword(); }
        if(!migrated) {
            Flyway.configure().dataSource(jdbc+(jdbc.contains("?")?"&":"?")+"connectTimeout=5&socketTimeout=20",user,password).locations("classpath:db/migration").load().migrate();
            migrated=true;
        }
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.parse(jdbc.replace("jdbc:","r2dbc:"))
                .mutate().option(ConnectionFactoryOptions.USER,user).option(ConnectionFactoryOptions.PASSWORD,password).build());
        pool=new ConnectionPool(ConnectionPoolConfiguration.builder(factory).initialSize(1).maxSize(5).build());
        db=DatabaseClient.create(pool);tx=TransactionalOperator.create(new R2dbcTransactionManager(pool));mapper=new ObjectMapper().findAndRegisterModules();
        db.sql("TRUNCATE users,smtp_accounts,mailbox_accounts,email_templates,papers,jobs,contacts,segments,suppression_entries,recipient_delivery_cooldowns,outbox_messages CASCADE").fetch().rowsUpdated().block();
        actor=UUID.randomUUID();smtp=UUID.randomUUID();mailbox=UUID.randomUUID();template=UUID.randomUUID();version=UUID.randomUUID();seed();
        audit=new AuditService(db,mapper);hasher=mock(SensitiveValueHasher.class);when(hasher.hash(any())).thenReturn(new byte[32]);
        imports=new ArxivImportService(new ArxivImportRepository(db),new ArxivQueryNormalizer(mapper,100),()->Mono.just(Set.of("cs.AI")),audit,hasher,mapper,10000,tx);
        extraction=new SourceExtractionService(new SourceExtractionRepository(db),audit,hasher,mapper,"0.1.0",tx);
        var cr=new CampaignRepository(db);var segments=new SegmentRepository(db,mapper);
        campaigns=new CampaignService(cr,segments,new PersonalizationProperties(false,"unused","unused",120),mapper,tx);
        var delivery=new CampaignDeliveryProperties(false,10,Duration.ofMinutes(2),Duration.ofDays(180),3,Duration.ofMinutes(1),Duration.ofMinutes(5),Duration.ofSeconds(1));
        preflight=new CampaignPreflightService(new CampaignWorkflowRepository(db),delivery,new MailTrackingProperties(true,"https://tracking.example.org",Base64.getEncoder().encodeToString(new byte[32]),Duration.ofDays(30)),mapper);
        workflow=new CampaignWorkflowService(new CampaignWorkflowRepository(db),preflight,campaigns,audit,hasher,mapper,tx);
        byte[] hmac=new byte[32];Arrays.fill(hmac,(byte)1);
        var crypto=new ContactCrypto(new ContactDataProtectionProperties(Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString(hmac)));
        adapter=new OutreachTemplateCampaignService(cr,segments,campaigns,workflow,new OutreachTemplateCampaignRepository(db),new CampaignRecipientContentRepository(db),new OutreachTemplateContentPolicy(new TemplateEngine(102400)),new CampaignProductionSelectionPolicy(crypto,new CampaignSafetyProperties(false,null,20,Map.of()),Duration.ofDays(180)),tx);
        repository=new BulkOutreachRepository(db,mapper);properties=new OutreachProperties();service=service();
    }
    @AfterEach void close() { if(pool!=null) pool.dispose(); }

    @Test void identicalPayloadReusesParentWhileChangedTargetRejects() {
        var first=service.create(actor,command("same-key-123",100,false,false),context()).block();
        assertThat(service.create(actor,command("same-key-123",100,false,false),context()).block().id()).isEqualTo(first.id());
        assertThatThrownBy(()->service.create(actor,command("same-key-123",101,false,false),context()).block()).isInstanceOf(OutreachConflictException.class);
        assertThat(count("bulk_outreach_runs")).isEqualTo(1);
    }
    @Test void legacyRunReadDoesNotInventAvailableZeroInventory() {
        var run=service.create(actor,command("inventory-legacy",100,false,false),context()).block();
        var json=mapper.valueToTree(run);
        assertThat(json.has("selectionCounts")).isTrue();
        assertThat(json.get("selectionCounts").isNull()).isTrue();
        assertThat(run.selectedCount()).isZero();
    }
    @Test void migratedStandaloneTargetsDoNotBecomeBulkChildrenOrRewriteReservations() {
        byte[] hmac=new byte[32];Arrays.fill(hmac,(byte)1);
        var inventory=new CampaignSelectionInventory(new CampaignProductionSelectionPolicy(
            new ContactCrypto(new ContactDataProtectionProperties(Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString(hmac))),
            new CampaignSafetyProperties(false,null,20,Map.of()),Duration.ofDays(180)));
        repository=new BulkOutreachRepository(db,mapper,inventory);service=service();
        var run=service.create(actor,command("inventory-owned-123",1,false,false),context()).block();
        assertThat(run.selectionCounts()).isEqualTo(new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts(0,0,0,0));
        UUID paper=paper(1,"SUCCEEDED",3);freeze(run.id(),List.of(paper),"PREPARE");
        service.advanceOne().block();service.advanceOne().block();
        UUID source=service.get(run.id()).block().children().getFirst().id();
        db.sql("UPDATE bulk_outreach_runs SET status='BLOCKED' WHERE id=:id").bind("id",run.id()).fetch().rowsUpdated().block();
        var ids=db.sql("SELECT id FROM campaign_recipients WHERE campaign_id=:id ORDER BY id LIMIT 2").bind("id",source).map((r,m)->r.get("id",UUID.class)).all().collectList().block();
        UUID schedule=UUID.randomUUID();
        db.sql("INSERT INTO outreach_schedules(id,actor_id,idempotency_key,payload_hash,name,purpose,max_papers,smtp_account_id,template_id,directions,request_context,config_snapshot,next_run_at) VALUES(:id,:actor,'inventory-weekly',decode(repeat('01',32),'hex'),'Synthetic weekly','Synthetic',3,:smtp,:template,'[\"Synthetic\"]','{}','{}','2030-01-01T00:00:00Z')")
            .bind("id",schedule).bind("actor",actor).bind("smtp",smtp).bind("template",template).fetch().rowsUpdated().block();
        db.sql("INSERT INTO outreach_schedule_occurrences(schedule_id,scheduled_for,occurrence_key,status,window_from,window_to,lease_token,lease_until,parent_run_id) VALUES(:schedule,'2026-10-12T00:00:00Z','inventory-occurrence','BLOCKED','2026-10-05','2026-10-12',gen_random_uuid(),'2026-10-12T01:00:00Z',:parent)")
            .bind("schedule",schedule).bind("parent",run.id()).fetch().rowsUpdated().block();
        String before=db.sql("SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(r)) FROM bulk_outreach_reservations r),(SELECT jsonb_agg(to_jsonb(c)) FROM bulk_outreach_children c),(SELECT jsonb_agg(to_jsonb(p)) FROM bulk_outreach_runs p),(SELECT jsonb_agg(to_jsonb(s)) FROM outreach_schedules s),(SELECT jsonb_agg(to_jsonb(o)) FROM outreach_schedule_occurrences o))::text AS snapshot").map((r,m)->r.get("snapshot",String.class)).one().block();
        var migration=new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationService(
            new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationRepository(db,Duration.ofDays(180),Set.of()),new CampaignRepository(db),
            new OutreachTemplateCampaignRepository(db),new OutreachTemplateContentPolicy(new TemplateEngine(102400)),audit,tx,true);
        var preview=migration.preview(source,actor,new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.PreviewCommand(template,version,ids)).block();
        assertThat(preview.ready()).isTrue();
        var result=migration.migrate(source,actor,context(),new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.MigrationCommand(
            preview.expectedLockVersion(),template,version,preview.expectedTemplateLockVersion(),ids,preview.expectedMembershipDigest(),"inventory-exact")).block();
        var live=service.get(run.id()).block();
        assertThat(live.selectionCounts()).isEqualTo(new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts(3,1,2,0));
        assertThat(live.selectedCount()).isEqualTo(3);
        assertThat(live.children()).singleElement().satisfies(child->assertThat(child.id()).isEqualTo(source));
        assertThat(live.migrationReferences()).singleElement().satisfies(ref->assertThat(ref.targetCampaignId()).isEqualTo(result.targetCampaignId()));
        assertThat(new CampaignReportingRepository(db,inventory).campaigns(result.targetCampaignId(),0,20).blockFirst().selectionCounts())
            .isEqualTo(new com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts(2,2,0,0));
        String after=db.sql("SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(r)) FROM bulk_outreach_reservations r),(SELECT jsonb_agg(to_jsonb(c)) FROM bulk_outreach_children c),(SELECT jsonb_agg(to_jsonb(p)) FROM bulk_outreach_runs p),(SELECT jsonb_agg(to_jsonb(s)) FROM outreach_schedules s),(SELECT jsonb_agg(to_jsonb(o)) FROM outreach_schedule_occurrences o))::text AS snapshot").map((r,m)->r.get("snapshot",String.class)).one().block();
        assertThat(after).isEqualTo(before);
        assertThat(count("outbox_messages")).isZero();
    }
    @Test void actorRevocationRejectsEvenAnExistingIdempotencyKey() {
        service.create(actor,command("revoke-key-123",100,false,false),context()).block();
        db.sql("DELETE FROM user_roles WHERE user_id=:actor").bind("actor",actor).fetch().rowsUpdated().block();
        assertThatThrownBy(()->service.create(actor,command("revoke-key-123",100,false,false),context()).block()).isInstanceOf(OutreachValidationException.class);
        assertThat(count("bulk_outreach_runs")).isEqualTo(1);
    }
    @Test @Timeout(120) void initialDirectionsReceiveFairBudgetsAndUseRealCriteriaNotPreviewPages() {
        var base=command("shards-key-123",10,false,false);var criteria=base.searchPlan().getFirst();
        List<ArxivSearchCriteria> directions=new ArrayList<>();for(String phrase:List.of("Agent","Financial AI","multi agent")) directions.add(new ArxivSearchCriteria(criteria.categoryIds(),criteria.categoryMode(),null,null,null,null,null,phrase,null,null,null,null,null,null,37,1));
        var run=service.create(actor,new BulkOutreachCommand(base.name(),base.purpose(),10,smtp,template,directions,false,false,base.confirmation(),base.idempotencyKey()),context()).block();
        for(int i=0;i<3;i++) {service.advanceOne().block();db.sql("UPDATE jobs SET status='SUCCEEDED',ended_at=now()").fetch().rowsUpdated().block();service.advanceOne().block();}
        assertThat(db.sql("SELECT budget FROM bulk_outreach_jobs ORDER BY ordinal").map((r,m)->r.get("budget",Integer.class)).all().collectList().block()).containsExactly(4,3,3);
        assertThat(service.get(run.id()).block().message()).contains("shortfall");assertThat(count("jobs")).isEqualTo(3);
        assertThat(db.sql("SELECT count(*) AS n FROM bulk_outreach_jobs WHERE criteria->>'page'='1'").map((r,m)->r.get("n",Long.class)).one().block()).isEqualTo(3);
    }
    @Test @Timeout(120) void residualSearchIncludesOldestDayRatherThanSkippingItsUnseenPapers() {
        var prior=service.create(actor,command("boundary-old-123",2,false,false),context()).block();
        UUID one=paper(1,"SUCCEEDED",0),two=paper(2,"SUCCEEDED",0);freeze(prior.id(),List.of(one,two),"EXTRACT");service.cancel(prior.id(),actor,service.get(prior.id()).block().lockVersion(),context()).block();
        var next=service.create(actor,command("boundary-new-123",2,true,false),context()).block();service.advanceOne().block();
        UUID job=db.sql("SELECT job_id FROM bulk_outreach_jobs WHERE run_id=:id").bind("id",next.id()).map((r,m)->r.get("job_id",UUID.class)).one().block();
        db.sql("INSERT INTO paper_imports(paper_id,job_id,metadata_source) SELECT id,:job,'LEGACY_API' FROM papers").bind("job",job).fetch().rowsUpdated().block();
        db.sql("UPDATE jobs SET status='SUCCEEDED',ended_at=now() WHERE id=:job").bind("job",job).fetch().rowsUpdated().block();service.advanceOne().block();service.advanceOne().block();
        assertThat(db.sql("SELECT criteria->>'submittedTo' AS bound FROM bulk_outreach_jobs WHERE run_id=:id AND ordinal=1").bind("id",next.id()).map((r,m)->r.get("bound",String.class)).one().block()).isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        assertThat(db.sql("SELECT j.parameters->'criteria'->>'submittedTo' AS bound FROM bulk_outreach_jobs bj JOIN jobs j ON j.id=bj.job_id WHERE bj.run_id=:id AND bj.ordinal=1").bind("id",next.id()).map((r,m)->r.get("bound",String.class)).one().block()).isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        UUID residual=db.sql("SELECT job_id FROM bulk_outreach_jobs WHERE run_id=:id AND ordinal=1").bind("id",next.id()).map((r,m)->r.get("job_id",UUID.class)).one().block();
        db.sql("INSERT INTO paper_imports(paper_id,job_id,metadata_source) SELECT id,:job,'LEGACY_API' FROM papers").bind("job",residual).fetch().rowsUpdated().block();
        db.sql("UPDATE jobs SET status='SUCCEEDED',ended_at=now() WHERE id=:job").bind("job",residual).fetch().rowsUpdated().block();service.advanceOne().block();
        assertThat(service.get(next.id()).block().message()).contains("shortfall");assertThat(service.advanceOne().block()).isFalse();assertThat(count("jobs")).isEqualTo(2);
    }
    @Test void importOldestUsesArxivUtcDayAcrossDatabaseSessionTimezones() {
        var run=service.create(actor,command("utc-oldest-day-123",1,false,false),context()).block();
        service.advanceOne().block();
        UUID job=db.sql("SELECT job_id FROM bulk_outreach_jobs WHERE run_id=:id").bind("id",run.id()).map((r,m)->r.get("job_id",UUID.class)).one().block();
        UUID paper=paper(900,"SUCCEEDED",0);
        db.sql("UPDATE papers SET submitted_at='2026-10-12T16:33:00Z'::timestamptz WHERE id=:id").bind("id",paper).fetch().rowsUpdated().block();
        db.sql("INSERT INTO paper_imports(paper_id,job_id,metadata_source) VALUES(:paper,:job,'LEGACY_API')").bind("paper",paper).bind("job",job).fetch().rowsUpdated().block();
        var days=new ArrayList<java.time.LocalDate>();
        // SET LOCAL affects only this test-owned transaction, never the pooled/global configuration.
        for(String zone:List.of("UTC","Asia/Shanghai","America/Los_Angeles"))
            days.add(tx.transactional(db.sql("SET LOCAL TIME ZONE '"+zone+"'").fetch().rowsUpdated().then(repository.importOldest(job))).block());
        assertThat(days).containsExactly(java.time.LocalDate.of(2026,10,12),java.time.LocalDate.of(2026,10,12),java.time.LocalDate.of(2026,10,12));
    }
    @Test void actualWorkerImportPayloadCarriesIsoDateBoundsNotIgnoredDateArrays() {
        var base=command("worker-dates-123",1,false,false);var c=base.searchPlan().getFirst();
        var criteria=new ArxivSearchCriteria(c.categoryIds(),c.categoryMode(),java.time.LocalDate.of(2026,10,1),java.time.LocalDate.of(2026,10,11),null,null,null,"Agent",null,null,null,null,null,null,1,100);
        service.create(actor,new BulkOutreachCommand(base.name(),base.purpose(),1,smtp,template,List.of(criteria),false,false,base.confirmation(),base.idempotencyKey()),context()).block();service.advanceOne().block();
        assertThat(db.sql("SELECT parameters->'criteria'->>'submittedFrom' AS date FROM jobs").map((r,m)->r.get("date",String.class)).one().block()).isEqualTo("2026-10-01");
        assertThat(db.sql("SELECT parameters->'criteria'->>'submittedTo' AS date FROM jobs").map((r,m)->r.get("date",String.class)).one().block()).isEqualTo("2026-10-11");
    }
    @Test @Timeout(180) void concurrentParentsReserveTheSameHmacOnlyOnce() {
        UUID paper=paper(1,"SUCCEEDED",1);
        var first=service.create(actor,command("concurrent-a-123",1,false,true),context()).block();
        var second=service.create(actor,command("concurrent-b-123",1,false,true),context()).block();
        freeze(first.id(),List.of(paper),"PREPARE");freeze(second.id(),List.of(paper),"PREPARE");
        reactor.core.publisher.Flux.merge(service.advanceOne(),service.advanceOne()).collectList().block();
        assertThat(count("bulk_outreach_reservations")).isEqualTo(1);assertThat(count("bulk_outreach_children")).isEqualTo(1);assertThat(count("campaigns")).isEqualTo(1);
    }
    @Test void importJobPersistsAndRestartWaitsWithoutAnotherSubmission() {
        var run=service.create(actor,command("restart-key-123",100,false,false),context()).block();
        service.advanceOne().block();
        assertThat(count("bulk_outreach_jobs")).isEqualTo(1);
        service=service();service.advanceOne().block();
        assertThat(service.get(run.id()).block().stage()).isEqualTo("WAIT_IMPORT");
        assertThat(count("jobs")).isEqualTo(1);assertThat(count("outbox_messages")).isEqualTo(1);
    }
    @Test @Timeout(120) void existingActiveSourceJobIsRecordedWaitedAndNeverRecreatedAfterFailure() {
        UUID paper=paper(1,"SUCCEEDED",0);db.sql("DELETE FROM extraction_runs WHERE paper_id=:id").bind("id",paper).fetch().rowsUpdated().block();
        UUID sourceJob=extraction.create(actor,List.of(paper),context()).block().jobId();
        var run=service.create(actor,command("source-reuse-123",1,false,false),context()).block();freeze(run.id(),List.of(paper),"EXTRACT");service.advanceOne().block();
        assertThat(service.get(run.id()).block().stage()).isEqualTo("WAIT_EXTRACTION");
        assertThat(db.sql("SELECT extraction_job_id FROM bulk_outreach_papers WHERE run_id=:id").bind("id",run.id()).map((r,m)->Optional.ofNullable(r.get("extraction_job_id",UUID.class))).one().block()).contains(sourceJob);
        assertThat(db.sql("SELECT job_id FROM bulk_outreach_jobs WHERE run_id=:id AND kind='EXTRACTION'").bind("id",run.id()).map((r,m)->r.get("job_id",UUID.class)).one().block()).isEqualTo(sourceJob);
        db.sql("UPDATE jobs SET status='FAILED',ended_at=now() WHERE id=:id").bind("id",sourceJob).fetch().rowsUpdated().block();
        db.sql("UPDATE bulk_outreach_runs SET next_step_at=now() WHERE id=:id").bind("id",run.id()).fetch().rowsUpdated().block();service=service();service.advanceOne().block();
        var result=service.get(run.id()).block();assertThat(result.excludedCount()).isEqualTo(1);assertThat(result.noContactCount()).isZero();assertThat(count("jobs")).isEqualTo(1);assertThat(count("outbox_messages")).isEqualTo(1);
    }
    @Test @Timeout(180) void concurrentSourceCreatorIsObservedWithoutBlockingParent() throws Exception {
        var race=sourceCreatorRace(false);var recovered=service.get(race.runId()).block();assertThat(recovered.status()).isEqualTo("ACTIVE");assertThat(recovered.stage()).isEqualTo("WAIT_EXTRACTION");
        assertThat(db.sql("SELECT next_step_at>=updated_at+interval '3 seconds' AS delayed FROM bulk_outreach_runs WHERE id=:id").bind("id",race.runId()).map((r,m)->r.get("delayed",Boolean.class)).one().block()).isTrue();
        assertThat(count("jobs")).isEqualTo(1);assertThat(count("job_items")).isEqualTo(1);assertThat(count("outbox_messages")).isEqualTo(1);
        db.sql("UPDATE bulk_outreach_runs SET next_step_at=now() WHERE id=:id").bind("id",race.runId()).fetch().rowsUpdated().block();service=service();service.advanceOne().block();
        assertThat(service.get(race.runId()).block().stage()).isEqualTo("WAIT_EXTRACTION");
        assertThat(db.sql("SELECT job_id FROM bulk_outreach_jobs WHERE run_id=:id AND kind='EXTRACTION'").bind("id",race.runId()).map((r,m)->r.get("job_id",UUID.class)).one().block()).isEqualTo(race.jobId());
        db.sql("UPDATE jobs SET status='FAILED',ended_at=now() WHERE id=:id").bind("id",race.jobId()).fetch().rowsUpdated().block();
        db.sql("UPDATE bulk_outreach_runs SET next_step_at=now() WHERE id=:id").bind("id",race.runId()).fetch().rowsUpdated().block();service.advanceOne().block();
        assertThat(service.get(race.runId()).block().excludedCount()).isEqualTo(1);assertThat(count("jobs")).isEqualTo(1);assertThat(count("outbox_messages")).isEqualTo(1);assertThat(count("campaigns")).isZero();assertThat(count("bulk_outreach_reservations")).isZero();
    }
    @Test @Timeout(180) void sourceCreatorThatFailsBeforeBulkClaimsPaperIsExcludedWithoutReplay() throws Exception {
        var race=sourceCreatorRace(true);
        assertThat(count("jobs")).isEqualTo(1);assertThat(count("job_items")).isEqualTo(1);assertThat(count("outbox_messages")).isEqualTo(1);
        var observed=service.get(race.runId()).block();assertThat(observed.status()).isEqualTo("ACTIVE");assertThat(observed.excludedCount()).isEqualTo(1);
        service=service();service.advanceOne().block();assertThat(service.get(race.runId()).block().stage()).isEqualTo("PREPARE");
        assertThat(db.sql("SELECT job_id FROM bulk_outreach_jobs WHERE run_id=:id AND kind='EXTRACTION'").bind("id",race.runId()).map((r,m)->r.get("job_id",UUID.class)).one().block()).isEqualTo(race.jobId());
        assertThat(count("jobs")).isEqualTo(1);assertThat(count("outbox_messages")).isEqualTo(1);assertThat(count("campaigns")).isZero();assertThat(count("bulk_outreach_reservations")).isZero();
    }
    @Test @Timeout(180) void terminalRaceExcludesWinnerAndSubmitsOnlyUnaffectedPendingPaper() throws Exception {
        var race=sourceCreatorRace(true,true);
        var observed=service.get(race.runId()).block();assertThat(observed.status()).isEqualTo("ACTIVE");assertThat(observed.stage()).isEqualTo("WAIT_EXTRACTION");assertThat(observed.excludedCount()).isEqualTo(1);
        assertThat(count("jobs")).isEqualTo(2);assertThat(count("job_items")).isEqualTo(2);assertThat(count("outbox_messages")).isEqualTo(2);
        assertThat(count("bulk_outreach_jobs")).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) AS n FROM job_items WHERE job_id<>:winner AND external_key IN(SELECT paper_id::text FROM bulk_outreach_papers WHERE outcome<>'ACTIVE')").bind("winner",race.jobId()).map((r,m)->r.get("n",Long.class)).one().block()).isZero();
        service=service();service.advanceOne().block();assertThat(count("jobs")).isEqualTo(2);assertThat(count("outbox_messages")).isEqualTo(2);assertThat(count("campaigns")).isZero();assertThat(count("bulk_outreach_reservations")).isZero();
    }
    @Test @Timeout(180) void unsafeAndUnavailablePapersAreExcludedWithoutBlocking98SafePapers() {
        var run=service.create(actor,command("isolated-key-123",100,false,false),context()).block();
        List<UUID> papers=new ArrayList<>();for(int i=1;i<=100;i++) papers.add(paper(i,i<=2?i==1?"SECURITY_REJECTED":"SOURCE_UNAVAILABLE":"SUCCEEDED",0));
        freeze(run.id(),papers,"EXTRACT");service.advanceOne().block();
        var view=service.get(run.id()).block();
        assertThat(view.status()).isEqualTo("ACTIVE");assertThat(view.stage()).isEqualTo("PREPARE");
        assertThat(view.excludedCount()).isEqualTo(2);assertThat(view.extractedCount()).isEqualTo(98);
        assertThat(count("jobs")).isZero();
    }
    @Test @Timeout(600) void all250ContactsFreezeInto12012010WithoutHmacOverlap() {
        var run=service.create(actor,command("large-key-123",1,false,true),context()).block();
        UUID paper=paper(1,"SUCCEEDED",250);freeze(run.id(),List.of(paper),"PREPARE");
        for(int i=0;i<4;i++) service.advanceOne().block();
        var view=service.get(run.id()).block();
        assertThat(view.selectedCount()).isEqualTo(250);
        assertThat(view.children()).extracting(BulkOutreachView.Child::selectedCount).containsExactlyInAnyOrder(120,120,10);
        assertThat(count("bulk_outreach_reservations")).isEqualTo(250);
        assertThat(db.sql("SELECT count(DISTINCT email_hmac) AS n FROM campaign_recipients").map((r,m)->r.get("n",Long.class)).one().block()).isEqualTo(250);
        assertThat(db.sql("SELECT max(cardinality(paper_contact_ids)) AS n FROM campaigns").map((r,m)->r.get("n",Integer.class)).one().block()).isLessThanOrEqualTo(100);
    }
    @Test void incrementalManifestExcludesPapersFrozenByEarlierParent() {
        var first=service.create(actor,command("prior-key-123",1,false,false),context()).block();UUID paper=paper(1,"SUCCEEDED",0);freeze(first.id(),List.of(paper),"EXTRACT");
        service.cancel(first.id(),actor,service.get(first.id()).block().lockVersion(),context()).block();
        var next=service.create(actor,command("new-key-123",1,true,false),context()).block();service.advanceOne().block();
        UUID job=db.sql("SELECT job_id FROM bulk_outreach_jobs WHERE run_id=:id").bind("id",next.id()).map((r,m)->r.get("job_id",UUID.class)).one().block();
        db.sql("INSERT INTO paper_imports(paper_id,job_id,metadata_source) VALUES(:paper,:job,'LEGACY_API')").bind("paper",paper).bind("job",job).fetch().rowsUpdated().block();
        db.sql("UPDATE jobs SET status='SUCCEEDED',ended_at=now() WHERE id=:job").bind("job",job).fetch().rowsUpdated().block();
        service.advanceOne().block();assertThat(service.get(next.id()).block().paperCount()).isZero();
    }
    @Test void deliverabilityRiskAcceptanceDoesNotBypassConfigurationDrift() {
        var run=service.create(actor,command("drift-key-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",1);freeze(run.id(),List.of(paper),"PREPARE");
        db.sql("UPDATE smtp_accounts SET lock_version=lock_version+1 WHERE id=:id").bind("id",smtp).fetch().rowsUpdated().block();
        service.advanceOne().block();assertThat(service.get(run.id()).block().status()).isEqualTo("BLOCKED");assertThat(count("campaigns")).isZero();
    }
    @Test void emptyMetadataRecordsShortfallAndCannotPretendTargetWasReached() {
        var run=service.create(actor,command("short-key-123",100,false,false),context()).block();service.advanceOne().block();
        db.sql("UPDATE jobs SET status='SUCCEEDED',ended_at=now()").fetch().rowsUpdated().block();service.advanceOne().block();
        assertThat(service.get(run.id()).block().message()).contains("shortfall");
        assertThat(service.get(run.id()).block().paperCount()).isZero();
    }
    @Test void successMarkerWithoutCompletedEvidenceIsNotSafeOrRetried() {
        var run=service.create(actor,command("incomplete-key-123",1,false,false),context()).block();UUID paper=paper(1,"SUCCEEDED",0);
        db.sql("UPDATE extraction_runs SET completed_at=NULL WHERE paper_id=:paper").bind("paper",paper).fetch().rowsUpdated().block();
        freeze(run.id(),List.of(paper),"EXTRACT");service.advanceOne().block();
        var result=service.get(run.id()).block();assertThat(result.excludedCount()).isEqualTo(1);assertThat(result.extractedCount()).isZero();assertThat(count("jobs")).isZero();
    }
    @Test @Timeout(120) void unknownSmtpOutcomeSurvivesRestartAndNeverProducesAnotherCampaignOrJob() {
        var run=service.create(actor,command("unknown-key-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",1);freeze(run.id(),List.of(paper),"PREPARE");
        service.advanceOne().block();service.advanceOne().block();UUID campaign=service.get(run.id()).block().children().getFirst().id();
        db.sql("UPDATE campaign_recipients SET status='OUTCOME_UNKNOWN',attempt_count=1 WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE campaigns SET status='COMPLETED' WHERE id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE bulk_outreach_children SET status='SENDING',stage='SENDING' WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        service=service();service.advanceOne().block();service.advanceOne().block();
        var result=service.get(run.id()).block();assertThat(result.unknownCount()).isEqualTo(1);assertThat(result.status()).isEqualTo("FAILED");assertThat(count("campaigns")).isEqualTo(1);assertThat(count("jobs")).isZero();
        assertThat(db.sql("SELECT outcome FROM bulk_outreach_reservations").map((r,m)->r.get("outcome",String.class)).one().block()).isEqualTo("OUTCOME_UNKNOWN");
    }
    @Test @Timeout(120) void riskAcceptanceCannotStartPartialPreflightAndBlockedChildRemainsInspectable() {
        var run=service.create(actor,command("preflight-key-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",2);freeze(run.id(),List.of(paper),"PREPARE");
        service.advanceOne().block();service.advanceOne().block();UUID campaign=service.get(run.id()).block().children().getFirst().id();
        db.sql("INSERT INTO suppression_entries(email_hmac,email_domain,reason,source) SELECT email_hmac,email_domain,'MANUAL','TEST' FROM contacts ORDER BY id LIMIT 1").fetch().rowsUpdated().block();
        service.advanceOne().block();var result=service.get(run.id()).block();assertThat(result.children().getFirst().status()).isEqualTo("BLOCKED");assertThat(campaigns.get(campaign).block().status()).isEqualTo("DRAFT");
        assertThat(count("outbox_messages")).isZero();assertThat(count("bulk_outreach_reservations")).isEqualTo(2);
    }
    @Test @Timeout(180) void explicitRiskAcceptanceStartsOnlyThroughNormalReviewApproveAndStart() {
        properties.setProductionHoldReason("Historical deliverability warning");
        var run=service.create(actor,command("accepted-risk-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",1);freeze(run.id(),List.of(paper),"PREPARE");
        for(int i=0;i<6;i++) service.advanceOne().block();
        var result=service.get(run.id()).block();assertThat(result.status()).isEqualTo("SENDING");assertThat(result.selectedCount()).isEqualTo(1);assertThat(result.smtpAccepted()).isZero();
        var campaign=campaigns.get(result.children().getFirst().id()).block();assertThat(campaign.status()).isEqualTo("RUNNING");assertThat(campaign.recipientCounts().generated()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) AS n FROM audit_logs WHERE resource_id=:run AND action='BULK_OUTREACH_CREATED' AND after_summary->>'acceptDeliverabilityRisk'='true'").bind("run",run.id().toString()).map((r,m)->r.get("n",Long.class)).one().block()).isEqualTo(1);
    }
    @Test void staleCancellationDoesNotCancelAndCurrentCancellationIsDurable() {
        var run=service.create(actor,command("cancel-key-123",100,false,false),context()).block();service.advanceOne().block();
        assertThatThrownBy(()->service.cancel(run.id(),actor,run.lockVersion(),context()).block()).isInstanceOf(OutreachConflictException.class);
        var canceled=service.cancel(run.id(),actor,service.get(run.id()).block().lockVersion(),context()).block();
        assertThat(canceled.status()).isEqualTo("CANCELED");assertThat(service.advanceOne().block()).isFalse();
    }
    @Test @Timeout(180) void cancelReadbackRetainsAcceptedAndUnknownOutcomesThatArrivedSinceLastPoll() {
        var run=service.create(actor,command("cancel-outcomes-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",2);freeze(run.id(),List.of(paper),"PREPARE");service.advanceOne().block();service.advanceOne().block();
        var before=service.get(run.id()).block();UUID campaign=before.children().getFirst().id();
        db.sql("UPDATE campaign_recipients cr SET status=CASE WHEN ranks.n=1 THEN 'SMTP_ACCEPTED' ELSE 'OUTCOME_UNKNOWN' END,attempt_count=1 FROM(SELECT id,row_number() OVER(ORDER BY id) AS n FROM campaign_recipients WHERE campaign_id=:id) ranks WHERE cr.id=ranks.id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE campaigns SET status='RUNNING' WHERE id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        var canceled=service.cancel(run.id(),actor,before.lockVersion(),context()).block();
        assertThat(canceled.status()).isEqualTo("CANCELED");assertThat(canceled.smtpAccepted()).isEqualTo(1);assertThat(canceled.unknownCount()).isEqualTo(1);
        assertThat(db.sql("SELECT outcome FROM bulk_outreach_reservations ORDER BY outcome").map((r,m)->r.get("outcome",String.class)).all().collectList().block()).containsExactly("OUTCOME_UNKNOWN","SMTP_ACCEPTED");
    }
    @Test @Timeout(180) void unsafePaperScalarsAreExcludedWhileSafePaperContactsContinue() {
        var run=service.create(actor,command("scalar-isolation-123",3,false,true),context()).block();
        UUID safe=paper(1,"SUCCEEDED",3),unsafeTitle=paper(2,"SUCCEEDED",0),unsafeAbstract=paper(3,"SUCCEEDED",0);
        for(var entry:Map.of(unsafeTitle,2,unsafeAbstract,3).entrySet())
            db.sql("UPDATE paper_author_contacts SET paper_id=:paper,extraction_run_id=(SELECT id FROM extraction_runs WHERE paper_id=:paper) WHERE contact_id=(SELECT id FROM contacts WHERE email_hmac=decode(lpad(to_hex(:index),64,'0'),'hex'))").bind("paper",entry.getKey()).bind("index",entry.getValue()).fetch().rowsUpdated().block();
        db.sql("UPDATE papers SET title='Unsafe {{source}}' WHERE id=:paper").bind("paper",unsafeTitle).fetch().rowsUpdated().block();
        db.sql("UPDATE papers SET abstract_text='Unsafe {{abstract}}' WHERE id=:paper").bind("paper",unsafeAbstract).fetch().rowsUpdated().block();
        freeze(run.id(),List.of(safe,unsafeTitle,unsafeAbstract),"PREPARE");for(int i=0;i<4;i++) service.advanceOne().block();
        var result=service.get(run.id()).block();assertThat(result.status()).isEqualTo("ACTIVE");assertThat(result.excludedCount()).isEqualTo(2);assertThat(result.selectedCount()).isEqualTo(1);
        assertThat(db.sql("SELECT outcome FROM bulk_outreach_papers WHERE run_id=:run AND paper_id<>:safe").bind("run",run.id()).bind("safe",safe).map((r,m)->r.get("outcome",String.class)).all().collectList().block()).containsExactlyInAnyOrder("CONTENT_REJECTED","CONTENT_REJECTED");
        assertThat(db.sql("SELECT DISTINCT paper_id FROM campaign_recipients").map((r,m)->r.get("paper_id",UUID.class)).all().collectList().block()).containsExactly(safe);
    }
    @Test @Timeout(120) void sharedUnsafeTemplateRemainsFailClosedNotAPaperExclusion() {
        var run=service.create(actor,command("template-hard-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",1);freeze(run.id(),List.of(paper),"PREPARE");
        db.sql("UPDATE email_template_versions SET subject_template='Pay $123' WHERE id=:version").bind("version",version).fetch().rowsUpdated().block();service.advanceOne().block();
        var result=service.get(run.id()).block();assertThat(result.status()).isEqualTo("BLOCKED");assertThat(result.excludedCount()).isZero();assertThat(result.children()).isEmpty();assertThat(count("bulk_outreach_reservations")).isZero();
    }
    @Test @Timeout(180) void completedParentReadsDelayedBounceWithoutChangingControlState() {
        var run=service.create(actor,command("late-bounce-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",1);freeze(run.id(),List.of(paper),"PREPARE");service.advanceOne().block();service.advanceOne().block();
        UUID campaign=service.get(run.id()).block().children().getFirst().id();
        db.sql("UPDATE campaign_recipients SET status='SMTP_ACCEPTED',attempt_count=1 WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE campaigns SET status='COMPLETED' WHERE id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE bulk_outreach_children SET status='SENDING',stage='SENDING' WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();service.advanceOne().block();
        var terminal=service.get(run.id()).block();assertThat(terminal.status()).isEqualTo("COMPLETED");
        db.sql("UPDATE campaign_recipients SET status='BOUNCED' WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        var live=service.get(run.id()).block();assertThat(live.bouncedCount()).isEqualTo(1);assertThat(live.smtpAccepted()).isEqualTo(1);assertThat(live.children().getFirst().bouncedCount()).isEqualTo(1);
        assertThat(live.status()).isEqualTo("COMPLETED");assertThat(live.lockVersion()).isEqualTo(terminal.lockVersion());assertThat(service.list(1,20).block().items().getFirst()).isEqualTo(live);assertThat(service.advanceOne().block()).isFalse();assertThat(count("bulk_outreach_reservations")).isEqualTo(1);
    }
    @Test @Timeout(180) void canceledParentReadsLaterInflightAcceptanceAndBounceWithoutReplay() {
        var run=service.create(actor,command("late-cancel-123",1,false,true),context()).block();UUID paper=paper(1,"SUCCEEDED",2);freeze(run.id(),List.of(paper),"PREPARE");service.advanceOne().block();service.advanceOne().block();
        var before=service.get(run.id()).block();UUID campaign=before.children().getFirst().id();
        db.sql("UPDATE campaign_recipients cr SET status=CASE WHEN ranks.n=1 THEN 'SMTP_ACCEPTED' ELSE 'CONNECTING' END,attempt_count=1 FROM(SELECT id,row_number() OVER(ORDER BY id) AS n FROM campaign_recipients WHERE campaign_id=:id) ranks WHERE cr.id=ranks.id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE campaigns SET status='RUNNING' WHERE id=:id").bind("id",campaign).fetch().rowsUpdated().block();var canceled=service.cancel(run.id(),actor,before.lockVersion(),context()).block();
        db.sql("UPDATE campaign_recipients SET status=CASE WHEN status='CONNECTING' THEN 'SMTP_ACCEPTED' ELSE 'BOUNCED' END WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        var live=service.get(run.id()).block();assertThat(live.smtpAccepted()).isEqualTo(2);assertThat(live.bouncedCount()).isEqualTo(1);assertThat(live.children().getFirst().smtpAccepted()).isEqualTo(2);
        assertThat(live.status()).isEqualTo("CANCELED");assertThat(live.lockVersion()).isEqualTo(canceled.lockVersion());assertThat(service.list(1,20).block().items().getFirst()).isEqualTo(live);assertThat(service.advanceOne().block()).isFalse();assertThat(count("campaigns")).isEqualTo(1);assertThat(count("jobs")).isZero();assertThat(count("bulk_outreach_reservations")).isEqualTo(2);
    }

    private BulkOutreachService service() { return new BulkOutreachService(repository,new OutreachRunRepository(db),imports,extraction,adapter,campaigns,preflight,workflow,audit,hasher,mapper,properties,tx); }

    @Test void bounceOnlyCompletedChildDoesNotFailParentAndShortfallIsSupplementary() {
        var run=service.create(actor,command("bounce-only-diagnostic",2,false,true),context()).block();
        UUID paper=paper(1,"SUCCEEDED",1);freeze(run.id(),List.of(paper),"PREPARE");
        service.advanceOne().block();service.advanceOne().block();
        UUID campaign=service.get(run.id()).block().children().getFirst().id();
        db.sql("UPDATE campaign_recipients SET status='BOUNCED',attempt_count=1,smtp_accepted_at=now() WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE campaigns SET status='COMPLETED' WHERE id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE bulk_outreach_children SET status='SENDING',stage='SENDING' WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        service.advanceOne().block();
        var result=service.get(run.id()).block();
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.children().getFirst().status()).isEqualTo("COMPLETED");
        assertThat(result.smtpAccepted()).isEqualTo(1);assertThat(result.bouncedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();assertThat(result.unknownCount()).isZero();
        assertThat(service.advanceOne().block()).isFalse();assertThat(count("jobs")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"PERMANENT_FAILURE,PREPARATION_FAILED", "PERMANENT_FAILURE,SMTP_REJECTED", "OUTCOME_UNKNOWN,UNEXPECTED_FAILURE"})
    void actualChildFailureIsParentFailureReasonAndSearchShortfallOnlySupplementary(String status,String category) {
        var run=service.create(actor,command("failure-cause-"+category,2,false,true),context()).block();
        UUID paper=paper(1,"SUCCEEDED",1);freeze(run.id(),List.of(paper),"PREPARE");service.advanceOne().block();service.advanceOne().block();
        UUID campaign=service.get(run.id()).block().children().getFirst().id();
        db.sql("UPDATE campaign_recipients SET status=:status,attempt_count=1 WHERE campaign_id=:id").bind("status",status).bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("INSERT INTO delivery_attempts(campaign_recipient_id,smtp_account_id,attempt_number,idempotency_key,status,failure_category,started_at,completed_at) SELECT r.id,c.smtp_account_id,1,'synthetic:'||r.id,:status,:category,now(),now() FROM campaign_recipients r JOIN campaigns c ON c.id=r.campaign_id WHERE r.campaign_id=:id")
            .bind("status",status).bind("category",category).bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE campaigns SET status='COMPLETED' WHERE id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE bulk_outreach_children SET status='SENDING',stage='SENDING' WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        db.sql("UPDATE bulk_outreach_runs SET state=jsonb_set(state,'{shortfall}','\"Synthetic search shortfall\"') WHERE id=:id").bind("id",run.id()).fetch().rowsUpdated().block();
        service.advanceOne().block();var terminal=service.get(run.id()).block();
        assertThat(terminal.status()).isEqualTo("FAILED");assertThat(terminal.message()).startsWith("Parent failed because a child").contains("supplementary search information", "Synthetic search shortfall");
        assertThat(terminal.failedCount()+terminal.unknownCount()).isEqualTo(1);assertThat(terminal.bouncedCount()).isZero();
        db.sql("UPDATE campaign_recipients SET replied_at=now() WHERE campaign_id=:id").bind("id",campaign).fetch().rowsUpdated().block();
        assertThat(service.advanceOne().block()).isFalse();assertThat(service.get(run.id()).block().status()).isEqualTo("FAILED");
        assertThat(service.get(run.id()).block().lockVersion()).isEqualTo(terminal.lockVersion());assertThat(count("jobs")).isZero();assertThat(count("campaigns")).isEqualTo(1);
    }
    private SourceRace sourceCreatorRace(boolean terminal) throws Exception {
        return sourceCreatorRace(terminal,false);
    }
    private SourceRace sourceCreatorRace(boolean terminal,boolean extraPending) throws Exception {
        UUID paper=paper(1,"SUCCEEDED",0);db.sql("DELETE FROM extraction_runs WHERE paper_id=:id").bind("id",paper).fetch().rowsUpdated().block();
        var candidates=new ArrayList<UUID>();candidates.add(paper);
        if(extraPending) {UUID other=paper(2,"SUCCEEDED",0);db.sql("DELETE FROM extraction_runs WHERE paper_id=:id").bind("id",other).fetch().rowsUpdated().block();candidates.add(other);}
        var run=service.create(actor,command("source-race-123",candidates.size(),false,false),context()).block();freeze(run.id(),candidates,"EXTRACT");
        var reachedRead=new java.util.concurrent.CountDownLatch(1);var winnerCommitted=reactor.core.publisher.Sinks.<Void>one();var firstRead=new java.util.concurrent.atomic.AtomicBoolean(true);
        var gatedRepository=spy(repository);
        // Decorate the completed real SQL read only; all returned rows and both creators' side effects stay real.
        doAnswer(invocation->{
            @SuppressWarnings("unchecked") var rows=(Flux<BulkOutreachRepository.PaperOutcome>)invocation.callRealMethod();
            return firstRead.compareAndSet(true,false)?rows.doOnComplete(reachedRead::countDown).concatWith(winnerCommitted.asMono().thenMany(Flux.empty())):rows;
        }).when(gatedRepository).extractionOutcomes(run.id());
        var racingParent=new BulkOutreachService(gatedRepository,new OutreachRunRepository(db),imports,extraction,adapter,campaigns,preflight,workflow,audit,hasher,mapper,properties,tx);
        var advancing=racingParent.advanceOne().toFuture();UUID sourceJob;
        try {
            assertThat(reachedRead.await(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            sourceJob=extraction.create(actor,List.of(paper),context()).block(Duration.ofSeconds(30)).jobId();
            if(terminal) db.sql("UPDATE jobs SET status='FAILED',ended_at=now() WHERE id=:id").bind("id",sourceJob).fetch().rowsUpdated().block();
        } finally { winnerCommitted.tryEmitEmpty(); }
        assertThat(advancing.get(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return new SourceRace(run.id(),sourceJob);
    }
    private record SourceRace(UUID runId,UUID jobId) { }
    private BulkOutreachCommand command(String key,int target,boolean incremental,boolean risk) { return new BulkOutreachCommand("Bulk test","neutral invitation",target,smtp,template,List.of(new ArxivSearchCriteria(List.of("cs.AI"),ArxivSearchCriteria.CategoryMode.ANY,null,null,null,null,null,"Agent",null,null,null,null,ArxivSearchCriteria.SortBy.SUBMITTED_DATE,ArxivSearchCriteria.SortOrder.DESCENDING,1,100)),incremental,risk,"AUTO_OUTREACH_SEND",key); }
    private AuthenticationRequestContext context() { return new AuthenticationRequestContext("127.0.0.1","bulk-test","bulk-test-trace"); }
    private long count(String table) { return db.sql("SELECT count(*) AS n FROM "+table).map((r,m)->r.get("n",Long.class)).one().block(); }
    private void freeze(UUID run,List<UUID> papers,String stage) {
        for(UUID paper:papers) db.sql("INSERT INTO bulk_outreach_papers(run_id,paper_id,arxiv_id) SELECT :run,id,arxiv_id FROM papers WHERE id=:paper").bind("run",run).bind("paper",paper).fetch().rowsUpdated().block();
        if(stage.equals("PREPARE")) repository.extractionOutcomes(run).concatMap(outcome->repository.saveOutcome(run,outcome)).then().block();
        db.sql("UPDATE bulk_outreach_runs SET stage=:stage WHERE id=:run").bind("run",run).bind("stage",stage).fetch().rowsUpdated().block();
    }
    private UUID paper(int index,String status,int contacts) {
        UUID id=UUID.randomUUID(), extractionId=UUID.randomUUID();String arxiv="2610."+String.format("%05d",index);
        db.sql("INSERT INTO papers(id,arxiv_id,title,abstract_text,submitted_at,updated_at,pdf_url) VALUES(:id,:arxiv,'title','abstract',now(),now(),'https://arxiv.org/pdf/'||:arxiv)").bind("id",id).bind("arxiv",arxiv).fetch().rowsUpdated().block();
        db.sql("INSERT INTO extraction_runs(id,paper_id,parser_version,status,started_at,completed_at) VALUES(:id,:paper,'0.1.0',:status,now(),now())").bind("id",extractionId).bind("paper",id).bind("status",status).fetch().rowsUpdated().block();
        if(contacts>0) {
            db.sql("INSERT INTO contacts(email_ciphertext,email_nonce,email_hmac,email_domain,display_ciphertext,syntax_valid,example_address,suppression_status) SELECT decode(repeat('01',16),'hex'),decode(repeat('02',12),'hex'),decode(lpad(to_hex(i),64,'0'),'hex'),'example.org',decode(repeat('01',16),'hex'),true,false,'ACTIVE' FROM generate_series(1,:count) i").bind("count",contacts).fetch().rowsUpdated().block();
            db.sql("INSERT INTO paper_author_contacts(paper_id,contact_id,extraction_run_id,confidence,human_verified,verification_status) SELECT :paper,id,:extraction,'LOW',false,'UNVERIFIED' FROM contacts").bind("paper",id).bind("extraction",extractionId).fetch().rowsUpdated().block();
            db.sql("INSERT INTO extraction_evidence(paper_author_contact_id,source_relative_path,rule_name,masked_context) SELECT id,'source.tex','source-email','masked source' FROM paper_author_contacts").fetch().rowsUpdated().block();
        }return id;
    }
    private void seed() {
        db.sql("INSERT INTO users(id,username,email,password_hash,display_name,force_password_change) VALUES(:actor,'bulk-user','bulk@example.org','hash','Bulk',false)").bind("actor",actor).fetch().rowsUpdated().block();
        db.sql("INSERT INTO user_roles(user_id,role_id) SELECT :actor,id FROM roles WHERE code='SUPER_ADMIN'").bind("actor",actor).fetch().rowsUpdated().block();
        db.sql("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,username,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit,enabled,last_test_status,last_tested_at,updated_at) VALUES(:smtp,'sender','smtp.example.org',465,'TLS_IMPLICIT','sender@example.org','sender@example.org','Team','sender@example.org',1,10,100,10,true,'SUCCEEDED',now(),now())").bind("smtp",smtp).fetch().rowsUpdated().block();
        db.sql("INSERT INTO mailbox_accounts(id,name,protocol,host,port,tls_mode,username,password_ciphertext,password_nonce,enabled,last_test_status,last_tested_at,updated_at) VALUES(:id,'replies','IMAP','imap.example.org',993,'TLS_IMPLICIT','sender@example.org',decode(repeat('01',16),'hex'),decode(repeat('01',12),'hex'),true,'SUCCEEDED',now(),now())").bind("id",mailbox).fetch().rowsUpdated().block();
        db.sql("INSERT INTO email_templates(id,name,status) VALUES(:id,'invitation','ACTIVE')").bind("id",template).fetch().rowsUpdated().block();
        db.sql("INSERT INTO email_template_versions(id,template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes) VALUES(:id,:template,1,'Hello {{paper_title}}','Team','sender@example.org','<p>Hello {{unsubscribe_url}}</p>','Hello {{unsubscribe_url}}',80)").bind("id",version).bind("template",template).fetch().rowsUpdated().block();
    }
}
