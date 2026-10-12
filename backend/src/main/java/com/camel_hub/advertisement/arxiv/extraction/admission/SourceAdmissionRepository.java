package com.camel_hub.advertisement.arxiv.extraction.admission;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionDtos.*;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceCommandIdentity.*;

/** Durable issuer journal. No upsert, backfill, history deletion or autonomous transaction. */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public final class SourceAdmissionRepository {
    private final DatabaseClient db;
    public SourceAdmissionRepository(DatabaseClient db) { this.db=db; }

    public Mono<Void> initializeCommand(UUID jobId,UUID messageId,String type,String key,String fingerprint,int targetCount) {
        return Mono.defer(()-> {
            new Scope(key,jobId,messageId,type,fingerprint); require(targetCount>=1 && targetCount<=100);
            return db.sql("""
                INSERT INTO source_command_authorities(job_id,message_id,command_type,key_digest,payload_fingerprint,target_count)
                VALUES(:job,:message,:type,:key,:fingerprint,:count)
                """).bind("job",jobId).bind("message",messageId).bind("type",type).bind("key",sha256(utf8(key)))
                    .bind("fingerprint",fingerprint).bind("count",targetCount).fetch().rowsUpdated().then();
        });
    }
    Mono<Job> lockJob(UUID id) {
        return db.sql("SELECT status,type FROM jobs WHERE id=:id FOR UPDATE").bind("id",id)
                .map((r,m)->new Job(r.get("status",String.class),r.get("type",String.class))).one();
    }
    Mono<Authority> lockAuthority(UUID id) {
        return db.sql("SELECT * FROM source_command_authorities WHERE job_id=:id FOR UPDATE").bind("id",id)
                .map((r,m)->new Authority(r.get("message_id",UUID.class),r.get("command_type",String.class),r.get("key_digest",String.class),r.get("payload_fingerprint",String.class),r.get("target_count",Integer.class),r.get("sequence",Long.class))).one();
    }
    Mono<Issue> issue(UUID jobId,UUID nonce) { return findIssue("nonce=:value",jobId,nonce); }
    Mono<Issue> latest(UUID jobId,long sequence) { return findIssue("sequence=:value",jobId,sequence); }
    private Mono<Issue> findIssue(String predicate,UUID jobId,Object value) {
        return db.sql("SELECT * FROM source_command_issues WHERE job_id=:job AND "+predicate).bind("job",jobId).bind("value",value)
                .map((r,m)->new Issue(r.get("sequence",Long.class),r.get("nonce",UUID.class),r.get("owner_id",UUID.class),r.get("operation",String.class),r.get("tuple_digest",String.class),r.get("issued_generation",Long.class),r.get("issued_digest",String.class),r.get("target_index",Integer.class),r.get("attempt_number",Integer.class),r.get("token",UUID.class),r.get("issued_deadline",OffsetDateTime.class)==null?null:r.get("issued_deadline",OffsetDateTime.class).toInstant(),r.get("prefix_success",Integer.class),r.get("prefix_skipped",Integer.class),r.get("prefix_failed",Integer.class),r.get("consumption_status",String.class),r.get("consumed_generation",Long.class),r.get("consumed_digest",String.class))).one();
    }
    Mono<Void> append(AdmissionRequest r,SourceAdmissionState next) {
        var s=db.sql("""
            INSERT INTO source_command_issues(job_id,sequence,nonce,owner_id,operation,tuple_digest,expires_at,
              expected_generation,expected_digest,predecessor_sequence,issued_generation,issued_digest,
              target_index,attempt_number,token,issued_deadline,prefix_success,prefix_skipped,prefix_failed,consumption_status)
            VALUES(:job,:sequence,:nonce,:owner,:operation,:tuple,:expires,:expectedGeneration,:expectedDigest,
              :predecessor,:generation,:digest,:index,:attempt,:token,:deadline,:success,:skipped,:failed,:consumption)
            """).bind("job",r.scope().jobId()).bind("sequence",next.receipt().sequence()).bind("nonce",r.nonce()).bind("owner",r.ownerId())
                .bind("operation",r.operation()).bind("tuple",r.tupleDigest()).bind("expires",r.expiresAt()).bind("expectedGeneration",r.expectedGeneration())
                .bind("predecessor",r.predecessorSequence()).bind("generation",next.generation()).bind("digest",next.digest())
                .bind("success",(int)next.success()).bind("skipped",(int)next.skipped()).bind("failed",(int)next.failed());
        s=nullable(s,"expectedDigest",r.expectedStateDigest(),String.class); s=nullable(s,"index",r.index()==null?null:r.index().intValue(),Integer.class);
        s=nullable(s,"attempt",r.attemptNumber()==null?null:r.attemptNumber().intValue(),Integer.class); s=nullable(s,"token",r.token(),UUID.class); s=nullable(s,"deadline",r.deadline(),Instant.class);
        s=nullable(s,"consumption",r.operation().equals("RESERVE")?"BURNED":null,String.class);
        return s.fetch().rowsUpdated().then(db.sql("UPDATE source_command_authorities SET sequence=:next WHERE job_id=:job AND sequence=:previous")
                .bind("next",next.receipt().sequence()).bind("job",r.scope().jobId()).bind("previous",r.predecessorSequence()).fetch().rowsUpdated()
                .flatMap(count->count==1?Mono.empty():Mono.error(new IllegalStateException("ADMISSION_SEQUENCE_CONFLICT"))));
    }
    Mono<Void> confirm(AdmissionRequest request,SourceAdmissionState consumed) {
        return db.sql("""
            UPDATE source_command_issues SET consumption_status='CONFIRMED',consumed_generation=:generation,consumed_digest=:digest
            WHERE job_id=:job AND nonce=:nonce AND sequence=:sequence AND tuple_digest=:tuple AND consumption_status='BURNED'
            """).bind("generation",consumed.generation()).bind("digest",consumed.digest()).bind("job",request.scope().jobId())
                .bind("nonce",request.nonce()).bind("sequence",consumed.receipt().sequence()).bind("tuple",request.tupleDigest()).fetch().rowsUpdated()
                .flatMap(rows->rows==1?Mono.empty():Mono.error(new IllegalStateException("ADMISSION_CONFIRMATION_CONFLICT")));
    }
    private static DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec spec,String name,Object value,Class<?> type) { return value==null?spec.bindNull(name,type):spec.bind(name,value); }
    record Job(String status,String type) { }
    record Authority(UUID messageId,String type,String keyDigest,String fingerprint,int targetCount,long sequence) {
        boolean matches(Scope s) { return messageId.equals(s.commandMessageId()) && type.equals(s.commandType()) && keyDigest.equals(sha256(utf8(s.idempotencyKey()))) && fingerprint.equals(s.payloadFingerprint()); }
    }
    record Issue(long sequence,UUID nonce,UUID owner,String operation,String tupleDigest,long issuedGeneration,String issuedDigest,Integer index,Integer attempt,UUID token,Instant issuedDeadline,int success,int skipped,int failed,String consumptionStatus,Long consumedGeneration,String consumedDigest) {
        Receipt receipt() { return new Receipt(sequence,nonce,owner,token,issuedGeneration,sequence-1); }
    }
}
