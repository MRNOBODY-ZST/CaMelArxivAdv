package com.camel_hub.advertisement.arxiv.extraction.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.*;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceCommandIdentity.*;

/** Strict internal wire protocol; none of these values is a public execution permission. */
public final class SourceAdmissionDtos {
    public static final long MAX_INTEGER = 9_007_199_254_740_991L;
    public static final Set<String> SOURCE_TYPES = Set.of("ARXIV_FETCH_AND_PARSE_SOURCE", "ARXIV_REEXTRACT_CONTACTS");
    public static final Set<String> TERMINALS = Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELED");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Set<String> COMMON = Set.of("operation", "scope", "nonce", "owner_id", "expires_at", "expected_generation", "expected_state_digest", "predecessor_sequence");
    private SourceAdmissionDtos() { }

    public record Scope(String idempotencyKey, UUID jobId, UUID commandMessageId, String commandType, String payloadFingerprint) {
        public Scope {
            require(idempotencyKey != null && safeText(idempotencyKey, 200));
            require(jobId != null && commandMessageId != null && SOURCE_TYPES.contains(commandType)); digest(payloadFingerprint);
        }
    }
    public record Receipt(long sequence, UUID requestNonce, UUID ownerId, UUID token, long reservedGeneration, long predecessorSequence) {
        public Receipt { safe(sequence); safe(reservedGeneration); safe(predecessorSequence); require(sequence > 0 && sequence == predecessorSequence + 1 && requestNonce != null && ownerId != null); }
    }
    public record AdmissionRequest(String operation, Scope scope, UUID nonce, UUID ownerId, Instant expiresAt,
            long expectedGeneration, String expectedStateDigest, long predecessorSequence,
            Long index, Long attemptNumber, UUID token, Instant deadline) {
        public AdmissionRequest {
            require(Set.of("BIND", "RESERVE", "READBACK").contains(operation));
            require(scope != null && nonce != null && ownerId != null && expiresAt != null); time(expiresAt);
            safe(expectedGeneration); safe(predecessorSequence); if (expectedStateDigest != null) digest(expectedStateDigest);
            boolean invocation = index != null || attemptNumber != null || token != null || deadline != null;
            require(!operation.equals("BIND") || !invocation);
            require(!operation.equals("RESERVE") || invocation);
            if (invocation) {
                require(index != null && attemptNumber != null && token != null && deadline != null);
                safe(index); require(attemptNumber >= 1 && attemptNumber <= 6); time(deadline);
                require(expectedStateDigest != null && predecessorSequence > 0);
            }
        }
        public AdmissionRequest readback() { return new AdmissionRequest("READBACK",scope,nonce,ownerId,expiresAt,expectedGeneration,expectedStateDigest,predecessorSequence,index,attemptNumber,token,deadline); }
        public String originalOperation() { return index == null ? "BIND" : "RESERVE"; }
        public String tupleDigest() { ObjectNode value = requestNode(this); value.put("operation", originalOperation()); return sha256(utf8(canonicalJson(value))); }
    }
    public record AdmissionReply(String status, String code, String stateDigest, Receipt receipt, Instant issuedDeadline, String terminalStatus) {
        public AdmissionReply {
            require(Set.of("BOUND","RESERVED","TERMINAL","PAUSED","HELD","UNAVAILABLE").contains(status));
            require(Set.of("OK","AMBIENT_TRANSACTION","REQUEST_EXPIRED","COORDINATION_FAILED","SCOPE_CONFLICT","JOB_TERMINAL","JOB_PAUSED","JOB_NOT_ACTIVE","LEGACY_UNBOUND","UNKNOWN_IDENTITY","UNKNOWN_NONCE","INVOCATION_UNCERTAIN","CORRUPT_STATE","STATE_LOST","PREDECESSOR_CONFLICT","INDEX_CONFLICT","INVALID_LEASE","BURN_PENDING","CAS_CONFLICT").contains(code));
            if(Set.of("BOUND","RESERVED").contains(status)) {
                digest(stateDigest); require(receipt!=null && terminalStatus==null && code.equals("OK"));
                if(status.equals("RESERVED")) require(receipt.token()!=null && issuedDeadline!=null);
                if(issuedDeadline!=null) time(issuedDeadline);
            } else {
                require(stateDigest==null && receipt==null && issuedDeadline==null);
                require(status.equals("TERMINAL")?terminalStatus!=null && TERMINALS.contains(terminalStatus):terminalStatus==null);
            }
        }
        public static AdmissionReply deny(String status, String code) { return new AdmissionReply(status,code,null,null,null,null); }
    }

    public static String stateKey(Scope scope) { return "camel:worker:source-state:v2:{source-admission}:" + sha256(utf8(scope.idempotencyKey())); }
    public static String slotKey(UUID nonce) {
        String digest = sha256(utf8(nonce.toString()));
        return "camel:worker:source-admission:{source-admission}:slot:" + (Long.parseUnsignedLong(digest.substring(0,8),16) % 64);
    }
    public static String replyKey(UUID nonce) { return "camel:worker:source-admission:{source-admission}:reply:"+nonce; }
    static String strictUtf8(java.nio.ByteBuffer bytes) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(bytes).toString();
        } catch(java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("INVALID_UTF8"); }
    }
    public static String time(Instant value) { require(value.getNano()%1_000_000==0); return TIME.format(value); }
    static Instant instant(JsonNode value) {
        String raw=text(value); require(raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"));
        Instant result=Instant.parse(raw); require(time(result).equals(raw)); return result;
    }
    static String text(JsonNode value) { require(value != null && value.isTextual()); utf8(value.textValue()); return value.textValue(); }
    static UUID uuid(JsonNode value) { String raw=text(value); UUID uuid=UUID.fromString(raw); require(uuid.toString().equals(raw)); return uuid; }
    static long number(JsonNode value) { require(value != null && value.isIntegralNumber() && value.canConvertToLong()); long n=value.longValue(); safe(n); return n; }
    static void safe(long n) { require(n>=0 && n<=MAX_INTEGER); }
    static void digest(String raw) { require(raw != null && raw.matches("[0-9a-f]{64}")); }
    static Scope scope(JsonNode n) {
        fields(n,Set.of("idempotency_key","job_id","command_message_id","command_type","payload_fingerprint"));
        return new Scope(text(n.get("idempotency_key")),uuid(n.get("job_id")),uuid(n.get("command_message_id")),text(n.get("command_type")),text(n.get("payload_fingerprint")));
    }
    static ObjectNode scopeNode(Scope scope) {
        ObjectNode n=JSON.createObjectNode(); n.put("idempotency_key",scope.idempotencyKey()); n.put("job_id",scope.jobId().toString()); n.put("command_message_id",scope.commandMessageId().toString()); n.put("command_type",scope.commandType()); n.put("payload_fingerprint",scope.payloadFingerprint()); return n;
    }
    static Receipt receipt(JsonNode n) {
        if(n.isNull()) return null;
        fields(n,Set.of("sequence","request_nonce","owner_id","token","reserved_generation","predecessor_sequence"));
        return new Receipt(number(n.get("sequence")),uuid(n.get("request_nonce")),uuid(n.get("owner_id")),n.get("token").isNull()?null:uuid(n.get("token")),number(n.get("reserved_generation")),number(n.get("predecessor_sequence")));
    }
    static JsonNode receiptNode(Receipt r) {
        if(r==null) return JSON.nullNode();
        ObjectNode n=JSON.createObjectNode(); n.put("sequence",r.sequence()); n.put("request_nonce",r.requestNonce().toString()); n.put("owner_id",r.ownerId().toString()); n.put("token",r.token()==null?null:r.token().toString()); n.put("reserved_generation",r.reservedGeneration()); n.put("predecessor_sequence",r.predecessorSequence()); return n;
    }
    public static AdmissionRequest decodeRequest(String raw) {
        require(utf8(raw).length<=4096); JsonNode n=parse(raw);
        require(n.isObject()); boolean reservation=n.has("index")||n.has("attempt_number")||n.has("token")||n.has("deadline");
        Set<String> expected=new HashSet<>(COMMON); if(reservation) expected.addAll(Set.of("index","attempt_number","token","deadline")); fields(n,expected);
        return new AdmissionRequest(text(n.get("operation")),scope(n.get("scope")),uuid(n.get("nonce")),uuid(n.get("owner_id")),instant(n.get("expires_at")),number(n.get("expected_generation")),n.get("expected_state_digest").isNull()?null:text(n.get("expected_state_digest")),number(n.get("predecessor_sequence")),reservation?number(n.get("index")):null,reservation?number(n.get("attempt_number")):null,reservation?uuid(n.get("token")):null,reservation?instant(n.get("deadline")):null);
    }
    static ObjectNode requestNode(AdmissionRequest r) {
        ObjectNode n=JSON.createObjectNode(); n.put("operation",r.operation()); n.set("scope",scopeNode(r.scope())); n.put("nonce",r.nonce().toString()); n.put("owner_id",r.ownerId().toString()); n.put("expires_at",time(r.expiresAt())); n.put("expected_generation",r.expectedGeneration()); n.put("expected_state_digest",r.expectedStateDigest()); n.put("predecessor_sequence",r.predecessorSequence());
        if(r.index()!=null) { n.put("index",r.index()); n.put("attempt_number",r.attemptNumber()); n.put("token",r.token().toString()); n.put("deadline",time(r.deadline())); } return n;
    }
    public static String encodeRequest(AdmissionRequest r) { return canonicalJson(requestNode(r)); }
    public static String encodeReply(AdmissionReply r) {
        ObjectNode n=JSON.createObjectNode(); n.put("status",r.status()); n.put("code",r.code()); n.put("state_digest",r.stateDigest()); n.set("receipt",receiptNode(r.receipt())); n.put("issued_deadline",r.issuedDeadline()==null?null:time(r.issuedDeadline())); n.put("terminal_status",r.terminalStatus()); return canonicalJson(n);
    }
    public static AdmissionReply decodeReply(String raw) {
        require(utf8(raw).length<=4096); JsonNode n=parse(raw);
        fields(n,Set.of("status","code","state_digest","receipt","issued_deadline","terminal_status"));
        return new AdmissionReply(text(n.get("status")),text(n.get("code")),n.get("state_digest").isNull()?null:text(n.get("state_digest")),receipt(n.get("receipt")),n.get("issued_deadline").isNull()?null:instant(n.get("issued_deadline")),n.get("terminal_status").isNull()?null:text(n.get("terminal_status")));
    }
    /** Sorted ASCII schema names, raw valid Unicode values, explicit null/default values. */
    public static String canonicalJson(JsonNode n) {
        if(n.isObject()) {
            var names=new TreeSet<String>(); n.fieldNames().forEachRemaining(names::add);
            var parts=new ArrayList<String>(); for(String name:names) parts.add(canonicalJson(JSON.getNodeFactory().textNode(name))+":"+canonicalJson(n.get(name)));
            return "{"+String.join(",",parts)+"}";
        }
        if(n.isArray()) { var parts=new ArrayList<String>(); n.forEach(v->parts.add(canonicalJson(v))); return "["+String.join(",",parts)+"]"; }
        if(n.isTextual()) utf8(n.textValue());
        try { return JSON.writeValueAsString(n); } catch(Exception e) { throw new IllegalArgumentException("Invalid source JSON"); }
    }
}
