package com.camel_hub.advertisement.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PersonalizationCommandMessage(
		int version,
		UUID messageId,
		String type,
		UUID jobId,
		UUID campaignId,
		String idempotencyKey,
		String traceId,
		Instant occurredAt,
		Payload payload
) {
	public record Payload(
			String purpose,
			String templateSubject,
			String templateHtml,
			String templateText,
			List<Target> targets
	) {
		public Payload {
			targets = List.copyOf(targets);
		}
	}

	public record Target(
			UUID recipientId,
			String authorName,
			String paperTitle,
			String paperAbstract,
			String arxivId,
			String primaryCategory,
			String paperUrl,
			String organization
	) { }
}
