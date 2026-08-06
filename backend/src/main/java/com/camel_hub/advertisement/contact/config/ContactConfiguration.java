package com.camel_hub.advertisement.contact.config;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.contact.ContactRepository;
import com.camel_hub.advertisement.contact.ContactService;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.contact.security.EmailDisclosurePolicy;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration
@Profile("api")
@EnableConfigurationProperties(ContactDataProtectionProperties.class)
public class ContactConfiguration {

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ContactCrypto contactCrypto(ContactDataProtectionProperties properties) {
		return new ContactCrypto(properties);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ContactRepository contactRepository(DatabaseClient databaseClient) {
		return new ContactRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ContactService contactService(
			ContactRepository repository,
			ContactCrypto crypto,
			EmailDisclosurePolicy disclosurePolicy,
			AuditService auditService,
			SensitiveValueHasher hasher
	) {
		return new ContactService(repository, crypto, disclosurePolicy, auditService, hasher);
	}
}
