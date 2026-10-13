package com.camel_hub.advertisement.outreach.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Catches final-controller CGLIB startup failure and unguarded read methods. */
class WeeklyScheduleMethodSecurityTest {
    @Configuration(proxyBeanMethods=false)
    @EnableReactiveMethodSecurity
    static class SecurityContext {
        @Bean WeeklyScheduleService service(){return mock(WeeklyScheduleService.class);}
        @Bean WeeklyScheduleController controller(WeeklyScheduleService service){return new WeeklyScheduleController(service);}
    }
    @Test void realReactiveMethodSecurityStartsAndGuardsControllerReads(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().setActiveProfiles("api");context.register(SecurityContext.class);context.refresh();
            var service=context.getBean(WeeklyScheduleService.class);var controller=context.getBean(WeeklyScheduleController.class);
            UUID id=UUID.randomUUID();var view=new WeeklyScheduleView(id,"Weekly","Research",5000,UUID.randomUUID(),UUID.randomUUID(),List.of("Agent"),true,"Asia/Shanghai","MONDAY","09:00",Instant.parse("2026-10-12T01:00:00Z"),null,"Scheduled",false,0,Instant.now(),Instant.now(),List.of("PAUSE"));
            when(service.get(id)).thenReturn(Mono.just(view));
            var denied=new UsernamePasswordAuthenticationToken("reader","n/a",List.of());
            assertThatThrownBy(()->controller.get(id).contextWrite(ReactiveSecurityContextHolder.withAuthentication(denied)).block()).isInstanceOf(AccessDeniedException.class);
            var allowed=new UsernamePasswordAuthenticationToken("reader","n/a",List.of(new SimpleGrantedAuthority("campaign:read")));
            assertThat(controller.get(id).contextWrite(ReactiveSecurityContextHolder.withAuthentication(allowed)).block()).isEqualTo(view);
        }
    }
}
