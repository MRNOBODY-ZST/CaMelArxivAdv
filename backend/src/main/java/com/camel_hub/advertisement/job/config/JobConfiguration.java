package com.camel_hub.advertisement.job.config;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.job.domain.JobStateMachine;
import com.camel_hub.advertisement.job.persistence.JobRepository;
import com.camel_hub.advertisement.job.service.JobService;
import com.camel_hub.advertisement.job.service.JobEventStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Duration;

@Configuration
public class JobConfiguration {

	@Bean
	JobStateMachine jobStateMachine() {
		return new JobStateMachine();
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	JobRepository jobRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new JobRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	JobService jobService(
			JobRepository repository,
			JobStateMachine stateMachine,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		return new JobService(repository, stateMachine, auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	JobEventStream jobEventStream(JobRepository repository) {
		return new JobEventStream(repository, Duration.ofSeconds(1));
	}
}
