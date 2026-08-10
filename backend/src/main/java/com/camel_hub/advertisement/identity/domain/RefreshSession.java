package com.camel_hub.advertisement.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshSession(
		UUID id,
		UUID userId,
		UUID familyId,
		byte[] tokenHash,
		Instant issuedAt,
		Instant expiresAt,
		Instant rotatedAt,
		Instant revokedAt,
		UUID replacedBy,
		byte[] createdIpHash,
		String userAgentSummary
) {
}
