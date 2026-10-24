package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@Profile("api")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutreachTemplateCampaignConfiguration {
	@Bean
	OutreachTemplateCampaignRepository outreachTemplateCampaignRepository(DatabaseClient db) {
		return new OutreachTemplateCampaignRepository(db);
	}

	@Bean
	OutreachTemplateCampaignService outreachTemplateCampaignService(CampaignRepository campaigns, SegmentRepository segments,
			CampaignService campaignService, CampaignWorkflowService workflow, OutreachTemplateCampaignRepository metadata,
			CampaignRecipientContentRepository contents, TemplateEngine engine, ContactCrypto crypto,
			CampaignSafetyProperties safety, CampaignDeliveryProperties delivery, TransactionalOperator transactions) {
		return new OutreachTemplateCampaignService(campaigns, segments, campaignService, workflow, metadata, contents,
				new OutreachTemplateContentPolicy(engine), new CampaignProductionSelectionPolicy(crypto, safety, delivery.productionCooldown()), transactions);
	}
}
