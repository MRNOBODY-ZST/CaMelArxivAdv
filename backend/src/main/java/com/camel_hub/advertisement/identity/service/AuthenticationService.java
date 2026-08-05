package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.domain.UserAccount;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.security.AccessTokenService;
import com.camel_hub.advertisement.identity.security.LoginRateLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;
import java.util.Map;

public class AuthenticationService {

	private final IdentityRepository identityRepository;
	private final LoginRateLimiter rateLimiter;
	private final AuditService auditService;
	private final PasswordEncoder passwordEncoder;
	private final AccessTokenService accessTokenService;
	private final String dummyPasswordHash;

	public AuthenticationService(
			IdentityRepository identityRepository,
			LoginRateLimiter rateLimiter,
			AuditService auditService,
			PasswordEncoder passwordEncoder,
			AccessTokenService accessTokenService
	) {
		this.identityRepository = identityRepository;
		this.rateLimiter = rateLimiter;
		this.auditService = auditService;
		this.passwordEncoder = passwordEncoder;
		this.accessTokenService = accessTokenService;
		this.dummyPasswordHash = passwordEncoder.encode("Dummy!Credential92");
	}

	public Mono<AuthenticationResult> login(
			String principal,
			String password,
			AuthenticationRequestContext context
	) {
		String normalizedPrincipal = principal.strip().toLowerCase(Locale.ROOT);
		return rateLimiter.isBlocked(normalizedPrincipal, context.ipAddress())
				.flatMap(blocked -> blocked
						? auditFailure(null, normalizedPrincipal, context, "RATE_LIMITED", AuditResult.DENIED)
								.then(Mono.error(new LoginRateLimitedException()))
						: authenticate(normalizedPrincipal, password, context));
	}

	private Mono<AuthenticationResult> authenticate(
			String principal,
			String password,
			AuthenticationRequestContext context
	) {
		return identityRepository.findByPrincipal(principal)
				.flatMap(account -> passwordMatches(password, account.passwordHash())
						.flatMap(matches -> matches && account.status() == UserStatus.ACTIVE
								? loginSucceeded(account, principal, context)
								: loginFailed(account, principal, context)))
				.switchIfEmpty(Mono.defer(() -> passwordMatches(password, dummyPasswordHash)
						.then(loginFailed(null, principal, context))));
	}

	private Mono<AuthenticationResult> loginSucceeded(
			UserAccount account,
			String principal,
			AuthenticationRequestContext context
	) {
		AuthenticatedUser user = AuthenticatedUser.from(account);
		AuthenticationResult result = new AuthenticationResult(accessTokenService.issue(user), user);
		return rateLimiter.record(principal, context.ipAddress(), true, null)
				.then(identityRepository.updateLastLogin(account.id()))
				.then(auditService.record(new AuditEvent(
						account.id(), "AUTH_LOGIN_SUCCESS", "USER", account.id().toString(),
						rateLimiter.ipHash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
						Map.of(), Map.of("status", "AUTHENTICATED"), AuditResult.SUCCESS, null)))
				.thenReturn(result);
	}

	private Mono<AuthenticationResult> loginFailed(
			UserAccount account,
			String principal,
			AuthenticationRequestContext context
	) {
		String reason = account != null && account.status() != UserStatus.ACTIVE
				? "USER_NOT_ACTIVE"
				: "BAD_CREDENTIALS";
		return rateLimiter.record(principal, context.ipAddress(), false, reason)
				.then(auditFailure(account, principal, context, reason, AuditResult.FAILURE))
				.then(Mono.error(new AuthenticationFailedException()));
	}

	private Mono<Void> auditFailure(
			UserAccount account,
			String principal,
			AuthenticationRequestContext context,
			String reason,
			AuditResult result
	) {
		return auditService.record(new AuditEvent(
				account == null ? null : account.id(),
				"AUTH_LOGIN_FAILURE",
				"USER",
				account == null ? null : account.id().toString(),
				rateLimiter.ipHash(context.ipAddress()),
				context.userAgentSummary(),
				context.traceId(),
				Map.of("principal", "[REDACTED]"),
				Map.of("status", "REJECTED"),
				result,
				reason));
	}

	private Mono<Boolean> passwordMatches(String rawPassword, String encodedPassword) {
		return Mono.fromCallable(() -> passwordEncoder.matches(rawPassword, encodedPassword))
				.subscribeOn(Schedulers.boundedElastic());
	}
}
