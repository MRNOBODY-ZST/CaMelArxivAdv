package com.camel_hub.advertisement.identity.security;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

public final class RefreshCookieFactory {

	public static final String COOKIE_NAME = "refresh_token";
	private final AuthProperties properties;

	public RefreshCookieFactory(AuthProperties properties) {
		this.properties = properties;
	}

	public ResponseCookie issue(String rawToken) {
		return base(rawToken)
				.maxAge(properties.refreshTokenTtl())
				.build();
	}

	public ResponseCookie expire() {
		return base("")
				.maxAge(Duration.ZERO)
				.build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(COOKIE_NAME, value)
				.httpOnly(true)
				.secure(properties.cookie().secure())
				.sameSite(properties.cookie().sameSite())
				.path(properties.cookie().path());
	}
}
