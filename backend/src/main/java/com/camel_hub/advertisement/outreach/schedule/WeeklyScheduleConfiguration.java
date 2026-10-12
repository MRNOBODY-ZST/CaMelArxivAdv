package com.camel_hub.advertisement.outreach.schedule;
import com.camel_hub.advertisement.outreach.OutreachRunRepository;
import com.camel_hub.advertisement.outreach.bulk.BulkOutreachService;
import com.camel_hub.advertisement.system.personalization.*;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import java.time.Clock;
@Configuration(proxyBeanMethods=false) @Profile("api")
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class WeeklyScheduleConfiguration {
    @Bean WeeklyScheduleRepository weeklyScheduleRepository(DatabaseClient db){return new WeeklyScheduleRepository(db);}
    @Bean WeeklyTrendClient weeklyTrendClient(){return new WeeklyTrendClient();}
    @Bean TrendDiscovery weeklyTrendDiscovery(WeeklyTrendClient client,PersonalizationSettingsService settings){return new TrendDiscovery(client,settings);}
    @Bean WeeklyScheduleService weeklyScheduleService(WeeklyScheduleRepository repository,OutreachRunRepository safety,TrendDiscovery trends,BulkOutreachService bulk,AuditService audit,SensitiveValueHasher hasher,ObjectMapper mapper,TransactionalOperator tx){
        return new WeeklyScheduleService(repository,safety,trends,(actor,command,context)->bulk.create(actor,command,context).map(view->view.id()),audit,hasher,mapper,tx,Clock.systemUTC());
    }
    @Bean WeeklyScheduleScheduler weeklyScheduleScheduler(WeeklyScheduleService service){return new WeeklyScheduleScheduler(service);}
}
