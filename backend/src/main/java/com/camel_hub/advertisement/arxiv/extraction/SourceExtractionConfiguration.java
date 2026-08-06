package com.camel_hub.advertisement.arxiv.extraction;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@Profile("api")
@ConditionalOnProperty(
		prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SourceExtractionConfiguration {

	@Bean
	SourceExtractionRepository sourceExtractionRepository(DatabaseClient databaseClient) {
		return new SourceExtractionRepository(databaseClient);
	}

	@Bean
	SourceExtractionService sourceExtractionService(
			SourceExtractionRepository repository,
			AuditService auditService,
			SensitiveValueHasher hasher,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		return new SourceExtractionService(
				repository, auditService, hasher, objectMapper, "0.1.0", transactions);
	}
}
