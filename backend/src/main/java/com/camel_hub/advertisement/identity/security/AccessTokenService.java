package com.camel_hub.advertisement.identity.security;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;

public final class AccessTokenService {

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private final AuthProperties properties;
	private final Clock clock;
	private final JwtEncoder encoder;

	public AccessTokenService(AuthProperties properties) {
		this(properties, Clock.systemUTC());
	}

	AccessTokenService(AuthProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		var secretKey = new SecretKeySpec(properties.decodedSigningKey(), HMAC_ALGORITHM);
		this.encoder = NimbusJwtEncoder.withSecretKey(secretKey)
				.algorithm(MacAlgorithm.HS256)
				.build();
	}

	public IssuedAccessToken issue(AuthenticatedUser user) {
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.issuer())
				.subject(user.id().toString())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.claim("username", user.username())
				.claim("roles", user.roles())
				.claim("permissions", user.permissions())
				.claim("tokenVersion", user.tokenVersion())
				.claim("mustChangePassword", user.mustChangePassword())
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedAccessToken(value, expiresAt, properties.accessTokenTtl().toSeconds());
	}

	public record IssuedAccessToken(String value, Instant expiresAt, long expiresInSeconds) {
	}
}
