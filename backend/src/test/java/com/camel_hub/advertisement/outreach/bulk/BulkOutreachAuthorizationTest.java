package com.camel_hub.advertisement.outreach.bulk;

import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Break caught: any one missing permission must prevent the real API boundary from writing. */
class BulkOutreachAuthorizationTest {
    @Test void parentCreationRequiresAllFourPermissionsTogether() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(BulkOutreachController.class);UUID actor=UUID.randomUUID();
            var principal=new UsernamePasswordAuthenticationToken(actor.toString(),"n/a");
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/outreach-bulk-runs"));
            var criteria=new ArxivSearchCriteria(List.of("cs.AI"),null,null,null,null,null,null,"Agent",null,null,null,null,null,null,1,100);
            var request=new BulkOutreachCommand("run","invitation",10000,UUID.randomUUID(),UUID.randomUUID(),List.of(criteria),false,true,"AUTO_OUTREACH_SEND","permission-key-123");
            var permissions=List.of("paper:import","campaign:create","campaign:approve","campaign:send");
            for(String missing:permissions) {
                var authentication=UsernamePasswordAuthenticationToken.authenticated(actor.toString(),"n/a",permissions.stream().filter(permission->!permission.equals(missing)).map(SimpleGrantedAuthority::new).toList());
                assertThatThrownBy(()->controller.create(request,principal,exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block()).isInstanceOf(AccessDeniedException.class);
            }
            var all=UsernamePasswordAuthenticationToken.authenticated(actor.toString(),"n/a",permissions.stream().map(SimpleGrantedAuthority::new).toList());
            assertThat(controller.create(request,principal,exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(all)).block().status()).isEqualTo("ACTIVE");
        }
    }
    @Configuration @EnableReactiveMethodSecurity(proxyTargetClass=true)
    static class Config {
        @Bean BulkOutreachService service() {
            var service=mock(BulkOutreachService.class);
            when(service.create(any(),any(),any())).thenReturn(Mono.just(new BulkOutreachView(UUID.randomUUID(),"run",10000,"ACTIVE","IMPORT","Created",0,0,0,0,0,0,0,0,0,UUID.randomUUID(),UUID.randomUUID(),false,true,0,Instant.now(),Instant.now(),List.of("CANCEL"),List.of())));return service;
        }
        @Bean BulkOutreachController controller(BulkOutreachService service) {return new BulkOutreachController(service);}
    }
}
