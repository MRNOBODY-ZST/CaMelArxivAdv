package com.camel_hub.advertisement.identity.domain;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(
		UUID id,
		String username,
		String displayName,
		Set<String> roles,
		Set<String> permissions,
		boolean mustChangePassword,
		int tokenVersion
) {

	public static AuthenticatedUser from(UserAccount account) {
		return new AuthenticatedUser(
				account.id(),
				account.username(),
				account.displayName(),
				account.roles(),
				account.permissions(),
				account.forcePasswordChange(),
				account.tokenVersion());
	}
}
