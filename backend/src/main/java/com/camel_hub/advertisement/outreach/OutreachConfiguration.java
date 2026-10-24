package com.camel_hub.advertisement.outreach;
import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionService;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration @Profile("api") @EnableConfigurationProperties(OutreachProperties.class)
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class OutreachConfiguration {
    @Bean OutreachRunRepository outreachRunRepository(DatabaseClient db) { return new OutreachRunRepository(db); }
    @Bean OutreachRunService outreachRunService(OutreachRunRepository repository,ArxivImportService imports,SourceExtractionService extraction,
            OutreachTemplateCampaignService adapter,CampaignService campaigns,CampaignPreflightService preflight,CampaignWorkflowService workflow,
            AuditService audit,SensitiveValueHasher hasher,ObjectMapper mapper,OutreachProperties properties,TransactionalOperator tx) {
        return new OutreachRunService(repository,imports,extraction,adapter,campaigns,preflight,workflow,audit,hasher,mapper,properties,tx);
    }
    @Bean OutreachRunScheduler outreachRunScheduler(OutreachRunService service) { return new OutreachRunScheduler(service); }
}
