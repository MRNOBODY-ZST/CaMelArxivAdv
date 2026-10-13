package com.camel_hub.advertisement.arxiv.extraction;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionRepository;
import com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionService;
import com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionMailbox;
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
	SourceExtractionRepository sourceExtractionRepository(DatabaseClient databaseClient, SourceAdmissionRepository admission) {
		return new SourceExtractionRepository(databaseClient, admission);
	}

	@Bean
	@ConditionalOnProperty(prefix="app.arxiv.source-admission",name="enabled",havingValue="true")
	SourceAdmissionService sourceAdmissionService(SourceAdmissionRepository admission,
			org.springframework.data.redis.core.ReactiveStringRedisTemplate redis,
			org.springframework.transaction.ReactiveTransactionManager manager) {
		return new SourceAdmissionService(admission,redis,manager,java.time.Clock.systemUTC());
	}

	@Bean
	@ConditionalOnProperty(prefix="app.arxiv.source-admission",name="enabled",havingValue="true")
	SourceAdmissionMailbox sourceAdmissionMailbox(SourceAdmissionService service,
			org.springframework.data.redis.core.ReactiveStringRedisTemplate redis) {
		return new SourceAdmissionMailbox(service,redis);
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
