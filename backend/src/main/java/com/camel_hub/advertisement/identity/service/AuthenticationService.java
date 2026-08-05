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
import com.camel_hub.advertisement.identity.security.PasswordPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class AuthenticationService {

	private final IdentityRepository identityRepository;
	private final LoginRateLimiter rateLimiter;
	private final AuditService auditService;
	private final PasswordEncoder passwordEncoder;
	private final AccessTokenService accessTokenService;
	private final RefreshSessionService refreshSessions;
	private final PasswordPolicy passwordPolicy;
	private final TransactionalOperator transactions;
	private final String dummyPasswordHash;

	public AuthenticationService(
			IdentityRepository identityRepository,
			LoginRateLimiter rateLimiter,
			AuditService auditService,
			PasswordEncoder passwordEncoder,
			AccessTokenService accessTokenService,
			RefreshSessionService refreshSessions,
			PasswordPolicy passwordPolicy,
			TransactionalOperator transactions
	) {
		this.identityRepository = identityRepository;
		this.rateLimiter = rateLimiter;
		this.auditService = auditService;
		this.passwordEncoder = passwordEncoder;
		this.accessTokenService = accessTokenService;
		this.refreshSessions = refreshSessions;
		this.passwordPolicy = passwordPolicy;
		this.transactions = transactions;
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
		return rateLimiter.record(principal, context.ipAddress(), true, null)
				.then(identityRepository.updateLastLogin(account.id()))
				.then(auditService.record(new AuditEvent(
						account.id(), "AUTH_LOGIN_SUCCESS", "USER", account.id().toString(),
						rateLimiter.ipHash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
						Map.of(), Map.of("status", "AUTHENTICATED"), AuditResult.SUCCESS, null)))
				.then(refreshSessions.issue(account.id(), context))
				.map(refresh -> new AuthenticationResult(
						accessTokenService.issue(user), user, refresh.rawToken()));
	}

	public Mono<AuthenticationResult> refresh(String rawToken, AuthenticationRequestContext context) {
		return refreshSessions.rotate(rawToken, context)
				.flatMap(rotated -> {
					AuthenticatedUser user = AuthenticatedUser.from(rotated.account());
					return auditService.record(new AuditEvent(
							user.id(), "AUTH_REFRESH_SUCCESS", "REFRESH_FAMILY", rotated.familyId().toString(),
							rateLimiter.ipHash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
							Map.of(), Map.of("status", "ROTATED"), AuditResult.SUCCESS, null))
							.thenReturn(new AuthenticationResult(
									accessTokenService.issue(user), user, rotated.rawToken()));
				})
				.onErrorResume(InvalidRefreshTokenException.class, exception ->
						auditRefreshFailure(exception, context).then(Mono.error(exception)));
	}

	private Mono<Void> auditRefreshFailure(
			InvalidRefreshTokenException exception,
			AuthenticationRequestContext context
	) {
		if (exception.userId() == null || exception.familyId() == null) {
			return Mono.empty();
		}
		String action = exception.replay() ? "AUTH_REFRESH_REPLAY" : "AUTH_REFRESH_FAILURE";
		AuditResult result = exception.replay() ? AuditResult.DENIED : AuditResult.FAILURE;
		return auditService.record(new AuditEvent(
				exception.userId(), action, "REFRESH_FAMILY", exception.familyId().toString(),
				rateLimiter.ipHash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
				Map.of(), Map.of("status", "REJECTED"), result,
				exception.replay() ? "TOKEN_REPLAY" : "INVALID_SESSION"));
	}

	public Mono<Void> logout(String rawToken, AuthenticationRequestContext context) {
		return refreshSessions.revoke(rawToken)
				.flatMap(userId -> auditService.record(new AuditEvent(
						userId, "AUTH_LOGOUT", "USER", userId.toString(),
						rateLimiter.ipHash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
						Map.of(), Map.of("status", "REVOKED"), AuditResult.SUCCESS, null)))
				.then();
	}

	public Mono<Void> changePassword(
			UUID userId,
			String currentPassword,
			String newPassword,
			AuthenticationRequestContext context
	) {
		return identityRepository.findById(userId)
				.switchIfEmpty(Mono.error(new AuthenticationFailedException()))
				.flatMap(account -> passwordMatches(currentPassword, account.passwordHash())
						.flatMap(matches -> matches && account.status() == UserStatus.ACTIVE
								? validateAndChangePassword(account, newPassword, context)
								: passwordChangeFailed(account, context)));
	}

	private Mono<Void> validateAndChangePassword(
			UserAccount account,
			String newPassword,
			AuthenticationRequestContext context
	) {
		Mono<String> encodedPassword = Mono.fromCallable(() -> {
			try {
				passwordPolicy.validate(newPassword, account.username(), account.email());
			}
			catch (IllegalArgumentException exception) {
				throw new PasswordPolicyViolationException(exception.getMessage());
			}
			return passwordEncoder.encode(newPassword);
		}).subscribeOn(Schedulers.boundedElastic());

		return encodedPassword.flatMap(encoded -> transactions.transactional(
				identityRepository.updatePasswordIfUnchanged(
							account.id(), account.passwordHash(), account.tokenVersion(), encoded)
						.flatMap(updated -> updated
								? refreshSessions.revokeAll(account.id())
								: Mono.error(new AuthenticationFailedException()))
						.then(auditService.record(new AuditEvent(
								account.id(), "AUTH_PASSWORD_CHANGE", "USER", account.id().toString(),
								rateLimiter.ipHash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
								Map.of("tokenVersion", account.tokenVersion()),
								Map.of("tokenVersion", account.tokenVersion() + 1, "sessions", "REVOKED"),
								AuditResult.SUCCESS, null)))));
	}

	private Mono<Void> passwordChangeFailed(
			UserAccount account,
			AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				account.id(), "AUTH_PASSWORD_CHANGE", "USER", account.id().toString(),
				rateLimiter.ipHash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
				Map.of(), Map.of("status", "REJECTED"), AuditResult.FAILURE, "BAD_CREDENTIALS"))
				.then(Mono.error(new AuthenticationFailedException()));
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
				.then(rateLimiter.isBlocked(principal, context.ipAddress()))
				.flatMap(blocked -> {
					String auditedReason = blocked ? "RATE_LIMITED" : reason;
					AuditResult result = blocked ? AuditResult.DENIED : AuditResult.FAILURE;
					RuntimeException failure = blocked
							? new LoginRateLimitedException()
							: new AuthenticationFailedException();
					return auditFailure(account, principal, context, auditedReason, result)
							.then(Mono.error(failure));
				});
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
