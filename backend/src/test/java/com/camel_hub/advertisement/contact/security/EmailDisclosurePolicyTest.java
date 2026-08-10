package com.camel_hub.advertisement.contact.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailDisclosurePolicyTest {

	private final EmailDisclosurePolicy policy = new EmailDisclosurePolicy();

	@Test
	void returnsFullEmailOnlyWithTheExplicitFullReadPermission() {
		assertThat(policy.disclose("john.doe@example.edu", Set.of("contact:read_full")))
				.isEqualTo("john.doe@example.edu");
	}

	@Test
	void masksTheLocalPartForMaskedReaders() {
		assertThat(policy.disclose("john.doe@example.edu", Set.of("contact:read_masked")))
				.isEqualTo("jo***@example.edu");
		assertThat(policy.disclose("a@example.edu", Set.of("contact:read_masked")))
				.isEqualTo("a***@example.edu");
	}

	@Test
	void deniesCallersWithoutAnyContactReadPermission() {
		assertThatThrownBy(() -> policy.disclose("john.doe@example.edu", Set.of("paper:read")))
				.isInstanceOf(AccessDeniedException.class);
	}
}
