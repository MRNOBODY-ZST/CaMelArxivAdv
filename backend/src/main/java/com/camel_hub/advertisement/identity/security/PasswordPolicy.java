package com.camel_hub.advertisement.identity.security;

import java.util.Locale;

public final class PasswordPolicy {

	private static final int MINIMUM_LENGTH = 12;

	public void validate(String password, String username, String email) {
		if (password == null || password.length() < MINIMUM_LENGTH) {
			throw new IllegalArgumentException("password must contain at least 12 characters");
		}
		if (password.chars().noneMatch(Character::isUpperCase)) {
			throw new IllegalArgumentException("password must contain an uppercase character");
		}
		if (password.chars().noneMatch(Character::isLowerCase)) {
			throw new IllegalArgumentException("password must contain a lowercase character");
		}
		if (password.chars().noneMatch(Character::isDigit)) {
			throw new IllegalArgumentException("password must contain a digit");
		}
		if (password.chars().allMatch(Character::isLetterOrDigit)) {
			throw new IllegalArgumentException("password must contain a symbol");
		}

		String normalizedPassword = password.toLowerCase(Locale.ROOT);
		String normalizedUsername = normalizeIdentityPart(username);
		String emailLocalPart = email == null ? "" : normalizeIdentityPart(email.split("@", 2)[0]);
		if ((!normalizedUsername.isBlank() && normalizedPassword.contains(normalizedUsername))
				|| (!emailLocalPart.isBlank() && normalizedPassword.contains(emailLocalPart))) {
			throw new IllegalArgumentException("password must not contain identity values");
		}
	}

	private String normalizeIdentityPart(String value) {
		return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
	}
}
