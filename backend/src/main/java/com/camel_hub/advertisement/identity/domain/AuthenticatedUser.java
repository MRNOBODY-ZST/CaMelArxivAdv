package com.camel_hub.advertisement.identity.domain;

import java.security.Principal;
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
) implements Principal {

	public static AuthenticatedUser from(UserAccount account) {
		Set<String> effectivePermissions = account.forcePasswordChange()
				? Set.of()
				: account.permissions();
		return new AuthenticatedUser(
				account.id(),
				account.username(),
				account.displayName(),
				account.roles(),
				effectivePermissions,
				account.forcePasswordChange(),
				account.tokenVersion());
	}

	@Override
	public String getName() {
		return id.toString();
	}
}
