package com.camel_hub.advertisement.outreach.schedule;
import io.r2dbc.spi.*;
import io.r2dbc.pool.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import java.time.Instant;
import java.util.UUID;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import com.camel_hub.advertisement.outreach.OutreachRunRepository;
import com.camel_hub.advertisement.system.personalization.*;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
@Timeout(30)
class WeeklyScheduleRepositoryIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine");
    private static final String schema="weekly_fixture_"+UUID.randomUUID().toString().replace("-","");
    @BeforeAll static void database(){
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
    }
    @AfterAll static void stopDatabase(){if(POSTGRES.isRunning())POSTGRES.stop();}
    private DatabaseClient db;private ConnectionPool pool;private TransactionalOperator tx;private WeeklyScheduleRepository repo;
    private final UUID actor=UUID.randomUUID(),smtp=UUID.randomUUID(),template=UUID.randomUUID(),schedule=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-12T01:00:00Z");
    @BeforeEach void setup(){
        String jdbc=POSTGRES.getJdbcUrl(),user=POSTGRES.getUsername(),pass=POSTGRES.getPassword();
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.parse(jdbc.replace("jdbc:","r2dbc:")).mutate().option(ConnectionFactoryOptions.USER,user).option(ConnectionFactoryOptions.PASSWORD,pass).option(Option.valueOf("schema"),schema).build());
        pool=new ConnectionPool(ConnectionPoolConfiguration.builder(factory).initialSize(1).maxSize(5).build());db=DatabaseClient.create(pool);tx=TransactionalOperator.create(new R2dbcTransactionManager(pool));repo=new WeeklyScheduleRepository(db);
        db.sql("TRUNCATE outreach_schedules,users,smtp_accounts,email_templates CASCADE").fetch().rowsUpdated().block();
        db.sql("INSERT INTO users(id,username,email,password_hash,display_name,force_password_change) VALUES(:id,'weekly','weekly@example.org','hash','Weekly',false)").bind("id",actor).fetch().rowsUpdated().block();
        db.sql("INSERT INTO smtp_accounts(id,name,host,port,tls_mode,username,from_email,default_from_name,reply_to,per_minute_limit,per_hour_limit,per_day_limit,per_domain_hour_limit) VALUES(:id,'weekly','smtp.example.org',465,'TLS_IMPLICIT','weekly@example.org','weekly@example.org','Team','weekly@example.org',1,10,100,10)").bind("id",smtp).fetch().rowsUpdated().block();
        db.sql("INSERT INTO email_templates(id,name,status)VALUES(:id,'Weekly','ACTIVE')").bind("id",template).fetch().rowsUpdated().block();
        db.sql("INSERT INTO outreach_schedules(id,actor_id,idempotency_key,payload_hash,name,purpose,max_papers,smtp_account_id,template_id,directions,request_context,config_snapshot,next_run_at)VALUES(:id,:actor,'weekly-test-key',decode('01','hex'),'Weekly','Research',5000,:smtp,:template,'[\"Agent\"]','{}','{}',:due)").bind("id",schedule).bind("actor",actor).bind("smtp",smtp).bind("template",template).bind("due",now).fetch().rowsUpdated().block();
    }
    @AfterEach void close(){if(pool!=null)pool.dispose();}
    private WeeklyScheduleRepository.Occurrence claim(Instant at){return tx.transactional(repo.lockNext(at).flatMap(s->repo.reserve(s,at))).block();}
    @Test void claimTwiceAndRestartUseExactlyOneDurableOccurrence(){
        var first=claim(now);assertThat(first).isNotNull();assertThat(claim(now)).isNull();
        repo=new WeeklyScheduleRepository(db);var recovered=claim(now.plusSeconds(181));
        assertThat(recovered.key()).isEqualTo(first.key());assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(count()).isEqualTo(1L);
    }
    @Test void missedWeeksReserveOnlyOneBoundedCatchup(){
        var late=Instant.parse("2026-10-30T01:00:00Z");assertThat(claim(late)).isNotNull();
        assertThat(repo.find(schedule).block().nextRunAt()).isEqualTo(Instant.parse("2026-11-02T01:00:00Z"));
        assertThat(claim(late)).isNull();assertThat(count()).isEqualTo(1L);
    }
    @Test void pausedScheduleDoesNotReserveAnything(){db.sql("UPDATE outreach_schedules SET enabled=false").fetch().rowsUpdated().block();assertThat(claim(now)).isNull();assertThat(count()).isZero();}
    @Test void actorPermissionLossBlocksBeforeCreatingAnyParent(){
        var service=service();assertThat(service.advanceOne().block()).isTrue();
        assertThat(repo.find(schedule).block().enabled()).isFalse();
        assertThat(db.sql("SELECT status FROM outreach_schedule_occurrences").map((r,m)->r.get("status",String.class)).one().block()).isEqualTo("BLOCKED");
        assertThat(parentCount()).isZero();
    }
    @Test void validScheduleFreezesFallbackAndCreatesOnlyOneIncrementalParentAfterRestart(){
        seedValidConfig();var service=service();
        var view=service.create(actor,new WeeklyScheduleCommand("Weekly created","Research",5000,smtp,template,List.of("Agent"),true,"AUTO_OUTREACH_SEND","weekly-create-key"),context()).block();
        assertThat(view).isNotNull();assertThat(view.nextRunAt()).isEqualTo(Instant.parse("2026-10-19T01:00:00Z"));
        db.sql("DELETE FROM outreach_schedules WHERE id=:id").bind("id",schedule).fetch().rowsUpdated().block();
        db.sql("UPDATE outreach_schedules SET next_run_at=:now").bind("now",now).fetch().rowsUpdated().block();
        assertThat(service.advanceOne().block()).isTrue();assertThat(service().advanceOne().block()).isFalse();
        assertThat(parentCount()).isEqualTo(1L);
        String discovery=db.sql("SELECT discovery::text d FROM outreach_schedule_occurrences").map((r,m)->r.get("d",String.class)).one().block();
        assertThat(discovery).contains("PROVIDER_DISABLED").doesNotContain("secret-key");
        String command=db.sql("SELECT bulk_command::text c FROM outreach_schedule_occurrences").map((r,m)->r.get("c",String.class)).one().block();
        assertThat(command).contains("\"incremental\": true","\"targetPapers\": 5000","AUTO_OUTREACH_SEND");
    }
    @Test void previousActiveParentPreventsOverlap(){
        UUID parent=insertParent("previous-parent");db.sql("UPDATE outreach_schedules SET last_run_id=:id").bind("id",parent).fetch().rowsUpdated().block();
        assertThat(claim(now)).isNull();assertThat(count()).isZero();
        db.sql("UPDATE bulk_outreach_runs SET status='SENDING'").fetch().rowsUpdated().block();assertThat(claim(now)).isNull();
        db.sql("UPDATE bulk_outreach_runs SET status='COMPLETED'").fetch().rowsUpdated().block();assertThat(claim(now)).isNotNull();
    }
    @Test void concurrentInstancesReserveExactlyOneOccurrence(){
        var values=reactor.core.publisher.Flux.merge(tx.transactional(repo.lockNext(now).flatMap(s->repo.reserve(s,now))),tx.transactional(new WeeklyScheduleRepository(db).lockNext(now).flatMap(s->repo.reserve(s,now)))).collectList().block();
        assertThat(values).hasSize(1);assertThat(count()).isEqualTo(1L);
    }
    @Test void staleLeaseCannotFreezeEvidenceAfterAnotherInstanceRecovers(){
        var stale=claim(now);var recovered=claim(now.plusSeconds(181));
        assertThat(repo.reserveDiscoveryAttempt(stale).block()).isNull();
        assertThat(repo.reserveDiscoveryAttempt(recovered).block().discoveryAttempts()).isEqualTo(1);
        assertThat(repo.ready(stale,"{}","{}").block()).isNull();
        assertThat(repo.ready(recovered,"{}","{}").block().status()).isEqualTo("READY");
    }
    @Test void changedIdempotencyPayloadConflictsAndPauseRequiresCurrentVersion(){
        seedValidConfig();var service=service();var command=new WeeklyScheduleCommand("Weekly created","Research",5000,smtp,template,List.of("Agent"),true,"AUTO_OUTREACH_SEND","weekly-create-key");
        var first=service.create(actor,command,context()).block();assertThat(service.create(actor,command,context()).block().id()).isEqualTo(first.id());
        assertThatThrownBy(()->service.create(actor,new WeeklyScheduleCommand(command.name(),command.purpose(),4999,smtp,template,command.directions(),true,command.confirmation(),command.idempotencyKey()),context()).block()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
        var paused=service.changeEnabled(first.id(),actor,first.lockVersion(),false,context()).block();assertThat(paused.enabled()).isFalse();
        assertThatThrownBy(()->service.changeEnabled(first.id(),actor,first.lockVersion(),true,context()).block()).hasMessageContaining("409");
        assertThat(service.changeEnabled(first.id(),actor,paused.lockVersion(),true,context()).block().enabled()).isTrue();
    }
    @Test void configurationDriftDisablesScheduleWithoutCreatingParent(){
        seedValidConfig();var service=service();var view=service.create(actor,new WeeklyScheduleCommand("Weekly created","Research",5000,smtp,template,List.of("Agent"),false,"AUTO_OUTREACH_SEND","weekly-drift-key"),context()).block();
        db.sql("DELETE FROM outreach_schedules WHERE id=:id").bind("id",schedule).fetch().rowsUpdated().block();
        db.sql("UPDATE outreach_schedules SET next_run_at=:now").bind("now",now).fetch().rowsUpdated().block();
        db.sql("UPDATE smtp_accounts SET lock_version=lock_version+1").fetch().rowsUpdated().block();
        assertThat(service.advanceOne().block()).isTrue();assertThat(service.get(view.id()).block().enabled()).isFalse();assertThat(parentCount()).isZero();
    }
    @Test void readyCheckpointSurvivesRestartWithoutAnotherProviderCall(){
        seedValidConfig();var service=service();var view=service.create(actor,new WeeklyScheduleCommand("Weekly created","Research",5000,smtp,template,List.of("Agent"),false,"AUTO_OUTREACH_SEND","weekly-ready-key"),context()).block();
        db.sql("DELETE FROM outreach_schedules WHERE id=:id").bind("id",schedule).fetch().rowsUpdated().block();
        db.sql("UPDATE outreach_schedules SET next_run_at=:now").bind("now",now).fetch().rowsUpdated().block();
        var occurrence=claim(now);var seed=new WeeklyScheduleCommand("Weekly created","Research",5000,smtp,template,List.of("Agent"),false,"AUTO_OUTREACH_SEND",occurrence.key());
        var discovery=TrendDiscovery.fallback(seed.directions(),"PROVIDER_DISABLED",0,"openai","test",0,0,0);var mapper=new ObjectMapper().findAndRegisterModules();
        try{repo.ready(occurrence,mapper.writeValueAsString(discovery),mapper.writeValueAsString(WeeklyScheduleService.bulkCommand(seed,discovery,occurrence.from(),occurrence.to(),occurrence.key()))).block();}catch(Exception e){throw new RuntimeException(e);}
        db.sql("UPDATE outreach_schedule_occurrences SET lease_until=:now").bind("now",now).fetch().rowsUpdated().block();
        var noNetwork=mock(TrendDiscovery.class);when(noNetwork.discover(any(),any())).thenReturn(Mono.error(new AssertionError("Provider must not be called after READY checkpoint")));
        assertThat(service(noNetwork).advanceOne().block()).isTrue();assertThat(service.get(view.id()).block().lastRunId()).isNotNull();assertThat(parentCount()).isEqualTo(1L);
    }
    @Test void repeatedCrashesBeforeReadyConsumeOnlyTwoDurableDiscoveryAttempts()throws Exception{
        seedValidConfig();var view=service().create(actor,new WeeklyScheduleCommand("Weekly created","Research",5000,smtp,template,List.of("Agent"),false,"AUTO_OUTREACH_SEND","weekly-crash-budget-key"),context()).block();
        db.sql("DELETE FROM outreach_schedules WHERE id=:id").bind("id",schedule).fetch().rowsUpdated().block();
        db.sql("UPDATE outreach_schedules SET next_run_at=:now").bind("now",now).fetch().rowsUpdated().block();
        var calls=new java.util.concurrent.atomic.AtomicInteger();var entered=new java.util.concurrent.LinkedBlockingQueue<Integer>();
        var stalled=mock(TrendDiscovery.class);when(stalled.discover(any(),any())).thenAnswer(invocation->Mono.defer(()->{entered.add(calls.incrementAndGet());return Mono.never();}));
        String key=null;
        for(int attempt=1;attempt<=2;attempt++){
            var subscription=service(stalled).advanceOne().subscribe();
            try{assertThat(entered.poll(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(attempt);}finally{subscription.dispose();}
            String current=db.sql("SELECT occurrence_key FROM outreach_schedule_occurrences").map((r,m)->r.get("occurrence_key",String.class)).one().block();
            if(key==null)key=current;else assertThat(current).isEqualTo(key);
            db.sql("UPDATE outreach_schedule_occurrences SET lease_until=:now").bind("now",now).fetch().rowsUpdated().block();
        }
        // A third process must use truthful seeds, not submit another paid request.
        assertThat(service(stalled).advanceOne().block(java.time.Duration.ofSeconds(10))).isTrue();
        assertThat(calls.get()).isEqualTo(2);assertThat(parentCount()).isEqualTo(1L);
        var frozen=db.sql("SELECT discovery::text AS d,discovery_attempts AS attempts FROM outreach_schedule_occurrences").map((r,m)->java.util.Map.of("discovery",r.get("d",String.class),"attempts",r.get("attempts",Integer.class))).one().block();
        assertThat(frozen.get("attempts")).isEqualTo(2);
        assertThat(frozen.get("discovery").toString()).contains("DISCOVERY_ATTEMPT_BUDGET_EXHAUSTED_USAGE_UNKNOWN","\"usageComplete\": false","\"inputTokens\": -1","Agent");
        assertThat(service().get(view.id()).block().lastRunId()).isNotNull();
    }
    private WeeklyScheduleService service(){
        var settings=new PersonalizationSettingsService(null,"",new PersonalizationSettingsService.EffectiveSettings(0,false,"openai","test","https://api.example.com/v1","bearer","secret-key","ENVIRONMENT",null));
        return service(new TrendDiscovery(new WeeklyTrendClient(),settings));
    }
    private WeeklyScheduleService service(TrendDiscovery discovery){
        var audit=mock(AuditService.class);when(audit.record(any())).thenReturn(Mono.empty());
        var hasher=mock(SensitiveValueHasher.class);when(hasher.hash(any())).thenReturn(new byte[]{1});
        return new WeeklyScheduleService(repo,new OutreachRunRepository(db),discovery,(a,c,x)->db.sql("INSERT INTO bulk_outreach_runs(actor_id,idempotency_key,payload_hash,command,configuration,request_context,state)VALUES(:actor,:key,decode(repeat('01',32),'hex'),'{}','{}','{}','{}') ON CONFLICT(actor_id,idempotency_key)DO UPDATE SET actor_id=EXCLUDED.actor_id RETURNING id").bind("actor",a).bind("key",c.idempotencyKey()).map((r,m)->r.get("id",UUID.class)).one(),audit,hasher,new ObjectMapper().findAndRegisterModules(),tx,Clock.fixed(now,ZoneOffset.UTC));
    }
    private void seedValidConfig(){
        db.sql("INSERT INTO user_roles(user_id,role_id)SELECT :actor,id FROM roles WHERE code='SUPER_ADMIN'").bind("actor",actor).fetch().rowsUpdated().block();
        db.sql("UPDATE smtp_accounts SET enabled=true,last_test_status='SUCCEEDED',last_tested_at=now(),updated_at=now()").fetch().rowsUpdated().block();
        db.sql("INSERT INTO mailbox_accounts(name,protocol,host,port,tls_mode,username,password_ciphertext,password_nonce,enabled,last_test_status,last_tested_at,updated_at)VALUES('Weekly replies','IMAP','imap.example.org',993,'TLS_IMPLICIT','weekly@example.org',decode(repeat('01',16),'hex'),decode(repeat('01',12),'hex'),true,'SUCCEEDED',now(),now())").fetch().rowsUpdated().block();
        db.sql("INSERT INTO email_template_versions(template_id,version_number,subject_template,from_name_template,reply_to,html_content,text_content,content_size_bytes)VALUES(:template,1,'Hello','Team','weekly@example.org','<p>Hello {{unsubscribe_url}}</p>','Hello {{unsubscribe_url}}',80)").bind("template",template).fetch().rowsUpdated().block();
    }
    private UUID insertParent(String key){return db.sql("INSERT INTO bulk_outreach_runs(actor_id,idempotency_key,payload_hash,command,configuration,request_context,state)VALUES(:actor,:key,decode(repeat('01',32),'hex'),'{}','{}','{}','{}')RETURNING id").bind("actor",actor).bind("key",key).map((r,m)->r.get("id",UUID.class)).one().block();}
    private long parentCount(){return db.sql("SELECT count(*) n FROM bulk_outreach_runs").map((r,m)->r.get("n",Long.class)).one().block();}
    private AuthenticationRequestContext context(){return new AuthenticationRequestContext("127.0.0.1","weekly-test","weekly-test");}
    private long count(){return db.sql("SELECT count(*) n FROM outreach_schedule_occurrences").map((r,m)->r.get("n",Long.class)).one().block();}
}
