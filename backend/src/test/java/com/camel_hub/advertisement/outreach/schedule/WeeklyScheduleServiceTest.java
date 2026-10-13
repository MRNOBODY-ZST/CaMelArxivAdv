package com.camel_hub.advertisement.outreach.schedule;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class WeeklyScheduleServiceTest {
    @Test void immutableParentCommandIsIncrementalBoundedAndIncludesOccurrenceKey(){
        var command=new WeeklyScheduleCommand("Weekly","Research",5000,UUID.randomUUID(),UUID.randomUUID(),List.of("Agent"),true,"AUTO_OUTREACH_SEND","weekly-seed-key");
        var result=TrendDiscovery.fallback(command.directions(),"PROVIDER_DISABLED",0,"openai","test",0,0,0);
        var bulk=WeeklyScheduleService.bulkCommand(command,result,LocalDate.parse("2026-10-10"),LocalDate.parse("2026-10-19"),"weekly:occurrence-123");
        assertThat(bulk).isNotNull();assertThat(bulk.incremental()).isTrue();assertThat(bulk.targetPapers()).isEqualTo(5000);
        assertThat(bulk.idempotencyKey()).isEqualTo("weekly:occurrence-123");assertThat(bulk.confirmation()).isEqualTo("AUTO_OUTREACH_SEND");assertThat(bulk.acceptDeliverabilityRisk()).isTrue();
        assertThat(bulk.searchPlan()).hasSize(1);assertThat(bulk.searchPlan().getFirst().submittedFrom()).isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(bulk.searchPlan().getFirst().submittedTo()).isEqualTo(LocalDate.parse("2026-10-19"));assertThat(bulk.searchPlan().getFirst().abstractKeywords()).isEqualTo("Agent");
    }
    @Test void oversizedScheduleAndMissingConfirmationCannotBecomeParents(){
        for(int max:List.of(0,5001))assertThatThrownBy(()->WeeklyScheduleService.validate(new WeeklyScheduleCommand("Weekly","Research",max,UUID.randomUUID(),UUID.randomUUID(),List.of("Agent"),false,"AUTO_OUTREACH_SEND","weekly-seed-key"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WeeklyScheduleService.validate(new WeeklyScheduleCommand("Weekly","Research",5000,UUID.randomUUID(),UUID.randomUUID(),List.of("Agent"),false,"","weekly-seed-key"))).isInstanceOf(IllegalArgumentException.class);
    }
}
