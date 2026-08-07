package com.camel_hub.advertisement.common.security;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.security.LiveUserJwtAuthenticationConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfiguration {

	@Bean
	ReactiveJwtDecoder jwtDecoder(AuthProperties properties) {
		var secretKey = new SecretKeySpec(properties.decodedSigningKey(), "HmacSHA256");
		NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(secretKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<>(
				"iss", properties.issuer()::equals);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), issuer));
		return decoder;
	}

	@Bean
	Converter<Jwt, Mono<UsernamePasswordAuthenticationToken>> jwtAuthenticationConverter(
			ObjectProvider<IdentityRepository> repositoryProvider
	) {
		IdentityRepository repository = repositoryProvider.getIfAvailable();
		if (repository == null) {
			return jwt -> Mono.error(new BadCredentialsException("Identity store is unavailable"));
		}
		return new LiveUserJwtAuthenticationConverter(repository);
	}

	@Bean
	SecurityWebFilterChain securityWebFilterChain(
			ServerHttpSecurity http,
			ReactiveJwtDecoder jwtDecoder,
			Converter<Jwt, Mono<UsernamePasswordAuthenticationToken>> jwtAuthenticationConverter,
			SecurityErrorResponseWriter errorWriter
	) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.authorizeExchange(exchanges -> exchanges
						.pathMatchers(
								"/api/v1/auth/login",
								"/api/v1/auth/refresh",
								"/api/v1/auth/logout",
								"/t/o/**",
								"/t/c/**",
								"/api/v1/system/health",
								"/actuator/health/**",
								"/actuator/info",
								"/api/openapi.json",
								"/api/docs/**",
								"/webjars/swagger-ui/**")
						.permitAll()
						.pathMatchers(HttpMethod.GET, "/api/v1/template-assets/*/*/content").permitAll()
						.pathMatchers(HttpMethod.GET, "/api/v1/users").hasAuthority("user:read")
						.pathMatchers(HttpMethod.POST, "/api/v1/users").hasAuthority("user:create")
						.pathMatchers(HttpMethod.PUT, "/api/v1/users/*").hasAuthority("user:update")
						.pathMatchers(HttpMethod.POST, "/api/v1/users/*/reset-password")
						.hasAuthority("user:update")
						.pathMatchers(HttpMethod.POST, "/api/v1/users/*/disable", "/api/v1/users/*/enable")
						.hasAuthority("user:disable")
						.pathMatchers(HttpMethod.GET, "/api/v1/roles", "/api/v1/permissions")
						.hasAuthority("role:read")
						.pathMatchers(HttpMethod.POST, "/api/v1/roles").hasAuthority("role:manage")
						.pathMatchers(HttpMethod.PUT, "/api/v1/roles/*").hasAuthority("role:manage")
						.pathMatchers(HttpMethod.DELETE, "/api/v1/roles/*").hasAuthority("role:manage")
						.pathMatchers(HttpMethod.GET, "/api/v1/audit-logs").hasAuthority("audit:read")
						.anyExchange().authenticated())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint((exchange, exception) -> errorWriter.authenticationRequired(exchange))
						.accessDeniedHandler((exchange, exception) -> errorWriter.accessDenied(exchange)))
				.oauth2ResourceServer(resourceServer -> resourceServer
						.authenticationEntryPoint(
								(exchange, exception) -> errorWriter.authenticationRequired(exchange))
						.accessDeniedHandler((exchange, exception) -> errorWriter.accessDenied(exchange))
						.jwt(jwt -> jwt
								.jwtDecoder(jwtDecoder)
								.jwtAuthenticationConverter(jwtAuthenticationConverter)))
				.build();
	}
}
