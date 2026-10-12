package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.campaign.tracking.CampaignPublicContentRedactor;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.camel_hub.advertisement.system.RuntimeStatusProperties;
import com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService;
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
		PersonalizationProperties.class, RuntimeStatusProperties.class, CampaignDeliveryProperties.class, CampaignSafetyProperties.class
})
public class CampaignConfiguration {

	@Bean
    @ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    CampaignSelectionInventory campaignSelectionInventory(ContactCrypto crypto, CampaignSafetyProperties safety,
            CampaignDeliveryProperties delivery) {
        return new CampaignSelectionInventory(new CampaignProductionSelectionPolicy(crypto, safety, delivery.productionCooldown()));
    }

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
	CampaignRepository campaignRepository(DatabaseClient databaseClient, CampaignSelectionInventory inventory) {
		return new CampaignRepository(databaseClient, inventory);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignService campaignService(
			CampaignRepository repository, SegmentRepository segments, PersonalizationProperties properties,
			ObjectMapper objectMapper, TransactionalOperator transactions,
			MailTrackingProperties trackingProperties, ContactCrypto contactCrypto,
			CampaignSafetyProperties safetyProperties, CampaignDeliveryProperties deliveryProperties,
			PersonalizationSettingsService personalizationSettings
	) {
		return new CampaignService(repository, segments, properties, objectMapper, transactions,
				new CampaignPublicContentRedactor(trackingProperties.publicBaseUrl()),
				new CampaignProductionSelectionPolicy(contactCrypto, safetyProperties, deliveryProperties.productionCooldown()),
				personalizationSettings);
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
			MailTrackingProperties trackingProperties, ObjectMapper objectMapper,
			@org.springframework.beans.factory.annotation.Value("${app.partner-invite.enabled:false}") boolean partnerEnabled
	) {
		return new CampaignPreflightService(repository, deliveryProperties, trackingProperties, objectMapper, partnerEnabled);
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
	CampaignRecipientContentRepository campaignRecipientContentRepository(DatabaseClient databaseClient) {
		return new CampaignRecipientContentRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignRecipientContentService campaignRecipientContentService(
			CampaignRecipientContentRepository repository, TemplateEngine templateEngine,
			AuditService auditService, SensitiveValueHasher hasher, TransactionalOperator transactions
	) {
		return new CampaignRecipientContentService(repository, templateEngine, auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignReportingRepository campaignReportingRepository(DatabaseClient databaseClient, CampaignSelectionInventory inventory) {
		return new CampaignReportingRepository(databaseClient, inventory);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	CampaignReportingService campaignReportingService(CampaignReportingRepository repository) {
		return new CampaignReportingService(repository);
	}
}
