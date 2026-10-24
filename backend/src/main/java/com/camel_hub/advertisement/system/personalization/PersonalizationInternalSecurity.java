package com.camel_hub.advertisement.system.personalization;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Configuration(proxyBeanMethods=false)
@Profile("!mail-worker")
public class PersonalizationInternalSecurity {
    @Bean @Order(-100)
    SecurityWebFilterChain personalizationInternalChain(ServerHttpSecurity http,
            @Value("${app.personalization-settings.service-token:}") String token) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/internal/personalization/**"))
            .csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable)
            .addFilterAt((exchange, chain) -> {
                exchange.getResponse().getHeaders().set("Cache-Control", "no-store");
                return chain.filter(exchange);
            }, SecurityWebFiltersOrder.FIRST)
            .authorizeExchange(exchanges -> exchanges.anyExchange().access((authentication, context) -> {
                String header = context.getExchange().getRequest().getHeaders().getFirst("Authorization");
                boolean valid = token.length() >= 32 && header != null && MessageDigest.isEqual(
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
                return Mono.just(new AuthorizationDecision(valid));
            }))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((exchange, ex) -> {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED); return exchange.getResponse().setComplete();
            }).accessDeniedHandler((exchange, ex) -> {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED); return exchange.getResponse().setComplete();
            })).build();
    }
}
