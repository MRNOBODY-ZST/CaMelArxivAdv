package com.camel_hub.advertisement.outreach;

import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutreachAuthorizationTest {
    @Test void actualCreateBoundaryRequiresAllFourPermissionsTogether() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(OutreachRunController.class);
            UUID actor=UUID.randomUUID();
            var principal=new UsernamePasswordAuthenticationToken(actor.toString(),"n/a");
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/outreach-runs"));
            var criteria=new ArxivSearchCriteria(List.of("cs.AI"),null,null,null,null,null,null,null,null,null,null,null,null,null,1,100);
            var request=new OutreachDtos.CreateRequest("run","EMAILS",1,UUID.randomUUID(),UUID.randomUUID(),"invitation",criteria,"AUTO_OUTREACH_SEND","permission-key-123");
            var all=List.of("paper:import","campaign:create","campaign:approve","campaign:send");
            for(String missing:all) {
                var authentication=UsernamePasswordAuthenticationToken.authenticated(actor.toString(),"n/a",all.stream()
                        .filter(permission->!permission.equals(missing)).map(SimpleGrantedAuthority::new).toList());
                assertThatThrownBy(()->controller.create(request,principal,exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block())
                        .isInstanceOf(AccessDeniedException.class);
            }
            var authentication=UsernamePasswordAuthenticationToken.authenticated(actor.toString(),"n/a",all.stream().map(SimpleGrantedAuthority::new).toList());
            assertThat(controller.create(request,principal,exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block().status()).isEqualTo("ACTIVE");
        }
    }
    @Configuration @EnableReactiveMethodSecurity(proxyTargetClass=true)
    static class Config {
        @Bean OutreachRunService service() {
            var service=mock(OutreachRunService.class);
            when(service.create(any(),any(),any())).thenReturn(Mono.just(new OutreachDtos.RunView(UUID.randomUUID(),"run","EMAILS",1,"ACTIVE","IMPORT","Created",0,0,0,0,0,0,0,null,null,null,UUID.randomUUID(),UUID.randomUUID(),0,Instant.now(),Instant.now(),List.of("CANCEL"))));
            return service;
        }
        @Bean OutreachRunController controller(OutreachRunService service) {return new OutreachRunController(service);}
    }
}
