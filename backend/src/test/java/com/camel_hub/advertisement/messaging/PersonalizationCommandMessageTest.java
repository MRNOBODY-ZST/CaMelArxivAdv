package com.camel_hub.advertisement.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalizationCommandMessageTest {

	@Test
	void serializesPublicPaperContextWithoutRecipientOrSmtpSecrets() throws Exception {
		var message = new PersonalizationCommandMessage(
				1, UUID.randomUUID(), "PERSONALIZE_CAMPAIGN", UUID.randomUUID(), UUID.randomUUID(),
				"personalize:test", "tracevalue1", Instant.now(),
				new PersonalizationCommandMessage.Payload(
						"Discuss the work", "About {{paper_title}}", "<p>Reference</p>", "Reference",
						List.of(new PersonalizationCommandMessage.Target(
								UUID.randomUUID(), "Ada Lovelace", "Safe Distributed Intelligence",
								"A public abstract", "2608.00001", "cs.AI",
								"https://arxiv.org/abs/2608.00001", "Analytical Engine University"))));

		String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(message);
		assertThat(json).contains("Ada Lovelace", "Safe Distributed Intelligence", "A public abstract")
				.doesNotContain("recipientEmail", "emailCiphertext", "emailNonce", "emailHmac", "smtpPassword");
	}
}
