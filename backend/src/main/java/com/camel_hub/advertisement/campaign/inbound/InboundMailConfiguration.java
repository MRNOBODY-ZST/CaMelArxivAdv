package com.camel_hub.advertisement.campaign.inbound;

import com.camel_hub.advertisement.email.mailbox.MailboxPolicy;
import com.camel_hub.advertisement.email.mailbox.MailboxProperties;
import com.camel_hub.advertisement.email.mailbox.MailboxTransport;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("mail-worker")
@EnableScheduling
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "app.campaign-inbound", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({
		InboundMailProperties.class, MailboxProperties.class, SmtpProperties.class
})
public class InboundMailConfiguration {
	@Bean
	@ConditionalOnMissingBean
	SmtpSecretCrypto inboundMailboxSecretCrypto(SmtpProperties properties) {
		return new SmtpSecretCrypto(properties.encryptionKeyBase64());
	}

	@Bean
	@ConditionalOnMissingBean
	MailboxPolicy inboundMailboxPolicy(MailboxProperties properties) {
		return new MailboxPolicy(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	MailboxTransport inboundMailboxTransport(
			SmtpSecretCrypto crypto, MailboxPolicy policy, MailboxProperties properties
	) {
		return new MailboxTransport(crypto, policy, properties);
	}

	@Bean
	InboundMailRepository inboundMailRepository(
			DatabaseClient database, ReactiveTransactionManager transactionManager
	) {
		return new InboundMailRepository(
				database, TransactionalOperator.create(transactionManager));
	}

	@Bean
	InboundMailParser inboundMailParser() {
		return new InboundMailParser();
	}

	@Bean
	InboundMailSynchronizer inboundMailSynchronizer(
			InboundMailRepository repository, MailboxTransport transport, InboundMailParser parser,
			ObjectProvider<Clock> clocks, InboundMailProperties properties
	) {
		return new InboundMailSynchronizer(
				repository, transport, parser, clocks.getIfUnique(Clock::systemUTC),
				properties.leaseDuration(), properties.batchSize());
	}
}
