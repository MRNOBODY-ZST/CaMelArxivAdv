package com.camel_hub.advertisement.outreach.schedule;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import io.r2dbc.spi.Row;
import reactor.core.publisher.Flux;
public final class WeeklyScheduleRepository {
    private final DatabaseClient db;
    public WeeklyScheduleRepository(DatabaseClient db){this.db=db;}
    public record Schedule(UUID id,UUID actorId,byte[] payloadHash,String name,String purpose,int maxPapers,UUID smtpAccountId,UUID templateId,String directionsJson,String contextJson,String configJson,boolean risk,boolean enabled,Instant nextRunAt,Instant lastOccurrenceAt,UUID lastRunId,String message,long lockVersion,Instant createdAt,Instant updatedAt) {}
    public record Occurrence(UUID id,UUID scheduleId,String key,String status,LocalDate from,LocalDate to,UUID leaseToken,Instant leaseUntil,String discoveryJson,String commandJson,int discoveryAttempts) {}
    public Mono<Schedule> find(UUID id){return db.sql("SELECT * FROM outreach_schedules WHERE id=:id").bind("id",id).map((r,m)->schedule(r)).one();}
    public Mono<Schedule> lock(UUID id){return db.sql("SELECT * FROM outreach_schedules WHERE id=:id FOR UPDATE").bind("id",id).map((r,m)->schedule(r)).one();}
    public Mono<Schedule> byKey(UUID actor,String key){return db.sql("SELECT * FROM outreach_schedules WHERE actor_id=:actor AND idempotency_key=:key").bind("actor",actor).bind("key",key).map((r,m)->schedule(r)).one();}
    public Flux<Schedule> list(int offset,int limit){return db.sql("SELECT * FROM outreach_schedules ORDER BY created_at DESC,id OFFSET :offset LIMIT :limit").bind("offset",offset).bind("limit",limit).map((r,m)->schedule(r)).all();}
    public Mono<Long> count(){return db.sql("SELECT count(*) n FROM outreach_schedules").map((r,m)->r.get("n",Long.class)).one();}
    public Mono<Schedule> create(UUID actor,WeeklyScheduleCommand command,byte[] hash,String directions,String context,String config,Instant next){
        return db.sql("""
            INSERT INTO outreach_schedules(actor_id,idempotency_key,payload_hash,name,purpose,max_papers,smtp_account_id,template_id,directions,request_context,config_snapshot,accept_deliverability_risk,next_run_at)
            VALUES(:actor,:key,:hash,:name,:purpose,:max,:smtp,:template,CAST(:directions AS jsonb),CAST(:context AS jsonb),CAST(:config AS jsonb),:risk,:next)
            ON CONFLICT(actor_id,idempotency_key) DO NOTHING RETURNING *
            """).bind("actor",actor).bind("key",command.idempotencyKey()).bind("hash",hash).bind("name",command.name()).bind("purpose",command.purpose()).bind("max",command.maxPapers())
                .bind("smtp",command.smtpAccountId()).bind("template",command.templateId()).bind("directions",directions).bind("context",context).bind("config",config).bind("risk",command.acceptDeliverabilityRisk()).bind("next",next).map((r,m)->schedule(r)).one();
    }
    public Mono<Schedule> enabled(UUID id,long version,boolean enabled,String config,Instant next){
        return db.sql("""
            UPDATE outreach_schedules SET enabled=:enabled,config_snapshot=CAST(:config AS jsonb),next_run_at=:next,
            message=:message,lock_version=lock_version+1,updated_at=now() WHERE id=:id AND lock_version=:version RETURNING *
            """).bind("id",id).bind("version",version).bind("enabled",enabled).bind("config",config).bind("next",next).bind("message",enabled?"Weekly incremental research resumed":"Paused; no new occurrence will be launched").map((r,m)->schedule(r)).one();
    }
    public Mono<Schedule> lockNext(Instant now){
        return db.sql("""
            SELECT s.* FROM outreach_schedules s WHERE s.enabled
            AND NOT EXISTS(SELECT 1 FROM bulk_outreach_runs b WHERE b.id=s.last_run_id AND b.status IN ('ACTIVE','SENDING'))
            AND ((s.next_run_at<=:now AND NOT EXISTS(SELECT 1 FROM outreach_schedule_occurrences o WHERE o.schedule_id=s.id AND o.status IN ('RESERVED','READY')))
              OR EXISTS(SELECT 1 FROM outreach_schedule_occurrences o WHERE o.schedule_id=s.id AND o.status IN ('RESERVED','READY') AND o.lease_until<=:now))
            ORDER BY s.next_run_at,s.id LIMIT 1 FOR UPDATE OF s SKIP LOCKED
            """).bind("now",now).map((r,m)->schedule(r)).one();
    }
    public Mono<Occurrence> reserve(Schedule s,Instant now){
        UUID token=UUID.randomUUID();Instant until=now.plusSeconds(180);
        return db.sql("""
            UPDATE outreach_schedule_occurrences SET lease_token=:token,lease_until=:until,updated_at=now()
            WHERE schedule_id=:id AND status IN ('RESERVED','READY') AND lease_until<=:now RETURNING *
            """).bind("id",s.id()).bind("now",now).bind("token",token).bind("until",until).map((r,m)->occurrence(r)).one()
            .switchIfEmpty(Mono.defer(()->{
                var window=WeeklySchedulePolicy.window(now,s.lastOccurrenceAt());String key="weekly:"+s.id()+":"+s.nextRunAt().getEpochSecond();
                return db.sql("""
                    INSERT INTO outreach_schedule_occurrences(schedule_id,scheduled_for,occurrence_key,window_from,window_to,lease_token,lease_until)
                    VALUES(:id,:scheduled,:key,:from,:to,:token,:until) ON CONFLICT DO NOTHING RETURNING *
                    """).bind("id",s.id()).bind("scheduled",s.nextRunAt()).bind("key",key).bind("from",window.from()).bind("to",window.to()).bind("token",token).bind("until",until).map((r,m)->occurrence(r)).one()
                    .flatMap(o->db.sql("UPDATE outreach_schedules SET next_run_at=:next,lock_version=lock_version+1,updated_at=now() WHERE id=:id").bind("next",WeeklySchedulePolicy.next(now)).bind("id",s.id()).fetch().rowsUpdated().thenReturn(o));
            }));
    }
    public Mono<Occurrence> ready(Occurrence occurrence,String discovery,String command){
        return db.sql("""
            UPDATE outreach_schedule_occurrences SET status='READY',discovery=CAST(:discovery AS jsonb),bulk_command=CAST(:command AS jsonb),updated_at=now()
            WHERE id=:id AND lease_token=:token AND status='RESERVED' RETURNING *
            """).bind("id",occurrence.id()).bind("token",occurrence.leaseToken()).bind("discovery",discovery).bind("command",command).map((r,m)->occurrence(r)).one();
    }
    public Mono<Occurrence> reserveDiscoveryAttempt(Occurrence occurrence){
        // Commit before any HTTP. A crash consumes its reservation even when actual usage is unknown.
        return db.sql("""
            UPDATE outreach_schedule_occurrences SET discovery_attempts=discovery_attempts+1,updated_at=now()
            WHERE id=:id AND lease_token=:token AND status='RESERVED' AND discovery_attempts<2 RETURNING *
            """).bind("id",occurrence.id()).bind("token",occurrence.leaseToken()).map((r,m)->occurrence(r)).one();
    }
    public Mono<Occurrence> lockOccurrence(Occurrence occurrence){return db.sql("SELECT * FROM outreach_schedule_occurrences WHERE id=:id AND lease_token=:token AND status IN ('RESERVED','READY') FOR UPDATE").bind("id",occurrence.id()).bind("token",occurrence.leaseToken()).map((r,m)->occurrence(r)).one();}
    public Mono<Void> launched(Schedule schedule,Occurrence occurrence,UUID parent,Instant windowEnd){
        return db.sql("UPDATE outreach_schedule_occurrences SET status='LAUNCHED',parent_run_id=:parent,message='Incremental bulk parent created',updated_at=now() WHERE id=:id AND lease_token=:token AND status='READY'")
            .bind("parent",parent).bind("id",occurrence.id()).bind("token",occurrence.leaseToken()).fetch().rowsUpdated().flatMap(n->{
                if(n!=1)return Mono.error(new IllegalStateException("Occurrence changed"));
                return db.sql("UPDATE outreach_schedules SET last_run_id=:parent,last_occurrence_at=:windowEnd,message='Incremental bulk parent created; SMTP acceptance is not delivery',lock_version=lock_version+1,updated_at=now() WHERE id=:id").bind("parent",parent).bind("windowEnd",windowEnd).bind("id",schedule.id()).fetch().rowsUpdated().then();
            });
    }
    public Mono<Void> block(Schedule schedule,Occurrence occurrence,String message){
        return db.sql("UPDATE outreach_schedule_occurrences SET status='BLOCKED',message=:message,updated_at=now() WHERE id=:id AND lease_token=:token AND status IN ('RESERVED','READY')")
            .bind("message",message).bind("id",occurrence.id()).bind("token",occurrence.leaseToken()).fetch().rowsUpdated().flatMap(n->n==1?db.sql("UPDATE outreach_schedules SET enabled=false,message=:message,lock_version=lock_version+1,updated_at=now() WHERE id=:id").bind("message",message).bind("id",schedule.id()).fetch().rowsUpdated().then():Mono.empty());
    }
    private Schedule schedule(Row r){return new Schedule(r.get("id",UUID.class),r.get("actor_id",UUID.class),r.get("payload_hash",byte[].class),r.get("name",String.class),r.get("purpose",String.class),((Number)r.get("max_papers")).intValue(),r.get("smtp_account_id",UUID.class),r.get("template_id",UUID.class),r.get("directions",String.class),r.get("request_context",String.class),r.get("config_snapshot",String.class),Boolean.TRUE.equals(r.get("accept_deliverability_risk",Boolean.class)),Boolean.TRUE.equals(r.get("enabled",Boolean.class)),r.get("next_run_at",Instant.class),r.get("last_occurrence_at",Instant.class),r.get("last_run_id",UUID.class),r.get("message",String.class),((Number)r.get("lock_version")).longValue(),r.get("created_at",Instant.class),r.get("updated_at",Instant.class));}
    private Occurrence occurrence(Row r){return new Occurrence(r.get("id",UUID.class),r.get("schedule_id",UUID.class),r.get("occurrence_key",String.class),r.get("status",String.class),r.get("window_from",LocalDate.class),r.get("window_to",LocalDate.class),r.get("lease_token",UUID.class),r.get("lease_until",Instant.class),r.get("discovery",String.class),r.get("bulk_command",String.class),r.get("discovery_attempts",Integer.class));}
}
