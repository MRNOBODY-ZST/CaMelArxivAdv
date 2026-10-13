package com.camel_hub.advertisement.outreach;

import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import com.camel_hub.advertisement.common.api.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutreachApiTest {
    private final UUID actor=UUID.randomUUID(),id=UUID.randomUUID(),smtp=UUID.randomUUID(),template=UUID.randomUUID();
    private OutreachRunService service;
    private WebTestClient client;
    @BeforeEach void setup() {
        service=mock(OutreachRunService.class);
        WebFilter principal=(exchange,chain)->chain.filter(exchange.mutate().principal(Mono.just(
                new UsernamePasswordAuthenticationToken(actor.toString(),"n/a"))).build());
        client=WebTestClient.bindToController(new OutreachRunController(service)).controllerAdvice(new GlobalExceptionHandler(null,null)).webFilter(principal).build();
        when(service.create(eq(actor),any(),any())).thenReturn(Mono.just(view()));
    }
    @Test void rejectsCoercedDuplicateAndUnknownCreateFieldsBeforeAnyMutation() {
        // If Jackson truncates a float, accepts hidden fields or overwrites a duplicate,
        // a one-click send may target a different amount/configuration than confirmed.
        for(String invalid:List.of(payload("1.5"),payload("\"1\""),payload("true"),payload("null"),
                payload("1").replace("\"targetCount\":1","\"targetCount\":1,\"targetCount\":2"),
                payload("1").replace("\"name\":\"run\"","\"name\":\"run\",\"secret\":\"ignored\""))) {
            client.post().uri("/api/v1/outreach-runs").header("Content-Type","application/json").bodyValue(invalid)
                    .exchange().expectStatus().isBadRequest();
        }
    }
    @Test void createAndListReturnDocumentedDirectJsonShapes() {
        when(service.list(1,20)).thenReturn(Mono.just(PageResponse.of(List.of(view()),1,20,1)));
        client.post().uri("/api/v1/outreach-runs").header("Content-Type","application/json").bodyValue(payload("1"))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.stage").isEqualTo("IMPORT").jsonPath("$.selectedCount").isEqualTo(0);
        client.get().uri("/api/v1/outreach-runs").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.total").isEqualTo(1).jsonPath("$.items[0].id").isEqualTo(id.toString());
    }
    @Test void cancelRequiresNonnegativePresentLockVersion() {
        when(service.cancel(eq(id),eq(actor),anyLong(),any())).thenReturn(Mono.just(view()));
        for(String invalid:List.of("{}","{\"expectedLockVersion\":null}","{\"expectedLockVersion\":-1}",
                "{\"expectedLockVersion\":0.1}","{\"expectedLockVersion\":\"0\"}","{\"expectedLockVersion\":0,\"expectedLockVersion\":1}"))
            client.post().uri("/api/v1/outreach-runs/{id}/cancel",id).header("Content-Type","application/json").bodyValue(invalid)
                    .exchange().expectStatus().isBadRequest();
    }
    private String payload(String count) {
        return "{\"name\":\"run\",\"targetMode\":\"EMAILS\",\"targetCount\":"+count+",\"smtpAccountId\":\""+smtp+"\",\"templateId\":\""+template+"\",\"purpose\":\"invitation\",\"criteria\":{\"categoryIds\":[\"cs.AI\"],\"page\":1,\"pageSize\":100},\"confirmation\":\"AUTO_OUTREACH_SEND\",\"idempotencyKey\":\"api-test-key-123\"}";
    }
    private OutreachDtos.RunView view() { return new OutreachDtos.RunView(id,"run","EMAILS",1,"ACTIVE","IMPORT","Created",0,0,0,0,0,0,0,null,null,null,smtp,template,0,Instant.now(),Instant.now(),List.of("CANCEL")); }
}
