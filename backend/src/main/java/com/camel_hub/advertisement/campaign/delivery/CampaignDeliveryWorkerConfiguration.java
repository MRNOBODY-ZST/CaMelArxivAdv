package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyOutboundPreparer;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRepository;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetyRuntimePolicy;
import com.camel_hub.advertisement.email.smtp.SmtpPolicy;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.messaging.KafkaDeadLetterPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("mail-worker")
@EnableScheduling
@EnableConfigurationProperties({
		CampaignDeliveryProperties.class, CampaignSafetyProperties.class,
		SmtpProperties.class, ContactDataProtectionProperties.class
})
public class CampaignDeliveryWorkerConfiguration {
	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean
	TransactionalOperator campaignDeliveryTransactions(ReactiveTransactionManager manager) {
		return TransactionalOperator.create(manager);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean
	ContactCrypto campaignDeliveryContactCrypto(ContactDataProtectionProperties properties) {
		return new ContactCrypto(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean
	SmtpRepository campaignDeliverySmtpRepository(DatabaseClient database) {
		return new SmtpRepository(database);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean
	SmtpSecretCrypto campaignDeliverySmtpSecretCrypto(SmtpProperties properties) {
		return new SmtpSecretCrypto(properties.encryptionKeyBase64());
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean
	SmtpPolicy campaignDeliverySmtpPolicy(SmtpProperties properties) {
		return new SmtpPolicy(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean
	SmtpTransport campaignDeliverySmtpTransport(
			SmtpSecretCrypto crypto, SmtpPolicy policy, SmtpProperties properties
	) {
		return new SmtpTransport(crypto, policy, properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	CampaignDeliveryRepository campaignDeliveryRepository(
			DatabaseClient database, TransactionalOperator transactions,
			CampaignDeliveryProperties properties, CampaignSafetyProperties safety,
			ObjectProvider<CampaignSafetyRuntimePolicy> safetyPolicy
	) {
		return new CampaignDeliveryRepository(
				database, transactions, properties, safety, safetyPolicy.getIfAvailable());
	}

	@Bean
	@ConditionalOnMissingBean
	Clock campaignDeliveryClock() {
		return Clock.systemUTC();
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.campaign-delivery", name = "enabled", havingValue = "true")
	static BeanFactoryPostProcessor campaignDeliveryRuntimeRegistrar() {
		return beanFactory -> {
			if (!(beanFactory instanceof DefaultListableBeanFactory registry)
					|| beanFactory.getBeanNamesForType(CampaignOutboundPreparer.class, false, false).length == 0) {
				return;
			}
			RootBeanDefinition executor = new RootBeanDefinition(CampaignDeliveryExecutor.class);
			executor.setInstanceSupplier(() -> {
				CampaignSafetyRepository safetyRepository = beanFactory
						.getBeanProvider(CampaignSafetyRepository.class).getIfAvailable();
				CampaignSafetyOutboundPreparer safetyPreparer = beanFactory
						.getBeanProvider(CampaignSafetyOutboundPreparer.class).getIfAvailable();
				return new CampaignDeliveryExecutor(
						beanFactory.getBean(CampaignDeliveryRepository.class),
						beanFactory.getBean(CampaignOutboundPreparer.class),
						safetyRepository, safetyPreparer,
						beanFactory.getBean(ContactCrypto.class),
						beanFactory.getBean(SmtpTransport.class)::sendDetailed,
						beanFactory.getBean(Clock.class));
			});
			registry.registerBeanDefinition("campaignDeliveryExecutor", executor);

			RootBeanDefinition listener = new RootBeanDefinition(CampaignDeliveryListener.class);
			listener.setInstanceSupplier(() -> new CampaignDeliveryListener(
					beanFactory.getBean(ObjectMapper.class), beanFactory.getBean(CampaignDeliveryExecutor.class),
					beanFactory.getBean(KafkaDeadLetterPublisher.class)));
			registry.registerBeanDefinition("campaignDeliveryListener", listener);

			RootBeanDefinition scheduler = new RootBeanDefinition(CampaignDeliveryScheduler.class);
			scheduler.setInstanceSupplier(() -> new CampaignDeliveryScheduler(
					beanFactory.getBean(CampaignDeliveryRepository.class),
					beanFactory.getBeanProvider(CampaignSafetyRepository.class).getIfAvailable(),
					beanFactory.getBean(CampaignSafetyProperties.class),
					beanFactory.getBean(CampaignDeliveryExecutor.class),
					beanFactory.getBean(CampaignDeliveryProperties.class), beanFactory.getBean(Clock.class)));
			registry.registerBeanDefinition("campaignDeliveryScheduler", scheduler);
		};
	}
}
