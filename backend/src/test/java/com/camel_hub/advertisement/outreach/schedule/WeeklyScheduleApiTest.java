package com.camel_hub.advertisement.outreach.schedule;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
class WeeklyScheduleApiTest {
    @Test void exposesFrozenViewAndOptimisticPauseResumeEndpoints(){
        UUID id=UUID.randomUUID();var service=mock(WeeklyScheduleService.class);
        var view=new WeeklyScheduleView(id,"Weekly","Research",5000,UUID.randomUUID(),UUID.randomUUID(),List.of("Agent"),true,"Asia/Shanghai","MONDAY","09:00",Instant.parse("2026-10-12T01:00:00Z"),null,"Scheduled",true,3,Instant.now(),Instant.now(),List.of("PAUSE"));
        when(service.get(id)).thenReturn(Mono.just(view));
        when(service.changeEnabled(eq(id),any(),eq(3L),eq(false),any())).thenReturn(Mono.just(view));
        when(service.changeEnabled(eq(id),any(),eq(3L),eq(true),any())).thenReturn(Mono.just(view));
        UUID actor=UUID.randomUUID();
        org.springframework.web.server.WebFilter principal=(exchange,chain)->chain.filter(exchange.mutate().principal(Mono.just(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor.toString(),"n/a"))).build());
        var client=WebTestClient.bindToController(new WeeklyScheduleController(service)).webFilter(principal).build();
        client.get().uri("/api/v1/outreach-schedules/"+id).exchange().expectStatus().isOk().expectBody().jsonPath("$.timezone").isEqualTo("Asia/Shanghai").jsonPath("$.maxPapers").isEqualTo(5000).jsonPath("$.localTime").isEqualTo("09:00");
        for(String action:List.of("pause","resume"))client.post().uri("/api/v1/outreach-schedules/"+id+"/"+action).header("Content-Type","application/json").bodyValue("{\"expectedVersion\":3}").exchange().expectStatus().isOk().expectBody().jsonPath("$.lockVersion").isEqualTo(3);
        client.post().uri("/api/v1/outreach-schedules/"+id+"/pause").header("Content-Type","application/json").bodyValue("{}").exchange().expectStatus().isBadRequest();
    }
}
