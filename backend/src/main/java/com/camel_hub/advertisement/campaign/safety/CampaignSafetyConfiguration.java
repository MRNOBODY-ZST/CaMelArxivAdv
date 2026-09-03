package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.delivery.CampaignSafetyProperties;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.tracking.MailOpenClassifier;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Clock;

/** Wires the safety namespace into API callbacks and the delivery worker without web leakage. */
@Configuration(proxyBeanMethods = false)
@Profile({"api", "mail-worker"})
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({
		CampaignSafetyProperties.class, SmtpProperties.class, MailTrackingProperties.class
})
public class CampaignSafetyConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "app.mail-tracking", name = "enabled", havingValue = "true")
	CampaignSafetySigner campaignSafetySigner(MailTrackingProperties tracking) {
		return new CampaignSafetySigner(tracking.signingKeyBase64());
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.mail-tracking", name = "enabled", havingValue = "true")
	CampaignSafetyRuntimePolicy campaignSafetyRuntimePolicy(
			CampaignSafetyProperties safety, SmtpProperties smtp,
			MailTrackingProperties tracking, CampaignSafetySigner signer,
			CampaignDeliveryProperties delivery
	) {
		return new CampaignSafetyRuntimePolicy(
				safety, smtp, tracking, signer, delivery.leaseDuration());
	}

	@Bean
	CampaignSafetyRepository campaignSafetyRepository(
			DatabaseClient database, ReactiveTransactionManager transactionManager, ObjectMapper objectMapper
	) {
		return new CampaignSafetyRepository(
				database, TransactionalOperator.create(transactionManager), objectMapper);
	}

	@Bean
	@Order(200)
	@ConditionalOnProperty(prefix = "app.mail-tracking", name = "enabled", havingValue = "true")
	CampaignSafetyTrackingService campaignSafetyTrackingService(
			CampaignSafetyRepository repository, CampaignSafetyRuntimePolicy policy,
			MailTrackingProperties tracking, CampaignSafetySigner signer,
			ObjectProvider<Clock> clocks, ReactiveTransactionManager transactionManager
	) {
		return new CampaignSafetyTrackingService(
				repository, policy, tracking, signer, new MailOpenClassifier(),
				clocks.getIfUnique(Clock::systemUTC), TransactionalOperator.create(transactionManager));
	}

	@Bean
	@Profile("api")
	CampaignSafetyService campaignSafetyService(
			CampaignSafetyRepository repository, ObjectProvider<CampaignSafetyRuntimePolicy> policies,
			CampaignSafetyProperties properties, ObjectProvider<Clock> clocks
	) {
		return new CampaignSafetyService(
				repository, policies.getIfAvailable(), properties.maximumRecipients(),
				clocks.getIfUnique(Clock::systemUTC));
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-safety", name = "enabled", havingValue = "true")
	CampaignSafetyStartupGate campaignSafetyStartupGate(
			ObjectProvider<CampaignSafetyRuntimePolicy> policies
	) {
		CampaignSafetyRuntimePolicy policy = policies.getIfAvailable();
		if (policy == null) {
			throw new IllegalStateException("Campaign safety mode requires mail tracking");
		}
		policy.requireReady();
		return new CampaignSafetyStartupGate();
	}

	static final class CampaignSafetyStartupGate { }
}
