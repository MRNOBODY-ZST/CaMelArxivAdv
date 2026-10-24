package com.camel_hub.advertisement.system.personalization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*;
import static org.assertj.core.api.Assertions.*;

class PersonalizationApiTest {
    @Test void adminResponseRedactsSecretsAndInternalRouteRequiresServiceTokenNotUserIdentity() {
        new ReactiveWebApplicationContextRunner().withUserConfiguration(Config.class, PersonalizationInternalSecurity.class)
            .withPropertyValues("app.personalization-settings.service-token=service-token-for-tests-at-least-32-characters")
            .run(app -> {
                assertThat(app).hasNotFailed();
                var client = WebTestClient.bindToApplicationContext(app).apply(springSecurity()).build();
                client.get().uri("/api/v1/system/personalization").exchange().expectStatus().isUnauthorized();
                client.mutateWith(mockUser("ordinary")).get().uri("/api/v1/system/personalization").exchange().expectStatus().isForbidden();
                String body = "{\"version\":0,\"enabled\":true,\"provider\":\"openai\",\"model\":\"m\",\"apiOrigin\":\"https://api.example.com\",\"authScheme\":\"bearer\",\"apiKey\":\"private-key\"}";
                client.mutateWith(mockUser("ordinary")).put().uri("/api/v1/system/personalization").header("Content-Type", "application/json").bodyValue(body).exchange().expectStatus().isForbidden();
                client.mutateWith(mockUser("ordinary")).post().uri("/api/v1/system/personalization/test").header("Content-Type", "application/json").bodyValue(body).exchange().expectStatus().isForbidden();
                client.mutateWith(mockUser("admin").authorities(new SimpleGrantedAuthority("system:manage")))
                    .get().uri("/api/v1/system/personalization").exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.apiKey").doesNotExist().jsonPath("$.keyConfigured").isEqualTo(true)
                    .consumeWith(r -> assertThat(new String(r.getResponseBody())).doesNotContain("env-secret"));
                String path = "/internal/personalization/jobs/00000000-0000-0000-0000-000000000001/configuration";
                client.get().uri(path).exchange().expectStatus().isUnauthorized();
                client.get().uri(path).header("Authorization", "Bearer wrong").exchange().expectStatus().isUnauthorized();
                client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("system:manage"))).get().uri(path).exchange().expectStatus().isUnauthorized();
                client.get().uri(path).header("Authorization", "Bearer service-token-for-tests-at-least-32-characters")
                    .exchange().expectStatus().isNotFound().expectHeader().valueEquals("Cache-Control", "no-store");
                org.mockito.Mockito.doReturn(reactor.core.publisher.Mono.just(PersonalizationSettingsServiceTest.fallback()))
                    .when(app.getBean(PersonalizationSettingsService.class)).jobConfiguration(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
                client.get().uri(path).header("Authorization", "Bearer service-token-for-tests-at-least-32-characters")
                    .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectBody().jsonPath("$.apiKey").isEqualTo("env-secret").jsonPath("$.apiBaseUrl").isEqualTo("https://api.example.com/v1")
                    .jsonPath("$.apiAuthScheme").isEqualTo("bearer");
            });
    }
    @Configuration(proxyBeanMethods=false) @EnableWebFlux
    @org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
    @EnableReactiveMethodSecurity
    static class Config {
        @Bean PersonalizationSettingsService service() { return org.mockito.Mockito.spy(new PersonalizationSettingsService(null, PersonalizationSettingsServiceTest.KEY, PersonalizationSettingsServiceTest.fallback())); }
        @Bean PersonalizationController controller(PersonalizationSettingsService service) { return new PersonalizationController(service, new PersonalizationProbe()); }
        @Bean PersonalizationInternalController internal(PersonalizationSettingsService service) { return new PersonalizationInternalController(service); }
        @Bean SecurityWebFilterChain regular(ServerHttpSecurity http) { return http.csrf(ServerHttpSecurity.CsrfSpec::disable).authorizeExchange(e -> e.anyExchange().authenticated()).build(); }
    }
}
