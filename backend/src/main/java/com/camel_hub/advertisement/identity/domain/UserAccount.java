package com.camel_hub.advertisement.identity.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public record UserAccount(
		UUID id,
		String username,
		String email,
		String passwordHash,
		String displayName,
		UserStatus status,
		boolean forcePasswordChange,
		int tokenVersion,
		Instant lastLoginAt,
		Instant passwordChangedAt,
		Set<String> roles,
		Set<String> permissions
) {

	public UserAccount {
		roles = immutableSorted(roles);
		permissions = immutableSorted(permissions);
	}

	private static Set<String> immutableSorted(Set<String> values) {
		return Collections.unmodifiableSortedSet(new TreeSet<>(values));
	}
}
