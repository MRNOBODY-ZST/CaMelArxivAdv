package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.domain.UserAccount;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.security.AccessTokenService;
import com.camel_hub.advertisement.identity.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

	@Mock
	private IdentityRepository identityRepository;
	@Mock
	private LoginRateLimiter rateLimiter;
	@Mock
	private AuditService auditService;
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private AccessTokenService accessTokenService;

	private AuthenticationService service;
	private AuthenticationRequestContext context;

	@BeforeEach
	void setUp() {
		when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
		service = new AuthenticationService(
				identityRepository, rateLimiter, auditService, passwordEncoder, accessTokenService);
		context = new AuthenticationRequestContext("192.0.2.10", "test-agent", "trace-auth-1");
	}

	@Test
	void logsInByNormalizedUsernameAndReturnsAuthorizationSnapshot() {
		UserAccount account = account(UserStatus.ACTIVE);
		var token = new AccessTokenService.IssuedAccessToken(
				"signed-access-token", Instant.parse("2026-08-05T08:10:00Z"), 600);
		when(rateLimiter.isBlocked("admin", context.ipAddress())).thenReturn(Mono.just(false));
		when(identityRepository.findByPrincipal("admin")).thenReturn(Mono.just(account));
		when(passwordEncoder.matches("Correct!Password92", account.passwordHash())).thenReturn(true);
		when(rateLimiter.record("admin", context.ipAddress(), true, null)).thenReturn(Mono.empty());
		when(identityRepository.updateLastLogin(account.id())).thenReturn(Mono.empty());
		when(auditService.record(any())).thenReturn(Mono.empty());
		when(accessTokenService.issue(any())).thenReturn(token);

		StepVerifier.create(service.login("  AdMiN ", "Correct!Password92", context))
				.assertNext(result -> {
					assertThat(result.accessToken()).isEqualTo(token);
					assertThat(result.user().username()).isEqualTo("admin");
					assertThat(result.user().permissions()).contains("system:manage");
				})
				.verifyComplete();

		verify(identityRepository).findByPrincipal("admin");
		verify(identityRepository).updateLastLogin(account.id());
		ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
		verify(auditService).record(event.capture());
		assertThat(event.getValue().action()).isEqualTo("AUTH_LOGIN_SUCCESS");
	}

	@Test
	void emailPrincipalUsesTheSameCaseInsensitivePath() {
		when(rateLimiter.isBlocked("admin@example.org", context.ipAddress())).thenReturn(Mono.just(false));
		when(identityRepository.findByPrincipal("admin@example.org")).thenReturn(Mono.empty());
		when(passwordEncoder.matches("wrong", "dummy-hash")).thenReturn(false);
		when(rateLimiter.record("admin@example.org", context.ipAddress(), false, "BAD_CREDENTIALS"))
				.thenReturn(Mono.empty());
		when(auditService.record(any())).thenReturn(Mono.empty());

		StepVerifier.create(service.login("Admin@Example.ORG", "wrong", context))
				.expectError(AuthenticationFailedException.class)
				.verify();

		verify(identityRepository).findByPrincipal("admin@example.org");
	}

	@Test
	void disabledAccountAndBadPasswordShareTheSameExternalFailure() {
		UserAccount account = account(UserStatus.DISABLED);
		when(rateLimiter.isBlocked("admin", context.ipAddress())).thenReturn(Mono.just(false));
		when(identityRepository.findByPrincipal("admin")).thenReturn(Mono.just(account));
		when(passwordEncoder.matches("Correct!Password92", account.passwordHash())).thenReturn(true);
		when(rateLimiter.record("admin", context.ipAddress(), false, "USER_NOT_ACTIVE"))
				.thenReturn(Mono.empty());
		when(auditService.record(any())).thenReturn(Mono.empty());

		StepVerifier.create(service.login("admin", "Correct!Password92", context))
				.expectError(AuthenticationFailedException.class)
				.verify();

		verify(accessTokenService, never()).issue(any());
		verify(identityRepository, never()).updateLastLogin(any());
	}

	@Test
	void rateLimitedRequestDoesNotQueryIdentityOrCheckPassword() {
		when(rateLimiter.isBlocked("admin", context.ipAddress())).thenReturn(Mono.just(true));
		when(auditService.record(any())).thenReturn(Mono.empty());

		StepVerifier.create(service.login("admin", "anything", context))
				.expectError(LoginRateLimitedException.class)
				.verify();

		verify(identityRepository, never()).findByPrincipal(anyString());
		verify(passwordEncoder, never()).matches(anyString(), anyString());
	}

	private UserAccount account(UserStatus status) {
		return new UserAccount(
				UUID.fromString("5d3a9802-375f-42ee-9739-d419299bc4a8"),
				"admin", "admin@example.org", "encoded-password", "Administrator", status,
				false, 2, null, Instant.parse("2026-08-05T07:00:00Z"),
				Set.of("SUPER_ADMIN"), Set.of("system:manage", "user:read"));
	}
}
