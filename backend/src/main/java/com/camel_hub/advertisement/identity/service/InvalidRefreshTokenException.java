package com.camel_hub.advertisement.identity.service;

import java.util.UUID;

public final class InvalidRefreshTokenException extends RuntimeException {
	private final UUID userId;
	private final UUID familyId;
	private final boolean replay;

	public InvalidRefreshTokenException() {
		this(null, null, false);
	}

	public InvalidRefreshTokenException(UUID userId, UUID familyId, boolean replay) {
		super("Session is invalid or expired");
		this.userId = userId;
		this.familyId = familyId;
		this.replay = replay;
	}

	public UUID userId() {
		return userId;
	}

	public UUID familyId() {
		return familyId;
	}

	public boolean replay() {
		return replay;
	}
}
