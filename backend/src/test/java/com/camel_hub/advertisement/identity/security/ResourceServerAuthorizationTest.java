package com.camel_hub.advertisement.identity.security;

import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.domain.UserAccount;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = {
		"app.auth.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.auth.fingerprint-hmac-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.persistence.enabled=false",
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration,"
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
				+ "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@Import(ResourceServerAuthorizationTest.ProbeConfiguration.class)
class ResourceServerAuthorizationTest {

	private static final UUID USER_ID = UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8");

	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private IdentityRepository identityRepository;
	@Autowired
	private AccessTokenService accessTokenService;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		reset(identityRepository);
		webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
				.apply(springSecurity())
				.build();
	}

	@Test
	void rejectsMissingInvalidAndExpiredBearerTokens() {
		webTestClient.get().uri("/api/v1/auth/me")
				.exchange()
				.expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.type").isEqualTo("authentication_required")
				.jsonPath("$.traceId").exists();
		webTestClient.get().uri("/api/v1/auth/me")
				.headers(headers -> headers.setBearerAuth("not-a-jwt"))
				.exchange().expectStatus().isUnauthorized();
		webTestClient.get().uri("/api/v1/auth/me")
				.headers(headers -> headers.setBearerAuth(expiredToken()))
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void returnsTheCurrentLiveUserForAValidToken() {
		UserAccount account = account(UserStatus.ACTIVE, 1, Set.of("contact:read_masked", "paper:read"));
		when(identityRepository.findById(USER_ID)).thenReturn(Mono.just(account));

		webTestClient.get().uri("/api/v1/auth/me")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").isEqualTo(USER_ID.toString())
				.jsonPath("$.username").isEqualTo("analyst")
				.jsonPath("$.permissions[0]").exists()
				.jsonPath("$.email").doesNotExist();
	}

	@Test
	void immediatelyRejectsDisabledUsersAndPrePasswordChangeTokenVersions() {
		when(identityRepository.findById(USER_ID))
				.thenReturn(Mono.just(account(UserStatus.DISABLED, 1, Set.of("paper:read"))))
				.thenReturn(Mono.just(account(UserStatus.ACTIVE, 2, Set.of("paper:read"))));

		webTestClient.get().uri("/api/v1/auth/me")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange().expectStatus().isUnauthorized();
		webTestClient.get().uri("/api/v1/auth/me")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void enforcesLiveDatabasePermissionsAtMethodLevel() {
		when(identityRepository.findById(USER_ID))
				.thenReturn(Mono.just(account(UserStatus.ACTIVE, 1, Set.of("contact:read_masked"))))
				.thenReturn(Mono.just(account(UserStatus.ACTIVE, 1, Set.of("contact:read_full"))));

		webTestClient.get().uri("/api/v1/test/full-contact")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange().expectStatus().isForbidden();
		webTestClient.get().uri("/api/v1/test/full-contact")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange().expectStatus().isOk();
	}

	@Test
	void enforcesAdministrativePermissionsAtTheHttpBoundary() {
		when(identityRepository.findById(USER_ID))
				.thenReturn(Mono.just(account(UserStatus.ACTIVE, 1, Set.of("paper:read"))))
				.thenReturn(Mono.just(account(UserStatus.ACTIVE, 1, Set.of("user:read"))));

		webTestClient.get().uri("/api/v1/users")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange().expectStatus().isForbidden();
		webTestClient.get().uri("/api/v1/users")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange().expectStatus().isOk();
	}

	@Test
	void deniesBusinessPermissionsUntilTheInitialPasswordIsChanged() {
		when(identityRepository.findById(USER_ID))
				.thenReturn(Mono.just(account(UserStatus.ACTIVE, true, 1, Set.of("contact:read_full"))))
				.thenReturn(Mono.just(account(UserStatus.ACTIVE, true, 1, Set.of("contact:read_full"))));

		webTestClient.get().uri("/api/v1/auth/me")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.mustChangePassword").isEqualTo(true)
				.jsonPath("$.permissions.length()").isEqualTo(0);
		webTestClient.get().uri("/api/v1/test/full-contact")
				.headers(headers -> headers.setBearerAuth(token(1)))
				.exchange().expectStatus().isForbidden();
	}

	private String token(int tokenVersion) {
		AuthenticatedUser user = new AuthenticatedUser(
				USER_ID, "analyst", "Data Analyst", Set.of("DATA_ANALYST"),
				Set.of("contact:read_masked"), false, tokenVersion);
		return accessTokenService.issue(user).value();
	}

	private String expiredToken() {
		byte[] keyBytes = "0123456789abcdef0123456789abcdef".getBytes();
		var key = new SecretKeySpec(keyBytes, "HmacSHA256");
		var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("camel-arxiv")
				.subject(USER_ID.toString())
				.issuedAt(now.minusSeconds(1200))
				.expiresAt(now.minusSeconds(600))
				.claim("tokenVersion", 1)
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
	}

	private UserAccount account(UserStatus status, int tokenVersion, Set<String> permissions) {
		return account(status, false, tokenVersion, permissions);
	}

	private UserAccount account(
			UserStatus status,
			boolean forcePasswordChange,
			int tokenVersion,
			Set<String> permissions
	) {
		return new UserAccount(
				USER_ID, "analyst", "analyst@example.edu", "$2a$12$hash", "Data Analyst",
				status, forcePasswordChange, tokenVersion, null, Instant.now(), Set.of("DATA_ANALYST"), permissions);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfiguration {

		@Bean
		IdentityRepository identityRepository() {
			return mock(IdentityRepository.class);
		}

		@Bean
		PermissionProbeController permissionProbeController() {
			return new PermissionProbeController();
		}
	}

	@RestController
	static class PermissionProbeController {

		@GetMapping("/api/v1/users")
		Mono<String> users() {
			return Mono.just("allowed");
		}

		@GetMapping("/api/v1/test/full-contact")
		@PreAuthorize("hasAuthority('contact:read_full')")
		Mono<String> fullContact() {
			return Mono.just("allowed");
		}
	}
}
