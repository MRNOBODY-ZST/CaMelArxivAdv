package com.camel_hub.advertisement.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.authorizeExchange(exchanges -> exchanges
						.pathMatchers(
								"/api/v1/auth/login",
								"/api/v1/auth/refresh",
								"/api/v1/system/health",
								"/actuator/health/**",
								"/actuator/info",
								"/api/openapi.json",
								"/api/docs/**",
								"/webjars/swagger-ui/**")
						.permitAll()
						.anyExchange().authenticated())
				.build();
	}
}
