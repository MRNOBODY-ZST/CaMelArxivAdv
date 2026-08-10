package com.camel_hub.advertisement.identity.bootstrap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Mono;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.security.PasswordPolicy;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialAdminBootstrapTest {

	@Test
	void hashesAndCreatesConfiguredAdministratorWithoutExposingTheRawPassword() {
		IdentityRepository repository = mock(IdentityRepository.class);
		when(repository.createInitialAdmin(anyString(), anyString(), anyString(), anyString()))
				.thenReturn(Mono.just(true));
		var encoder = new BCryptPasswordEncoder(12);
		var bootstrap = new InitialAdminBootstrap(repository, encoder, new PasswordPolicy(), properties(
				new AuthProperties.BootstrapAdmin(
						"admin", "admin@example.invalid", "Administrator", "Maple!Orbit92")));

		assertThat(bootstrap.initialize().block()).isTrue();

		ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
		verify(repository).createInitialAdmin(
				org.mockito.ArgumentMatchers.eq("admin"),
				org.mockito.ArgumentMatchers.eq("admin@example.invalid"),
				org.mockito.ArgumentMatchers.eq("Administrator"),
				hash.capture());
		assertThat(hash.getValue()).startsWith("$2").doesNotContain("Maple!Orbit92");
		assertThat(encoder.matches("Maple!Orbit92", hash.getValue())).isTrue();
	}

	@Test
	void skipsBootstrapWhenCredentialsAreIncomplete() {
		IdentityRepository repository = mock(IdentityRepository.class);
		var bootstrap = new InitialAdminBootstrap(
				repository,
				new BCryptPasswordEncoder(12),
				new PasswordPolicy(),
				properties(new AuthProperties.BootstrapAdmin("admin", "", "Administrator", "")));

		assertThat(bootstrap.initialize().block()).isFalse();
		verify(repository, never()).createInitialAdmin(anyString(), anyString(), anyString(), anyString());
	}

	private AuthProperties properties(AuthProperties.BootstrapAdmin admin) {
		String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
		return new AuthProperties(
				Duration.ofMinutes(10), Duration.ofDays(14), 5, Duration.ofMinutes(15),
				"camel-arxiv", key, key,
				new AuthProperties.RefreshCookie(true, "Strict", "/api/v1/auth"), admin);
	}
}
