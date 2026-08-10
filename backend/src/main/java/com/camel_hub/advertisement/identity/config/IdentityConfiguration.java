package com.camel_hub.advertisement.identity.config;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.audit.AuditQueryService;
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
import com.camel_hub.advertisement.identity.service.RoleAdministrationService;
import com.camel_hub.advertisement.identity.service.UserAdministrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class IdentityConfiguration {

	@Bean
	@ConditionalOnMissingBean(ObjectMapper.class)
	ObjectMapper legacyObjectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}

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
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	IdentityRepository identityRepository(DatabaseClient databaseClient) {
		return new IdentityRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	RefreshTokenRepository refreshTokenRepository(DatabaseClient databaseClient) {
		return new RefreshTokenRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
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
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	AuditService auditService(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new AuditService(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	LoginRateLimiter loginRateLimiter(
			DatabaseClient databaseClient,
			SensitiveValueHasher hasher,
			AuthProperties properties
	) {
		return new LoginRateLimiter(databaseClient, hasher, properties);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
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
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	UserAdministrationService userAdministrationService(
			DatabaseClient databaseClient,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			RefreshSessionService refreshSessions,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		return new UserAdministrationService(
				databaseClient, passwordEncoder, passwordPolicy, refreshSessions,
				auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	RoleAdministrationService roleAdministrationService(
			DatabaseClient databaseClient,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		return new RoleAdministrationService(databaseClient, auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	AuditQueryService auditQueryService(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new AuditQueryService(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	InitialAdminBootstrap initialAdminBootstrap(
			IdentityRepository repository,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			AuthProperties properties
	) {
		return new InitialAdminBootstrap(repository, passwordEncoder, passwordPolicy, properties);
	}

	@Bean
	@ConditionalOnMissingBean(TransactionalOperator.class)
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
		return TransactionalOperator.create(transactionManager);
	}
}
