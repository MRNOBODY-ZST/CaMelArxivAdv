package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.campaign.delivery.*;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.template.TemplateEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import java.util.*;

@Configuration
@Profile("api")
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class CampaignTemplateMigrationConfiguration {
    @Bean CampaignTemplateMigrationRepository campaignTemplateMigrationRepository(DatabaseClient db,ContactCrypto crypto,CampaignSafetyProperties safety,CampaignDeliveryProperties delivery){
        Set<String> addresses=new HashSet<>(safety.destinations().values());
        if(safety.recipient()!=null&&!safety.recipient().isBlank())addresses.add(safety.recipient());
        Set<String> excluded=new HashSet<>();
        addresses.forEach(address->excluded.add(HexFormat.of().formatHex(crypto.hmac(address.toLowerCase(Locale.ROOT)))));
        return new CampaignTemplateMigrationRepository(db,delivery.productionCooldown(),excluded);
    }
    @Bean CampaignTemplateMigrationService campaignTemplateMigrationService(CampaignTemplateMigrationRepository repository,CampaignRepository campaigns,
            OutreachTemplateCampaignRepository metadata,TemplateEngine engine,AuditService audit,TransactionalOperator transactions,
            @Value("${app.campaign.template-migrations.enabled:false}") boolean enabled){
        return new CampaignTemplateMigrationService(repository,campaigns,metadata,new OutreachTemplateContentPolicy(engine),audit,transactions,enabled);
    }
}
