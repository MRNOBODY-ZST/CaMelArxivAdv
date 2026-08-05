package com.camel_hub.advertisement.arxiv.config;

import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomySnapshotLoader;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyBootstrap;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyService;
import com.camel_hub.advertisement.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@EnableConfigurationProperties(ArxivProperties.class)
public class ArxivConfiguration {

	@Bean
	TaxonomySnapshotLoader taxonomySnapshotLoader(ObjectMapper objectMapper, ArxivProperties properties) {
		return new TaxonomySnapshotLoader(objectMapper, properties.taxonomySnapshot());
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TaxonomyRepository taxonomyRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new TaxonomyRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TaxonomyService taxonomyService(
			TaxonomyRepository repository,
			TaxonomySnapshotLoader snapshotLoader,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		return new TaxonomyService(repository, snapshotLoader, auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TaxonomyBootstrap taxonomyBootstrap(TaxonomyService service) {
		return new TaxonomyBootstrap(service);
	}
}
