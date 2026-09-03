package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.campaign.tracking.CampaignPublicContentRedactor;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.camel_hub.advertisement.system.RuntimeStatusProperties;
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
		PersonalizationProperties.class, RuntimeStatusProperties.class, CampaignDeliveryProperties.class
})
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

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignRepository campaignRepository(DatabaseClient databaseClient) {
		return new CampaignRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignService campaignService(
			CampaignRepository repository, SegmentRepository segments, PersonalizationProperties properties,
			ObjectMapper objectMapper, TransactionalOperator transactions,
			MailTrackingProperties trackingProperties
	) {
		return new CampaignService(repository, segments, properties, objectMapper, transactions,
				new CampaignPublicContentRedactor(trackingProperties.publicBaseUrl()));
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignWorkflowRepository campaignWorkflowRepository(DatabaseClient databaseClient) {
		return new CampaignWorkflowRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignPreflightService campaignPreflightService(
			CampaignWorkflowRepository repository, CampaignDeliveryProperties deliveryProperties,
			MailTrackingProperties trackingProperties, ObjectMapper objectMapper
	) {
		return new CampaignPreflightService(repository, deliveryProperties, trackingProperties, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignWorkflowService campaignWorkflowService(
			CampaignWorkflowRepository repository, CampaignPreflightService preflight, CampaignService campaigns,
			AuditService auditService, SensitiveValueHasher hasher, ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		return new CampaignWorkflowService(
				repository, preflight, campaigns, auditService, hasher, objectMapper, transactions);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignReportingRepository campaignReportingRepository(DatabaseClient databaseClient) {
		return new CampaignReportingRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignReportingService campaignReportingService(CampaignReportingRepository repository) {
		return new CampaignReportingService(repository);
	}
}
