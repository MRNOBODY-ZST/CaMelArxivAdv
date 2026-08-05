package com.camel_hub.advertisement.identity.config;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.bootstrap.InitialAdminBootstrap;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.persistence.RefreshTokenRepository;
import com.camel_hub.advertisement.identity.security.AccessTokenService;
import com.camel_hub.advertisement.identity.security.LoginRateLimiter;
import com.camel_hub.advertisement.identity.security.PasswordPolicy;
import com.camel_hub.advertisement.identity.security.RefreshCookieFactory;
import com.camel_hub.advertisement.identity.security.RefreshTokenGenerator;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationService;
import com.camel_hub.advertisement.identity.service.RefreshSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class IdentityConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	PasswordPolicy passwordPolicy() {
		return new PasswordPolicy();
	}

	@Bean
	@Lazy
	SensitiveValueHasher sensitiveValueHasher(AuthProperties properties) {
		return new SensitiveValueHasher(properties);
	}

	@Bean
	@Lazy
	AccessTokenService accessTokenService(AuthProperties properties) {
		return new AccessTokenService(properties);
	}

	@Bean
	RefreshTokenGenerator refreshTokenGenerator() {
		return new RefreshTokenGenerator();
	}

	@Bean
	RefreshCookieFactory refreshCookieFactory(AuthProperties properties) {
		return new RefreshCookieFactory(properties);
	}

	@Bean
	@ConditionalOnBean(DatabaseClient.class)
	IdentityRepository identityRepository(DatabaseClient databaseClient) {
		return new IdentityRepository(databaseClient);
	}

	@Bean
	@ConditionalOnBean(DatabaseClient.class)
	RefreshTokenRepository refreshTokenRepository(DatabaseClient databaseClient) {
		return new RefreshTokenRepository(databaseClient);
	}

	@Bean
	@ConditionalOnBean({DatabaseClient.class, TransactionalOperator.class})
	RefreshSessionService refreshSessionService(
			RefreshTokenRepository repository,
			IdentityRepository identityRepository,
			RefreshTokenGenerator tokenGenerator,
			SensitiveValueHasher hasher,
			AuthProperties properties,
			TransactionalOperator transactions
	) {
		return new RefreshSessionService(
				repository, identityRepository, tokenGenerator, hasher, properties, transactions);
	}

	@Bean
	@ConditionalOnBean(DatabaseClient.class)
	AuditService auditService(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new AuditService(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnBean(DatabaseClient.class)
	LoginRateLimiter loginRateLimiter(
			DatabaseClient databaseClient,
			SensitiveValueHasher hasher,
			AuthProperties properties
	) {
		return new LoginRateLimiter(databaseClient, hasher, properties);
	}

	@Bean
	@ConditionalOnBean(DatabaseClient.class)
	AuthenticationService authenticationService(
			IdentityRepository repository,
			LoginRateLimiter rateLimiter,
			AuditService auditService,
			PasswordEncoder passwordEncoder,
			AccessTokenService accessTokenService,
			RefreshSessionService refreshSessions,
			PasswordPolicy passwordPolicy,
			TransactionalOperator transactions
	) {
		return new AuthenticationService(
				repository, rateLimiter, auditService, passwordEncoder, accessTokenService,
				refreshSessions, passwordPolicy, transactions);
	}

	@Bean
	@ConditionalOnBean(IdentityRepository.class)
	InitialAdminBootstrap initialAdminBootstrap(
			IdentityRepository repository,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			AuthProperties properties
	) {
		return new InitialAdminBootstrap(repository, passwordEncoder, passwordPolicy, properties);
	}
}
