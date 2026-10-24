package com.camel_hub.advertisement.arxiv.extraction.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceCommandIdentity.*;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionDtos.*;

/** Strict shared v2 storage codec and issuer-side lineage validator; not a worker runner. */
public final class SourceAdmissionState {
    static final Set<String> UPSTREAM=Set.of("RATE_LIMITED","UNAVAILABLE","NETWORK","TIMEOUT");
    static final Set<String> HOLDS=Set.of("LEGACY_UNBOUND","SCOPE_CONFLICT","CORRUPT_STATE","INVOCATION_UNCERTAIN","STATE_LOST");
    private final ObjectNode node;
    private SourceAdmissionState(ObjectNode node) { this.node=node; }
    public static SourceAdmissionState decode(String raw) {
        require(utf8(raw).length<=1024*1024); JsonNode n=parse(raw);
        fields(n,Set.of("version","scope","generation","progress","phase","invocation","result","reason","terminal_status","authority"));
        require(number(n.get("version"))==2); SourceAdmissionDtos.scope(n.get("scope")); number(n.get("generation"));
        JsonNode p=n.get("progress"); fields(p,Set.of("next_index","success","skipped","failed"));
        long index=number(p.get("next_index")), success=number(p.get("success")), skipped=number(p.get("skipped")), failed=number(p.get("failed"));
        require(success+skipped+failed==index && index<=100);
        Receipt receipt=SourceAdmissionDtos.receipt(n.get("authority"));
        if(receipt!=null) require(number(n.get("generation"))>=receipt.reservedGeneration());
        String phase=text(n.get("phase"));
        require(Set.of("READY","RESERVED","RESULT_READY","RETRY_READY","EXHAUSTED","HELD","TERMINAL_PENDING","TERMINAL").contains(phase));
        JsonNode invocation=n.get("invocation"), result=n.get("result"), reason=n.get("reason"), terminal=n.get("terminal_status");
        if(!invocation.isNull()) {
            fields(invocation,Set.of("token","index","attempt_number","owner_id","deadline"));
            uuid(invocation.get("token")); uuid(invocation.get("owner_id")); instant(invocation.get("deadline"));
            require(number(invocation.get("index"))==index && number(invocation.get("attempt_number"))>=1 && number(invocation.get("attempt_number"))<=6);
            require(receipt!=null && uuid(invocation.get("token")).equals(receipt.token()) && uuid(invocation.get("owner_id")).equals(receipt.ownerId()));
        }
        if(!reason.isNull()) require(UPSTREAM.contains(text(reason)) || HOLDS.contains(text(reason)));
        if(!terminal.isNull()) require(TERMINALS.contains(text(terminal)));
        switch(phase) {
            case "READY" -> require(invocation.isNull() && result.isNull() && reason.isNull() && terminal.isNull());
            case "RESERVED" -> require(!invocation.isNull() && result.isNull() && reason.isNull() && terminal.isNull());
            case "RESULT_READY" -> { require(!invocation.isNull() && !result.isNull() && reason.isNull() && terminal.isNull()); validateResult(result); }
            case "RETRY_READY","EXHAUSTED" -> require(!invocation.isNull() && result.isNull() && UPSTREAM.contains(reason.asText()) && terminal.isNull() && (phase.equals("EXHAUSTED") ? number(invocation.get("attempt_number"))==6 : number(invocation.get("attempt_number"))<6));
            case "HELD" -> require(result.isNull() && HOLDS.contains(reason.asText()) && terminal.isNull());
            case "TERMINAL_PENDING","TERMINAL" -> require(invocation.isNull() && result.isNull() && reason.isNull() && !terminal.isNull());
            default -> throw new IllegalArgumentException("Invalid source phase");
        }
        require(canonicalJson(n).equals(raw));
        return new SourceAdmissionState((ObjectNode)n);
    }
    public String raw() { return canonicalJson(node); }
    public String digest() { return sha256(utf8(raw())); }
    public Scope scope() { return SourceAdmissionDtos.scope(node.get("scope")); }
    public Receipt receipt() { return SourceAdmissionDtos.receipt(node.get("authority")); }
    public long generation() { return number(node.get("generation")); }
    public long nextIndex() { return number(node.get("progress").get("next_index")); }
    public long success() { return number(node.get("progress").get("success")); }
    public long skipped() { return number(node.get("progress").get("skipped")); }
    public long failed() { return number(node.get("progress").get("failed")); }
    public String phase() { return text(node.get("phase")); }
    public String reason() { return node.get("reason").isNull()?null:text(node.get("reason")); }
    public long attempt() { return number(node.get("invocation").get("attempt_number")); }
    public Instant deadline() { return instant(node.get("invocation").get("deadline")); }

    static SourceAdmissionState initial(AdmissionRequest request) {
        ObjectNode n=JSON.createObjectNode(); n.put("version",2); n.set("scope",scopeNode(request.scope())); n.put("generation",0);
        ObjectNode p=n.putObject("progress"); p.put("next_index",0); p.put("success",0); p.put("skipped",0); p.put("failed",0);
        n.put("phase","READY"); n.putNull("invocation"); n.putNull("result"); n.putNull("reason"); n.putNull("terminal_status");
        n.set("authority",receiptNode(new Receipt(1,request.nonce(),request.ownerId(),null,0,0)));
        return decode(canonicalJson(n));
    }
    SourceAdmissionState reserve(AdmissionRequest r, long sequence) {
        ObjectNode n=node.deepCopy(); n.put("generation",generation()+1); n.put("phase","RESERVED"); n.putNull("reason");
        ObjectNode i=n.putObject("invocation"); i.put("token",r.token().toString()); i.put("owner_id",r.ownerId().toString()); i.put("index",r.index()); i.put("attempt_number",r.attemptNumber()); i.put("deadline",time(r.deadline()));
        n.set("authority",receiptNode(new Receipt(sequence,r.nonce(),r.ownerId(),r.token(),generation()+1,r.predecessorSequence())));
        return decode(canonicalJson(n));
    }
    SourceAdmissionState consumed() {
        require(phase().equals("RESERVED") && generation()==receipt().reservedGeneration());
        ObjectNode n=node.deepCopy(); n.put("generation",generation()+1); return decode(canonicalJson(n));
    }

    /** Compare the complete committed receipt and allowed local phase/prefix transformations. */
    boolean descendsFrom(SourceAdmissionRepository.Issue issue, int targetCount) {
        if(!Objects.equals(receipt(),issue.receipt()) || nextIndex()>targetCount || generation()<issue.issuedGeneration()) return false;
        if(issue.operation().equals("BIND")) return nextIndex()==0 && success()==0 && skipped()==0 && failed()==0
                && (phase().equals("READY") ? generation()==0 && digest().equals(issue.issuedDigest())
                    : generation()>0 && Set.of("HELD","TERMINAL_PENDING","TERMINAL").contains(phase()));
        if(!"CONFIRMED".equals(issue.consumptionStatus()) || issue.consumedGeneration()==null || generation()<issue.consumedGeneration()) return false;
        if(generation()==issue.consumedGeneration() && !digest().equals(issue.consumedDigest())) return false;
        long delta=nextIndex()-issue.index();
        if(delta==1 && Set.of("READY","HELD","TERMINAL_PENDING","TERMINAL").contains(phase())) {
            long ds=success()-issue.success(), dk=skipped()-issue.skipped(), df=failed()-issue.failed();
            return generation()>=issue.issuedGeneration()+3 && ds>=0 && dk>=0 && df>=0 && ds+dk+df==1;
        }
        if(delta!=0 || success()!=issue.success() || skipped()!=issue.skipped() || failed()!=issue.failed()) return false;
        if(Set.of("HELD","TERMINAL_PENDING","TERMINAL").contains(phase())) return generation()>issue.issuedGeneration();
        if(!Set.of("RESERVED","RETRY_READY","EXHAUSTED","RESULT_READY").contains(phase()) || attempt()!=issue.attempt()) return false;
        if(deadline().isBefore(issue.issuedDeadline())) return false;
        return phase().equals("RESERVED") || generation()>=issue.issuedGeneration()+2;
    }

    private static void booleanField(JsonNode n) { require(n.isBoolean()); }
    private static String safe(JsonNode n,int max,boolean optional,boolean emailForbidden) {
        if(optional && n.isNull()) return null;
        String s=text(n); require(safeText(s,max));
        if(emailForbidden) require(!Normalizer.normalize(s,Normalizer.Form.NFKC).contains("@")); return s;
    }
    private static void array(JsonNode n,int min,int max) { require(n.isArray() && n.size()>=min && n.size()<=max); }
    private static void validateResult(JsonNode r) {
        fields(r,Set.of("paper_id","arxiv_id","parser_version","status","cleanup_confirmed","source_format","archive_size_bytes","extracted_size_bytes","files_inspected","duration_ms","document_class","authors","contacts","error_code","error_summary"));
        uuid(r.get("paper_id")); require(text(r.get("arxiv_id")).matches(ARXIV)); require(text(r.get("parser_version")).matches("[A-Za-z0-9._-]{1,50}"));
        String status=text(r.get("status")); require(Set.of("SUCCEEDED","PARTIALLY_SUCCEEDED","FAILED","SECURITY_REJECTED","SOURCE_UNAVAILABLE").contains(status));
        booleanField(r.get("cleanup_confirmed")); require(r.get("cleanup_confirmed").booleanValue());
        String format=safe(r.get("source_format"),50,true,true); safe(r.get("document_class"),100,true,true); safe(r.get("error_summary"),500,true,true);
        number(r.get("archive_size_bytes")); number(r.get("extracted_size_bytes")); number(r.get("duration_ms")); require(number(r.get("files_inspected"))<=5000);
        if(!r.get("error_code").isNull()) require(text(r.get("error_code")).matches("[A-Z0-9_]{1,80}"));
        if(Set.of("SUCCEEDED","PARTIALLY_SUCCEEDED").contains(status)) require(format!=null && number(r.get("files_inspected"))>=1 && r.get("error_code").isNull());
        else require(!r.get("error_code").isNull());
        array(r.get("authors"),0,500); Set<Long> orders=new HashSet<>();
        for(JsonNode a:r.get("authors")) {
            fields(a,Set.of("order","name","affiliations","corresponding")); long order=number(a.get("order")); require(order>=1 && order<=500 && orders.add(order));
            safe(a.get("name"),300,false,true); booleanField(a.get("corresponding")); array(a.get("affiliations"),0,100); for(JsonNode affiliation:a.get("affiliations")) safe(affiliation,2000,false,true);
        }
        array(r.get("contacts"),0,500);
        for(JsonNode c:r.get("contacts")) {
            fields(c,Set.of("normalized_email","display_email","domain","syntax_valid","example_address","author_order","confidence","corresponding","evidence"));
            String address=text(c.get("normalized_email")), display=safe(c.get("display_email"),320,false,false), domain=text(c.get("domain"));
            require(address.length()>=3 && address.length()<=320 && display.codePointCount(0,display.length())>=3 && address.equals(address.toLowerCase(Locale.ROOT)) && address.indexOf('@')>0 && address.indexOf('@')==address.lastIndexOf('@'));
            String local=address.substring(0,address.indexOf('@')); require(local.matches("[A-Za-z0-9.!#$%&'*+/=?^_`|~-]{1,64}") && !local.startsWith(".") && !local.endsWith(".") && !local.contains(".."));
            require(domain.equals(address.substring(address.indexOf('@')+1)) && domain.length()<=253 && domain.contains("."));
            String[] labels=domain.split("\\.",-1); for(String label:labels) { require(label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")); if(label.startsWith("xn--")) require(!java.net.IDN.toUnicode(label).equals(label) && java.net.IDN.toASCII(java.net.IDN.toUnicode(label)).equals(label)); }
            require(labels[labels.length-1].length()>=2 && labels[labels.length-1].matches(".*[a-z].*"));
            booleanField(c.get("syntax_valid")); require(c.get("syntax_valid").booleanValue()); booleanField(c.get("example_address")); booleanField(c.get("corresponding"));
            if(!c.get("author_order").isNull()) require(orders.contains(number(c.get("author_order"))));
            require(Set.of("HIGH","MEDIUM","LOW","UNMAPPED").contains(text(c.get("confidence")))); array(c.get("evidence"),1,20);
            for(JsonNode e:c.get("evidence")) {
                fields(e,Set.of("source_relative_path","rule_name","line_number","logical_location","masked_context"));
                String path=safe(e.get("source_relative_path"),500,false,true); require(!path.startsWith("/") && !path.contains("\\"));
                for(String part:path.split("/",-1)) require(!Set.of("",".","..").contains(part));
                require(text(e.get("rule_name")).matches("[A-Z0-9_]{1,120}") && text(e.get("logical_location")).matches("[A-Z0-9_]{1,120}"));
                if(!e.get("line_number").isNull()) require(number(e.get("line_number"))>=1);
                String context=SourceEvidenceCaseFold.fold(safe(e.get("masked_context"),600,false,true));
                require(!context.contains(SourceEvidenceCaseFold.fold(address)) && !context.contains(SourceEvidenceCaseFold.fold(display)));
            }
        }
        require(utf8(canonicalJson(r)).length<=768*1024 && utf8(canonicalJson(camelCase(r))).length<=768*1024);
    }
    private static JsonNode camelCase(JsonNode n) {
        if(n.isArray()) { var a=JSON.createArrayNode(); n.forEach(v->a.add(camelCase(v))); return a; }
        if(!n.isObject()) return n;
        var result=JSON.createObjectNode(); n.fields().forEachRemaining(entry->{String[] parts=entry.getKey().split("_"); StringBuilder key=new StringBuilder(parts[0]); for(int i=1;i<parts.length;i++) key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1)); result.set(key.toString(),camelCase(entry.getValue()));}); return result;
    }
}
