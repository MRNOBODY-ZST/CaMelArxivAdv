package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;
import java.util.UUID;

public final class AuthDtos {

	private AuthDtos() {
	}

	public record LoginRequest(
			@NotBlank String principal,
			@NotBlank String password
	) {
	}

	public record LoginResponse(
			String accessToken,
			String tokenType,
			long expiresInSeconds,
			CurrentUserResponse user
	) {
	}

	public record CurrentUserResponse(
			UUID id,
			String username,
			String displayName,
			Set<String> roles,
			Set<String> permissions,
			boolean mustChangePassword
	) {
		public static CurrentUserResponse from(AuthenticatedUser user) {
			return new CurrentUserResponse(
					user.id(), user.username(), user.displayName(), user.roles(), user.permissions(),
					user.mustChangePassword());
		}
	}
}
