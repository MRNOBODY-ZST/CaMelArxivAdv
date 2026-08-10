package com.camel_hub.advertisement.messaging;

import java.time.Instant;
import java.util.UUID;

public record PersonalizationResultMessage(
		int version,
		UUID messageId,
		String type,
		UUID jobId,
		UUID campaignId,
		UUID recipientId,
		String idempotencyKey,
		String traceId,
		Instant occurredAt,
		Payload payload
) {
	public record Payload(
			String status,
			String subject,
			String html,
			String text,
			String rationale,
			String provider,
			String model,
			String errorCode,
			String errorMessage
	) { }
}
