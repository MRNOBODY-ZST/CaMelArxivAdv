package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.domain.RefreshSession;
import com.camel_hub.advertisement.identity.domain.UserAccount;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.persistence.RefreshTokenRepository;
import com.camel_hub.advertisement.identity.security.RefreshTokenGenerator;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public final class RefreshSessionService {

	private final RefreshTokenRepository repository;
	private final IdentityRepository identityRepository;
	private final RefreshTokenGenerator tokenGenerator;
	private final SensitiveValueHasher hasher;
	private final AuthProperties properties;
	private final TransactionalOperator transactions;
	private final Clock clock;

	public RefreshSessionService(
			RefreshTokenRepository repository,
			IdentityRepository identityRepository,
			RefreshTokenGenerator tokenGenerator,
			SensitiveValueHasher hasher,
			AuthProperties properties,
			TransactionalOperator transactions
	) {
		this(repository, identityRepository, tokenGenerator, hasher, properties, transactions, Clock.systemUTC());
	}

	RefreshSessionService(
			RefreshTokenRepository repository,
			IdentityRepository identityRepository,
			RefreshTokenGenerator tokenGenerator,
			SensitiveValueHasher hasher,
			AuthProperties properties,
			TransactionalOperator transactions,
			Clock clock
	) {
		this.repository = repository;
		this.identityRepository = identityRepository;
		this.tokenGenerator = tokenGenerator;
		this.hasher = hasher;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
	}

	public Mono<IssuedRefreshSession> issue(UUID userId, AuthenticationRequestContext context) {
		Instant now = clock.instant();
		UUID familyId = UUID.randomUUID();
		RefreshTokenGenerator.GeneratedRefreshToken generated = tokenGenerator.generate();
		return repository.create(
				userId,
				familyId,
				generated.hash(),
				now,
				now.plus(properties.refreshTokenTtl()),
				ipHash(context.ipAddress()),
				context.userAgentSummary())
				.map(id -> new IssuedRefreshSession(
						id, familyId, generated.rawValue(), now.plus(properties.refreshTokenTtl())));
	}

	public Mono<RotatedRefreshSession> rotate(String rawToken, AuthenticationRequestContext context) {
		byte[] tokenHash;
		try {
			tokenHash = tokenGenerator.hash(rawToken);
		}
		catch (IllegalArgumentException exception) {
			return Mono.error(new InvalidRefreshTokenException());
		}
		Mono<RotationOutcome> work = repository.lockByTokenHash(tokenHash)
				.flatMap(session -> rotateLocked(session, context))
				.switchIfEmpty(Mono.just(RotationOutcome.invalid()));
		return transactions.transactional(work)
				.flatMap(outcome -> outcome.value() == null
						? Mono.error(new InvalidRefreshTokenException(
								outcome.userId(), outcome.familyId(), outcome.replay()))
						: Mono.just(outcome.value()));
	}

	public Mono<UUID> revoke(String rawToken) {
		byte[] tokenHash;
		try {
			tokenHash = tokenGenerator.hash(rawToken);
		}
		catch (IllegalArgumentException exception) {
			return Mono.empty();
		}
		Mono<UUID> work = repository.lockByTokenHash(tokenHash)
				.flatMap(session -> repository.revokeFamily(session.familyId(), clock.instant())
						.thenReturn(session.userId()));
		return transactions.transactional(work);
	}

	public Mono<Void> revokeAll(UUID userId) {
		return repository.revokeAllForUser(userId, clock.instant());
	}

	private Mono<RotationOutcome> rotateLocked(
			RefreshSession session,
			AuthenticationRequestContext context
	) {
		Instant now = clock.instant();
		if (session.rotatedAt() != null || session.replacedBy() != null) {
			return repository.revokeFamily(session.familyId(), now)
					.thenReturn(RotationOutcome.invalid(session, true));
		}
		if (session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
			return Mono.just(RotationOutcome.invalid(session, false));
		}
		return identityRepository.findById(session.userId())
				.filter(account -> account.status() == UserStatus.ACTIVE)
				.flatMap(account -> createReplacement(session, account, context, now))
				.switchIfEmpty(repository.revokeFamily(session.familyId(), now)
						.thenReturn(RotationOutcome.invalid(session, false)));
	}

	private Mono<RotationOutcome> createReplacement(
			RefreshSession current,
			UserAccount account,
			AuthenticationRequestContext context,
			Instant now
	) {
		RefreshTokenGenerator.GeneratedRefreshToken replacement = tokenGenerator.generate();
		Instant expiresAt = now.plus(properties.refreshTokenTtl());
		return repository.create(
				account.id(), current.familyId(), replacement.hash(), now, expiresAt,
				ipHash(context.ipAddress()), context.userAgentSummary())
				.flatMap(replacementId -> repository.markRotated(current.id(), replacementId, now)
						.thenReturn(RotationOutcome.success(new RotatedRefreshSession(
								replacementId, current.familyId(), replacement.rawValue(), expiresAt, account))));
	}

	private byte[] ipHash(String ipAddress) {
		return hasher.hash(ipAddress == null ? "unknown" : ipAddress.strip());
	}

	public record IssuedRefreshSession(UUID id, UUID familyId, String rawToken, Instant expiresAt) {
	}

	public record RotatedRefreshSession(
			UUID id,
			UUID familyId,
			String rawToken,
			Instant expiresAt,
			UserAccount account
	) {
	}

	private record RotationOutcome(
			RotatedRefreshSession value,
			UUID userId,
			UUID familyId,
			boolean replay
	) {
		static RotationOutcome success(RotatedRefreshSession value) {
			return new RotationOutcome(value, value.account().id(), value.familyId(), false);
		}

		static RotationOutcome invalid() {
			return new RotationOutcome(null, null, null, false);
		}

		static RotationOutcome invalid(RefreshSession session, boolean replay) {
			return new RotationOutcome(null, session.userId(), session.familyId(), replay);
		}
	}
}
