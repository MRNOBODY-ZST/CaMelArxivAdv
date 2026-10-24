package com.camel_hub.advertisement.outreach.schedule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record WeeklyScheduleView(UUID id,String name,String purpose,int maxPapers,UUID smtpAccountId,UUID templateId,List<String> directions,boolean enabled,String timezone,String dayOfWeek,String localTime,Instant nextRunAt,UUID lastRunId,String message,boolean acceptDeliverabilityRisk,long lockVersion,Instant createdAt,Instant updatedAt,List<String> allowedActions) {}
