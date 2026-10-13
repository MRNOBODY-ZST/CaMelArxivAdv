package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.CampaignConflictException;
import com.camel_hub.advertisement.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.util.*;
import static com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CampaignTemplateMigrationApiTest {
    static final UUID ACTOR=UUID.fromString("10000000-0000-0000-0000-000000000001"), SOURCE=UUID.fromString("20000000-0000-0000-0000-000000000001"), TEMPLATE=UUID.fromString("30000000-0000-0000-0000-000000000001"), VERSION=UUID.fromString("40000000-0000-0000-0000-000000000001"), RECIPIENT=UUID.fromString("50000000-0000-0000-0000-000000000001");
    static final String PREFIX="/api/v1/campaigns/"+SOURCE+"/template-migrations";
    CampaignTemplateMigrationService service;
    WebTestClient client;
    @BeforeEach void setup(){
        service=mock(CampaignTemplateMigrationService.class);
        client=WebTestClient.bindToController(new CampaignTemplateMigrationController(service))
            .controllerAdvice(new GlobalExceptionHandler(null,null))
            .webFilter((exchange,chain)->chain.filter(exchange.mutate().principal(Mono.just(new UsernamePasswordAuthenticationToken(ACTOR.toString(),"n/a"))).build())).build();
    }
    static MigrationResult result(){return new MigrationResult(UUID.fromString("60000000-0000-0000-0000-000000000001"),SOURCE,UUID.fromString("70000000-0000-0000-0000-000000000001"),TEMPLATE,VERSION,1,1,"MIGRATED",List.of());}
    static Map<String,Object> preview(){return Map.of("targetTemplateId",TEMPLATE,"targetTemplateVersionId",VERSION,"recipientIds",List.of(RECIPIENT));}
    static Map<String,Object> command(){return Map.of("expectedLockVersion",3,"targetTemplateId",TEMPLATE,"targetTemplateVersionId",VERSION,"expectedTemplateLockVersion",4,"recipientIds",List.of(RECIPIENT),"expectedMembershipDigest","a".repeat(64),"idempotencyKey","original-key");}
    @Test void fourRoutesBindActorSourceExactPayloadAndExposeOnlyProtocol(){
        var p=new MigrationPreview(SOURCE,3,TEMPLATE,VERSION,4,"a".repeat(64),List.of(RECIPIENT),new SelectionCounts(1,1,0,0),true,List.of());
        when(service.preview(eq(SOURCE),eq(ACTOR),any())).thenReturn(Mono.just(p));
        when(service.migrate(eq(SOURCE),eq(ACTOR),any(),any())).thenReturn(Mono.just(result()));
        when(service.get(SOURCE,result().id(),ACTOR)).thenReturn(Mono.just(result()));
        when(service.getByKey(SOURCE,ACTOR,"original-key")).thenReturn(Mono.just(result()));
        client.post().uri(PREFIX+"/preview").bodyValue(preview()).exchange().expectStatus().isOk().expectBody().jsonPath("$.recipientIds[0]").isEqualTo(RECIPIENT.toString());
        String body=new String(client.post().uri(PREFIX).bodyValue(command()).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("MIGRATED").doesNotContain("@","html","subject","password","hmac","http");
        verify(service).migrate(eq(SOURCE),eq(ACTOR),any(),eq(new MigrationCommand(3,TEMPLATE,VERSION,4,List.of(RECIPIENT),"a".repeat(64),"original-key")));
        client.get().uri(PREFIX+"/"+result().id()).exchange().expectStatus().isOk();
        client.get().uri(PREFIX+"/by-key?key=original-key").exchange().expectStatus().isOk();
    }
    @Test void conflictRemains409AndNeverReturnsSuccess(){
        when(service.migrate(eq(SOURCE),eq(ACTOR),any(),any())).thenReturn(Mono.error(new CampaignConflictException("Membership changed")));
        client.post().uri(PREFIX).bodyValue(command()).exchange().expectStatus().isEqualTo(409);
    }
    @Test void malformedOrMissingScalarUnknownDuplicateFieldsAndDuplicateIdsFailBeforeService(){
        String valid="{\"expectedLockVersion\":3,\"targetTemplateId\":\""+TEMPLATE+"\",\"targetTemplateVersionId\":\""+VERSION+"\",\"expectedTemplateLockVersion\":4,\"recipientIds\":[\""+RECIPIENT+"\"],\"expectedMembershipDigest\":\""+"a".repeat(64)+"\",\"idempotencyKey\":\"original-key\"}";
        for(String body:List.of(valid.replace("\"expectedLockVersion\":3","\"expectedLockVersion\":\"3\""),valid.replace("\"expectedLockVersion\":3,",""),valid.replace("\"expectedLockVersion\":3","\"expectedLockVersion\":3,\"expectedLockVersion\":4"),valid.replace("\"recipientIds\":[\""+RECIPIENT+"\"]","\"recipientIds\":[\""+RECIPIENT+"\",\""+RECIPIENT+"\"]"),valid.replace("original-key","bad key"),valid.replace("\"expectedTemplateLockVersion\":4","\"expectedTemplateLockVersion\":4.0"),valid.replace("\"expectedTemplateLockVersion\":4","\"send\":true,\"expectedTemplateLockVersion\":4"))){
            client.post().uri(PREFIX).header("Content-Type","application/json").bodyValue(body).exchange().expectStatus().isBadRequest();
        }
        client.post().uri(PREFIX+"/preview").bodyValue(Map.of("targetTemplateId",TEMPLATE,"targetTemplateVersionId",VERSION)).exchange().expectStatus().isBadRequest();
        verifyNoInteractions(service);
    }
}
