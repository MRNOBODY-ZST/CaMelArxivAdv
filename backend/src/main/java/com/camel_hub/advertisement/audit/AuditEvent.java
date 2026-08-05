package com.camel_hub.advertisement.audit;

import java.util.Map;
import java.util.UUID;

public record AuditEvent(
		UUID actorUserId,
		String action,
		String resourceType,
		String resourceId,
		byte[] ipHash,
		String userAgentSummary,
		String traceId,
		Map<String, ?> beforeSummary,
		Map<String, ?> afterSummary,
		AuditResult result,
		String errorType
) {
}
