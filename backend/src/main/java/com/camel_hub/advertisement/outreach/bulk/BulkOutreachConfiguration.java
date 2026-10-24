package com.camel_hub.advertisement.outreach.bulk;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionService;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.outreach.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration @Profile("api")
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class BulkOutreachConfiguration {
    @Bean BulkOutreachRepository bulkOutreachRepository(DatabaseClient db,ObjectMapper mapper,CampaignSelectionInventory inventory) {return new BulkOutreachRepository(db,mapper,inventory);}
    @Bean BulkOutreachService bulkOutreachService(BulkOutreachRepository repository,OutreachRunRepository support,ArxivImportService imports,SourceExtractionService extraction,
            OutreachTemplateCampaignService adapter,CampaignService campaigns,CampaignPreflightService preflight,CampaignWorkflowService workflow,
            AuditService audit,SensitiveValueHasher hasher,ObjectMapper mapper,OutreachProperties properties,TransactionalOperator tx) {
        return new BulkOutreachService(repository,support,imports,extraction,adapter,campaigns,preflight,workflow,audit,hasher,mapper,properties,tx);
    }
    @Bean BulkOutreachScheduler bulkOutreachScheduler(BulkOutreachService service) {return new BulkOutreachScheduler(service);}
}
