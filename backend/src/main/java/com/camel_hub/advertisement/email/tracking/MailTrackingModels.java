package com.camel_hub.advertisement.email.tracking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MailTrackingModels {
	private MailTrackingModels() { }

	public enum Source { SMTP_DIAGNOSTIC, TEMPLATE_TEST }
	public enum Status { SENDING, SMTP_ACCEPTED, FAILED, UNKNOWN }
	public enum Classification { UNCLASSIFIED, PREFETCH, IMAGE_PROXY, BOT }
	public enum CallbackScope { LOCAL_ONLY, PUBLIC_HTTPS_CONFIGURED }

	public record TrackingStatus(
			boolean enabled, String callbackBaseUrl, CallbackScope callbackScope, long tokenTtlSeconds
	) { }

	public record MailSendRecord(
			UUID id, Source source, String recipientMasked, String subject, String smtpAccountName,
			Status status, String failureCategory, boolean trackingEnabled, Instant createdAt,
			Instant completedAt, Instant trackingExpiresAt, long rawOpenCount, long automatedOpenCount,
			Instant firstOpenAt, Instant lastOpenAt, long rawClickCount, long automatedClickCount,
			Instant firstClickAt, Instant lastClickAt
	) { }

	public record MailOpenEvent(long id, Instant occurredAt, Classification classification, String reason) { }

	public record PendingClickLink(
			UUID id, String targetUrl, String label, int position, byte[] tokenHash, Instant expiresAt
	) {
		public PendingClickLink {
			tokenHash = tokenHash.clone();
		}

		@Override
		public byte[] tokenHash() {
			return tokenHash.clone();
		}
	}

	public record MailClickLink(
			UUID id, String targetUrl, String label, int position, long rawClickCount, long automatedClickCount,
			Instant firstClickAt, Instant lastClickAt
	) { }

	public record MailClickEvent(
			long id, UUID linkId, Instant occurredAt, Classification classification, String reason
	) { }

	public record ResolvedClick(UUID recordId, UUID linkId, String targetUrl) { }

	public record Detail(
			MailSendRecord record, List<MailOpenEvent> events, List<MailClickLink> links, List<MailClickEvent> clickEvents
	) {
		public Detail {
			events = List.copyOf(events);
			links = List.copyOf(links);
			clickEvents = List.copyOf(clickEvents);
		}
	}
}
