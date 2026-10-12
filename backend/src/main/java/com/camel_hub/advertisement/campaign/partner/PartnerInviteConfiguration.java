package com.camel_hub.advertisement.campaign.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("mail-worker")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PartnerInviteProperties.class)
public class PartnerInviteConfiguration {
    @Bean PartnerInviteRepository partnerInviteRepository(DatabaseClient database, ObjectProvider<Clock> clocks) {
        return new R2dbcPartnerInviteRepository(database, clocks.getIfUnique(Clock::systemUTC));
    }

    @Bean PartnerInviteCrypto partnerInviteCrypto(PartnerInviteProperties properties) {
        return new PartnerInviteCrypto(properties.encryptionKeyBase64());
    }

    @Bean PartnerInviteClient partnerInviteClient(PartnerInviteProperties properties, ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new HttpPartnerInviteClient(properties, json, clocks.getIfUnique(Clock::systemUTC));
    }

    @Bean PartnerInviteService partnerInviteService(PartnerInviteRepository repository,
            PartnerInviteClient client, PartnerInviteCrypto crypto, PartnerInviteProperties properties,
            ObjectProvider<Clock> clocks) {
        return new PartnerInviteService(repository, client, crypto, properties, clocks.getIfUnique(Clock::systemUTC));
    }
}
