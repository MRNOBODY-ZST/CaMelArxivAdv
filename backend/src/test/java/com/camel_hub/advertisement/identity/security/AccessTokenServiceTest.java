package com.camel_hub.advertisement.identity.security;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenServiceTest {

	private static final byte[] SIGNING_KEY = "0123456789abcdef0123456789abcdef".getBytes();

	@Test
	void issuesSignedShortLivedTokenWithAuthorizationClaimsOnly() {
		Instant now = Instant.parse("2026-08-05T08:00:00Z");
		AuthProperties properties = properties();
		AccessTokenService service = new AccessTokenService(
				properties, Clock.fixed(now, ZoneOffset.UTC));
		UUID userId = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");
		AuthenticatedUser user = new AuthenticatedUser(
				userId,
				"admin",
				"Platform Administrator",
				Set.of("SUPER_ADMIN"),
				Set.of("user:read", "system:manage"),
				true,
				3);

		AccessTokenService.IssuedAccessToken issued = service.issue(user);
		var key = new SecretKeySpec(SIGNING_KEY, "HmacSHA256");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
		Jwt token = decoder.decode(issued.value());

		assertThat(token.getClaimAsString("iss")).isEqualTo("camel-arxiv");
		assertThat(token.getSubject()).isEqualTo(userId.toString());
		assertThat(token.getIssuedAt()).isEqualTo(now);
		assertThat(token.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
		assertThat(token.getClaimAsString("username")).isEqualTo("admin");
		assertThat(token.getClaimAsStringList("roles")).containsExactly("SUPER_ADMIN");
		assertThat(token.getClaimAsStringList("permissions"))
				.containsExactlyInAnyOrder("user:read", "system:manage");
		assertThat(token.getClaimAsBoolean("mustChangePassword")).isTrue();
		assertThat(token.getClaimAsString("email")).isNull();
		assertThat(token.getClaimAsString("displayName")).isNull();
		assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
		assertThat(issued.expiresInSeconds()).isEqualTo(600);
	}

	private AuthProperties properties() {
		String signingKey = Base64.getEncoder().encodeToString(SIGNING_KEY);
		String fingerprintKey = Base64.getEncoder().encodeToString(new byte[32]);
		return new AuthProperties(
				Duration.ofMinutes(10), Duration.ofDays(14), 5, Duration.ofMinutes(15),
				"camel-arxiv", signingKey, fingerprintKey,
				new AuthProperties.RefreshCookie(true, "Strict", "/api/v1/auth"),
				new AuthProperties.BootstrapAdmin("", "", "", ""));
	}
}
