package com.camel_hub.advertisement.identity.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PasswordPolicyTest {

	private final PasswordPolicy policy = new PasswordPolicy();

	@Test
	void acceptsAComplexPasswordUnrelatedToTheIdentity() {
		assertThatCode(() -> policy.validate(
				"Maple!Orbit92",
				"research-admin",
				"administrator@example.edu"))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsShortOrSingleClassPasswords() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> policy.validate("Short!2", "admin", "admin@example.edu"))
				.withMessageContaining("12");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> policy.validate("onlylowercasepassword", "admin", "admin@example.edu"))
				.withMessageContaining("uppercase");
	}

	@Test
	void rejectsUsernameAndEmailLocalPartFragments() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> policy.validate(
						"Research-admin!92",
						"research-admin",
						"operator@example.edu"))
				.withMessageContaining("identity");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> policy.validate(
						"Operator!Orbit92",
						"research-admin",
						"operator@example.edu"))
				.withMessageContaining("identity");
	}
}
