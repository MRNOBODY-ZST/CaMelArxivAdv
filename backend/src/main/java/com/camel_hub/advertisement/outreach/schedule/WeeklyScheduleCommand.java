package com.camel_hub.advertisement.outreach.schedule;
import java.util.List;
import java.util.UUID;
public record WeeklyScheduleCommand(String name,String purpose,int maxPapers,UUID smtpAccountId,UUID templateId,List<String> directions,boolean acceptDeliverabilityRisk,String confirmation,String idempotencyKey) {}
