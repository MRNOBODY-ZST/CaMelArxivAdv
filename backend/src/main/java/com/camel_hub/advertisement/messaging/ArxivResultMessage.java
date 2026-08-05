package com.camel_hub.advertisement.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ArxivResultMessage(
		int version,
		UUID messageId,
		String type,
		UUID jobId,
		String idempotencyKey,
		String traceId,
		Instant occurredAt,
		Payload payload
) {

	public record Payload(
			String status,
			String stage,
			long processedCount,
			long successCount,
			long failedCount,
			long totalCount,
			double progressPercent,
			JsonNode checkpoint,
			List<Paper> papers,
			String errorCode,
			String errorSummary
	) {
	}

	public record Paper(
			String arxivId,
			Integer version,
			String title,
			@JsonProperty("abstract") String abstractText,
			List<Author> authors,
			String primaryCategory,
			List<String> categories,
			String publishedAt,
			String updatedAt,
			String doi,
			String journalReference,
			String comment,
			String licenseUrl,
			String pdfUrl
	) {
	}

	public record Author(String name, List<String> affiliations) {
	}
}
