package com.camel_hub.advertisement.identity.security;

import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.domain.UserStatus;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

import java.util.UUID;

public final class LiveUserJwtAuthenticationConverter
		implements Converter<Jwt, Mono<UsernamePasswordAuthenticationToken>> {

	private final IdentityRepository identityRepository;

	public LiveUserJwtAuthenticationConverter(IdentityRepository identityRepository) {
		this.identityRepository = identityRepository;
	}

	@Override
	public Mono<UsernamePasswordAuthenticationToken> convert(Jwt jwt) {
		UUID userId;
		int tokenVersion;
		try {
			userId = UUID.fromString(jwt.getSubject());
			Number versionClaim = jwt.getClaim("tokenVersion");
			if (versionClaim == null) {
				return invalid();
			}
			tokenVersion = versionClaim.intValue();
		}
		catch (RuntimeException exception) {
			return invalid();
		}

		return identityRepository.findById(userId)
				.filter(account -> account.status() == UserStatus.ACTIVE)
				.filter(account -> account.tokenVersion() == tokenVersion)
				.map(account -> {
					AuthenticatedUser user = AuthenticatedUser.from(account);
					var authorities = user.permissions().stream()
							.map(SimpleGrantedAuthority::new)
							.toList();
					return UsernamePasswordAuthenticationToken.authenticated(user, jwt, authorities);
				})
				.switchIfEmpty(invalid());
	}

	private <T> Mono<T> invalid() {
		return Mono.error(new BadCredentialsException("Bearer token is no longer valid"));
	}
}
