package com.camel_hub.advertisement.campaign.migration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*;
import static com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationApiTest.*;

class CampaignTemplateMigrationAuthorizationTest {
    @Test void realMethodSecurityRequiresReadAndCreateButNoSendOrApprove(){
        new ReactiveWebApplicationContextRunner().withUserConfiguration(Config.class)
            .withBean(CampaignTemplateMigrationService.class,()->mock(CampaignTemplateMigrationService.class)).run(app->{
                assertThat(app).hasNotFailed();
                var service=app.getBean(CampaignTemplateMigrationService.class);
                when(service.getByKey(SOURCE,ACTOR,"original-key")).thenReturn(Mono.just(result()));
                when(service.migrate(eq(SOURCE),eq(ACTOR),any(),any())).thenReturn(Mono.just(result()));
                var anonymous=WebTestClient.bindToApplicationContext(app).apply(springSecurity()).build();
                anonymous.get().uri(PREFIX+"/by-key?key=original-key").exchange().expectStatus().isUnauthorized();
                for(String permission:List.of("campaign:read","campaign:create","campaign:send","campaign:approve")){
                    anonymous.mutateWith(mockUser(ACTOR.toString()).authorities(new SimpleGrantedAuthority(permission))).post().uri(PREFIX).bodyValue(command()).exchange().expectStatus().isForbidden();
                }
                anonymous.mutateWith(mockUser(ACTOR.toString()).authorities(new SimpleGrantedAuthority("campaign:read"))).get().uri(PREFIX+"/by-key?key=original-key").exchange().expectStatus().isOk();
                anonymous.mutateWith(mockUser(ACTOR.toString()).authorities(new SimpleGrantedAuthority("campaign:read"),new SimpleGrantedAuthority("campaign:create"))).post().uri(PREFIX).bodyValue(command()).exchange().expectStatus().isOk();
            });
    }
    @Test void serviceRechecksRevocationDisabledGateActorAndSourceInsideTransaction()throws Exception{
        try(var f=new CampaignTemplateMigrationIntegrationTest.Fixture()){
            f.seed();var command=f.command("original-key");var result=f.service.migrate(f.source,f.actor,CampaignTemplateMigrationIntegrationTest.Fixture.CONTEXT,command).block();
            assertThat(f.service(false).getByKey(f.source,f.actor,"original-key").block()).isEqualTo(result);
            assertThatThrownBy(()->f.service(false).migrate(f.source,f.actor,CampaignTemplateMigrationIntegrationTest.Fixture.CONTEXT,command).block()).isInstanceOf(com.camel_hub.advertisement.campaign.CampaignValidationException.class);
            UUID actor2=UUID.randomUUID();
            f.sql("INSERT INTO users(id,username,email,password_hash,display_name,force_password_change) VALUES ('"+actor2+"','"+actor2+"','"+actor2+"@example.test','synthetic','Second',false)");
            f.sql("INSERT INTO user_roles(user_id,role_id) VALUES ('"+actor2+"','"+f.role+"')");
            assertThatThrownBy(()->f.service.getByKey(f.source,actor2,"original-key").block()).isInstanceOf(com.camel_hub.advertisement.campaign.CampaignNotFoundException.class);
            assertThatThrownBy(()->f.service.getByKey(UUID.randomUUID(),f.actor,"original-key").block()).isInstanceOf(com.camel_hub.advertisement.campaign.CampaignNotFoundException.class);
            f.sql("DELETE FROM role_permissions WHERE role_id='"+f.role+"' AND permission_id=(SELECT id FROM permissions WHERE code='campaign:create')");
            assertThatThrownBy(()->f.service.migrate(f.source,f.actor,CampaignTemplateMigrationIntegrationTest.Fixture.CONTEXT,command).block()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(f.service.getByKey(f.source,f.actor,"original-key").block()).isEqualTo(result);
            assertThat(f.value("SELECT after_summary::text FROM audit_logs WHERE action='campaign.template_migration' LIMIT 1")).doesNotContain("@","password","rendered","hmac","http");
            assertThat(f.count("SELECT count(*) FROM campaign_template_migrations")).isEqualTo(1);
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableWebFlux @org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity @EnableReactiveMethodSecurity
    static class Config{
        @Bean CampaignTemplateMigrationController controller(CampaignTemplateMigrationService service){return new CampaignTemplateMigrationController(service);}
        @Bean SecurityWebFilterChain security(ServerHttpSecurity http){return http.csrf(ServerHttpSecurity.CsrfSpec::disable).authorizeExchange(e->e.anyExchange().authenticated()).build();}
    }
}
