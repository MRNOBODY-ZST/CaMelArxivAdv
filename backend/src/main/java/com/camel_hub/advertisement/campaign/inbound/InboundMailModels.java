package com.camel_hub.advertisement.campaign.inbound;

import java.time.Instant;
import java.util.List;

public final class InboundMailModels {
	private InboundMailModels() { }

	public enum InboundType {
		REPLY, AUTO_REPLY, BOUNCE, UNMATCHED
	}

	/** Header-only mailbox input plus the bounded structured fields of a delivery report. */
	public record InboundEnvelope(
			long remoteUid, String messageId, String inReplyTo, String references,
			String autoSubmitted, String contentType, Instant receivedAt,
			DsnFields dsn, boolean malformed
	) { }

	public record DsnFields(
			String action, String status, String diagnosticCode, String originalMessageId
	) { }

	public record MailboxRead(long uidValidity, long cursorFloor, List<InboundEnvelope> envelopes) {
		public MailboxRead(long uidValidity, List<InboundEnvelope> envelopes) {
			this(uidValidity, 0, envelopes);
		}

		public MailboxRead {
			if (uidValidity < 0 || cursorFloor < 0) {
				throw new IllegalArgumentException("Mailbox UID identity values must be nonnegative");
			}
			envelopes = envelopes == null ? List.of() : List.copyOf(envelopes);
		}
	}

	public record ParsedInbound(
			InboundType type, List<String> referencedMessageIds,
			String diagnosticCode, Boolean permanent
	) {
		public ParsedInbound {
			referencedMessageIds = referencedMessageIds == null
					? List.of() : List.copyOf(referencedMessageIds);
		}
	}
}
