package com.camel_hub.advertisement.campaign.inbound;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InboundMailParserTest {
	private static final String PRODUCTION =
			"<10000000-0000-0000-0000-000000000001@delivery.camel-arxiv.invalid>";
	private static final String SAFETY =
			"<safety-20000000-0000-0000-0000-000000000002@delivery.camel-arxiv.invalid>";
	private final InboundMailParser parser = new InboundMailParser();

	@Test
	void classifiesHumanAndAutomaticRepliesOnlyFromControlledReferences() {
		InboundMailModels.ParsedInbound human = parser.classify(envelope(
				PRODUCTION, "<unrelated@example.test> " + SAFETY, null, null));
		assertThat(human.type()).isEqualTo(InboundMailModels.InboundType.REPLY);
		assertThat(human.referencedMessageIds()).containsExactly(PRODUCTION, SAFETY);
		assertThat(human.diagnosticCode()).isNull();
		assertThat(human.permanent()).isNull();

		InboundMailModels.ParsedInbound automatic = parser.classify(envelope(
				SAFETY, null, "auto-replied", null));
		assertThat(automatic.type()).isEqualTo(InboundMailModels.InboundType.AUTO_REPLY);
		assertThat(automatic.referencedMessageIds()).containsExactly(SAFETY);

		InboundMailModels.ParsedInbound unrelated = parser.classify(envelope(
				"<10000000-0000-0000-0000-000000000001@attacker.test>", null, null, null));
		assertThat(unrelated.type()).isEqualTo(InboundMailModels.InboundType.UNMATCHED);
		assertThat(unrelated.referencedMessageIds()).isEmpty();
	}

	@Test
	void classifiesPermanentAndTemporaryStructuredDeliveryStatusWithoutBodyData() {
		InboundMailModels.ParsedInbound permanent = parser.classify(envelope(
				null, null, null, new InboundMailModels.DsnFields(
						"failed", "5.1.1",
						"smtp; 550 5.1.1 <private-author@example.org> rejected", PRODUCTION)));
		assertThat(permanent.type()).isEqualTo(InboundMailModels.InboundType.BOUNCE);
		assertThat(permanent.referencedMessageIds()).containsExactly(PRODUCTION);
		assertThat(permanent.permanent()).isTrue();
		assertThat(permanent.diagnosticCode()).isEqualTo("smtp; 550 5.1.1");
		assertThat(permanent.toString()).doesNotContain("private-author", "example.org");

		InboundMailModels.ParsedInbound temporary = parser.classify(envelope(
				null, null, null, new InboundMailModels.DsnFields(
						"delayed", "4.2.0", "smtp; 450 retry later", SAFETY)));
		assertThat(temporary.type()).isEqualTo(InboundMailModels.InboundType.BOUNCE);
		assertThat(temporary.permanent()).isFalse();
		assertThat(temporary.referencedMessageIds()).containsExactly(SAFETY);

		InboundMailModels.ParsedInbound contradictory = parser.classify(envelope(
				null, null, null, new InboundMailModels.DsnFields(
						"failed", "4.2.0", "smtp; 450 contradictory", PRODUCTION)));
		assertThat(contradictory.type()).isEqualTo(InboundMailModels.InboundType.BOUNCE);
		assertThat(contradictory.permanent()).isFalse();

		InboundMailModels.ParsedInbound missingAction = parser.classify(envelope(
				null, null, null, new InboundMailModels.DsnFields(
						null, "5.1.1", "smtp; 550 incomplete", PRODUCTION)));
		assertThat(missingAction.permanent()).isFalse();

		InboundMailModels.ParsedInbound malformedStatus = parser.classify(envelope(
				null, null, null, new InboundMailModels.DsnFields(
						"failed", "5.invalid", "smtp; malformed", PRODUCTION)));
		assertThat(malformedStatus.permanent()).isFalse();
	}

	@Test
	void malformedOrOversizedValuesFailClosedWithoutEchoingThem() {
		String oversized = "x".repeat(2_000);
		InboundMailModels.ParsedInbound malformed = parser.classify(new InboundMailModels.InboundEnvelope(
				9, oversized, oversized, oversized, oversized, oversized, Instant.EPOCH, null, true));
		assertThat(malformed.type()).isEqualTo(InboundMailModels.InboundType.UNMATCHED);
		assertThat(malformed.referencedMessageIds()).isEmpty();
		assertThat(malformed.diagnosticCode()).isNull();

		InboundMailModels.ParsedInbound oversizedStructuredIdentity = parser.classify(envelope(
				PRODUCTION, null, null, new InboundMailModels.DsnFields(
						"failed", "5.1.1", null, "x".repeat(5_000) + SAFETY)));
		assertThat(oversizedStructuredIdentity.type()).isEqualTo(InboundMailModels.InboundType.UNMATCHED);
		assertThat(oversizedStructuredIdentity.referencedMessageIds()).isEmpty();
	}

	@Test
	void twentyControlledReferencesRemainBoundedButATwentyFirstFailsClosed() {
		StringBuilder references = new StringBuilder(PRODUCTION);
		for (int index = 1; index < 20; index++) {
			references.append(" <00000000-0000-0000-0000-%012d@delivery.camel-arxiv.invalid>"
					.formatted(index));
		}
		InboundMailModels.ParsedInbound withinLimit = parser.classify(envelope(
				null, references.toString(), null, null));
		assertThat(withinLimit.type()).isEqualTo(InboundMailModels.InboundType.REPLY);
		assertThat(withinLimit.referencedMessageIds()).hasSize(20);

		InboundMailModels.ParsedInbound overflow = parser.classify(envelope(
				null, references + " " + SAFETY, null, null));
		assertThat(overflow.type()).isEqualTo(InboundMailModels.InboundType.UNMATCHED);
		assertThat(overflow.referencedMessageIds()).isEmpty();
	}

	private InboundMailModels.InboundEnvelope envelope(
			String inReplyTo, String references, String autoSubmitted, InboundMailModels.DsnFields dsn
	) {
		return new InboundMailModels.InboundEnvelope(
				1, "<inbound@example.test>", inReplyTo, references, autoSubmitted,
				"text/plain", Instant.parse("2026-09-03T00:00:00Z"), dsn, false);
	}
}
