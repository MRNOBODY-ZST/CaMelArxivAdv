package com.camel_hub.advertisement.analytics;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration
@Profile("api")
public class AnalyticsConfiguration {

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	AnalyticsRepository analyticsRepository(DatabaseClient databaseClient) {
		return new AnalyticsRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	AnalyticsService analyticsService(
			AnalyticsRepository repository,
			AuditService auditService,
			SensitiveValueHasher hasher
	) {
		return new AnalyticsService(repository, auditService, hasher);
	}
}
