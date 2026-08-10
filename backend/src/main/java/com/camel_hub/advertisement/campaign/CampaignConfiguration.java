package com.camel_hub.advertisement.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@Profile("api")
public class CampaignConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SegmentRepository segmentRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new SegmentRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SegmentService segmentService(SegmentRepository repository, TransactionalOperator transactions) {
		return new SegmentService(repository, transactions);
	}
}
