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
			long skippedCount,
			long failedCount,
			long totalCount,
			double progressPercent,
			JsonNode checkpoint,
			List<Paper> papers,
			String errorCode,
			String errorSummary,
			String workerId,
			String workerType,
			String version,
			UUID currentJobId,
			String snapshotVersion,
			Instant taxonomySourceUpdatedAt,
			List<TaxonomyCategory> taxonomyCategories,
			List<SourceExtraction> extractions
	) {
	}

	public record TaxonomyCategory(
			String groupId,
			String groupName,
			String archiveId,
			String archiveName,
			String categoryId,
			String categoryName,
			String description,
			boolean alias,
			String aliasTarget
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

	public record SourceExtraction(
			UUID paperId,
			String arxivId,
			String parserVersion,
			String status,
			boolean cleanupConfirmed,
			String sourceFormat,
			long archiveSizeBytes,
			long extractedSizeBytes,
			int filesInspected,
			long durationMs,
			String documentClass,
			List<SourceAuthor> authors,
			List<SourceContact> contacts,
			String errorCode,
			String errorSummary
	) { }

	public record SourceAuthor(
			int order,
			String name,
			List<String> affiliations,
			boolean corresponding
	) { }

	public record SourceContact(
			String normalizedEmail,
			String displayEmail,
			String domain,
			boolean syntaxValid,
			boolean exampleAddress,
			Integer authorOrder,
			String confidence,
			boolean corresponding,
			List<SourceEvidence> evidence
	) { }

	public record SourceEvidence(
			String sourceRelativePath,
			String ruleName,
			Integer lineNumber,
			String logicalLocation,
			String maskedContext
	) { }
}
