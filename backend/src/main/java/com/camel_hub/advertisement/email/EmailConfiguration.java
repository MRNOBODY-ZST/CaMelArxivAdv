package com.camel_hub.advertisement.email;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.email.mailbox.MailboxPolicy;
import com.camel_hub.advertisement.email.mailbox.MailboxProperties;
import com.camel_hub.advertisement.email.mailbox.MailboxRepository;
import com.camel_hub.advertisement.email.mailbox.MailboxService;
import com.camel_hub.advertisement.email.mailbox.MailboxTransport;
import com.camel_hub.advertisement.email.smtp.SmtpPolicy;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpService;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.email.template.MinioTemplateAssetObjectStore;
import com.camel_hub.advertisement.email.template.TemplateAssetObjectStore;
import com.camel_hub.advertisement.email.template.TemplateAssetCopyService;
import com.camel_hub.advertisement.email.template.TemplateAssetProperties;
import com.camel_hub.advertisement.email.template.TemplateAssetRepository;
import com.camel_hub.advertisement.email.template.TemplateAssetService;
import com.camel_hub.advertisement.email.template.TemplateAssetSigner;
import com.camel_hub.advertisement.email.template.TemplateMailService;
import com.camel_hub.advertisement.email.template.TemplateProperties;
import com.camel_hub.advertisement.email.template.TemplateRepository;
import com.camel_hub.advertisement.email.template.TemplateService;
import com.camel_hub.advertisement.email.tracking.MailTrackingService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@Profile("api")
@EnableConfigurationProperties({
		TemplateProperties.class, TemplateAssetProperties.class, SmtpProperties.class, MailboxProperties.class
})
public class EmailConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateEngine templateEngine(TemplateProperties properties) {
		return new TemplateEngine(properties.maxContentBytes());
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateRepository templateRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new TemplateRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateAssetRepository templateAssetRepository(DatabaseClient databaseClient) {
		return new TemplateAssetRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateAssetObjectStore templateAssetObjectStore(TemplateAssetProperties properties) {
		return new MinioTemplateAssetObjectStore(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateAssetSigner templateAssetSigner(TemplateAssetProperties properties) {
		return new TemplateAssetSigner(properties.signingKeyBase64(), properties.publicBaseUrl());
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateAssetCopyService templateAssetCopyService(
			TemplateAssetRepository repository, TemplateAssetObjectStore store, TemplateAssetSigner signer
	) {
		return new TemplateAssetCopyService(repository, store, signer);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SmtpRepository smtpRepository(DatabaseClient databaseClient) {
		return new SmtpRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SmtpSecretCrypto smtpSecretCrypto(SmtpProperties properties) {
		return new SmtpSecretCrypto(properties.encryptionKeyBase64());
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SmtpPolicy smtpPolicy(SmtpProperties properties) {
		return new SmtpPolicy(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SmtpTransport smtpTransport(SmtpSecretCrypto crypto, SmtpPolicy policy, SmtpProperties properties) {
		return new SmtpTransport(crypto, policy, properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	MailboxRepository mailboxRepository(DatabaseClient databaseClient) {
		return new MailboxRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	MailboxPolicy mailboxPolicy(MailboxProperties properties) {
		return new MailboxPolicy(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	MailboxTransport mailboxTransport(
			SmtpSecretCrypto crypto, MailboxPolicy policy, MailboxProperties properties
	) {
		return new MailboxTransport(crypto, policy, properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateService templateService(
			TemplateRepository repository, TemplateEngine engine, TemplateAssetCopyService assetCopyService,
			AuditService auditService,
			SensitiveValueHasher hasher, TransactionalOperator transactions
	) {
		return new TemplateService(repository, engine, assetCopyService, auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SmtpService smtpService(
			SmtpRepository repository, SmtpSecretCrypto crypto, SmtpPolicy policy,
			AuditService auditService, SensitiveValueHasher hasher, TransactionalOperator transactions,
			SmtpTransport transport, MailTrackingService tracking
	) {
		return new SmtpService(repository, crypto, policy, auditService, hasher, transactions, transport, tracking);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	MailboxService mailboxService(
			MailboxRepository repository, SmtpSecretCrypto crypto, MailboxPolicy policy,
			MailboxTransport transport, MailboxProperties properties, AuditService auditService,
			SensitiveValueHasher hasher, TransactionalOperator transactions
	) {
		return new MailboxService(
				repository, crypto, policy, transport, properties, auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateMailService templateMailService(
			TemplateRepository templates, TemplateEngine engine, SmtpService smtp, TemplateAssetSigner assetSigner
	) {
		return new TemplateMailService(templates, engine, smtp, assetSigner);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TemplateAssetService templateAssetService(
			TemplateRepository templates, TemplateAssetRepository assets, TemplateAssetObjectStore store,
			AuditService auditService, SensitiveValueHasher hasher, TemplateAssetSigner assetSigner,
			TemplateAssetProperties properties
	) {
		return new TemplateAssetService(
				templates, assets, store, auditService, hasher, assetSigner, properties.maxBytes());
	}
}
