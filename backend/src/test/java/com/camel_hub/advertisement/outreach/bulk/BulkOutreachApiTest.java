package com.camel_hub.advertisement.outreach.bulk;

import com.camel_hub.advertisement.common.api.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Breaks caught: coercion changes confirmed target, cancel silently uses version zero. */
class BulkOutreachApiTest {
    private final UUID actor=UUID.randomUUID(),id=UUID.randomUUID(),smtp=UUID.randomUUID(),template=UUID.randomUUID();
    private BulkOutreachService service;
    private WebTestClient client;
    @BeforeEach void setup() {
        service=mock(BulkOutreachService.class);
        WebFilter principal=(exchange,chain)->chain.filter(exchange.mutate().principal(Mono.just(new UsernamePasswordAuthenticationToken(actor.toString(),"n/a"))).build());
        client=WebTestClient.bindToController(new BulkOutreachController(service)).controllerAdvice(new GlobalExceptionHandler(null,null)).webFilter(principal).build();
        when(service.create(eq(actor),any(),any())).thenReturn(Mono.just(view()));
    }
    @Test void createReturnsTruthfulCountersAndChildArray() {
        when(service.list(1,20)).thenReturn(Mono.just(PageResponse.of(List.of(view()),1,20,1)));
        client.post().uri("/api/v1/outreach-bulk-runs").header("Content-Type","application/json").bodyValue(payload("10000"))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.targetPapers").isEqualTo(10000).jsonPath("$.smtpAccepted").isEqualTo(0).jsonPath("$.excludedCount").isEqualTo(0).jsonPath("$.children").isArray();
        client.get().uri("/api/v1/outreach-bulk-runs").exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].id").isEqualTo(id.toString());
    }
    @Test void rejectsUnknownDuplicateAndCoercedCreatePayloads() {
        for(String invalid:List.of(payload("1.5"),payload("\"100\""),payload("null"),payload("100").replace("\"targetPapers\":100","\"targetPapers\":100,\"targetPapers\":2"),payload("100").replace("\"name\":\"bulk\"","\"name\":\"bulk\",\"secret\":1")))
            client.post().uri("/api/v1/outreach-bulk-runs").header("Content-Type","application/json").bodyValue(invalid).exchange().expectStatus().isBadRequest();
    }
    @Test void cancellationRequiresPresentNonnegativeExpectedVersion() {
        when(service.cancel(eq(id),eq(actor),anyLong(),any())).thenReturn(Mono.just(view()));
        for(String invalid:List.of("{}","{\"expectedVersion\":null}","{\"expectedVersion\":-1}","{\"expectedVersion\":0.1}","{\"expectedVersion\":\"0\"}"))
            client.post().uri("/api/v1/outreach-bulk-runs/{id}/cancel",id).header("Content-Type","application/json").bodyValue(invalid).exchange().expectStatus().isBadRequest();
        client.post().uri("/api/v1/outreach-bulk-runs/{id}/cancel",id).header("Content-Type","application/json").bodyValue("{\"expectedVersion\":0}").exchange().expectStatus().isOk();
    }
    private String payload(String count) {return "{\"name\":\"bulk\",\"purpose\":\"invitation\",\"targetPapers\":"+count+",\"smtpAccountId\":\""+smtp+"\",\"templateId\":\""+template+"\",\"searchPlan\":[{\"abstractKeywords\":\"Agent\",\"page\":1,\"pageSize\":100}],\"incremental\":false,\"acceptDeliverabilityRisk\":true,\"confirmation\":\"AUTO_OUTREACH_SEND\",\"idempotencyKey\":\"bulk-api-key-123\"}";}
    private BulkOutreachView view() {return new BulkOutreachView(id,"bulk",10000,"ACTIVE","IMPORT","Created",0,0,0,0,0,0,0,0,0,smtp,template,false,true,0,Instant.now(),Instant.now(),List.of("CANCEL"),List.of());}
}
