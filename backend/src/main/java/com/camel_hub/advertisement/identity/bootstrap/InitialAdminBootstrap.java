package com.camel_hub.advertisement.identity.bootstrap;

import com.camel_hub.advertisement.identity.config.AuthProperties;
import com.camel_hub.advertisement.identity.persistence.IdentityRepository;
import com.camel_hub.advertisement.identity.security.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

import java.time.Duration;

public final class InitialAdminBootstrap implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(InitialAdminBootstrap.class);
	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(15);

	private final IdentityRepository repository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final AuthProperties properties;

	public InitialAdminBootstrap(
			IdentityRepository repository,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			AuthProperties properties
	) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		Boolean created = initialize().block(STARTUP_TIMEOUT);
		if (Boolean.TRUE.equals(created)) {
			LOGGER.info("Initial administrator created for username={}", properties.bootstrapAdmin().username());
		}
	}

	public Mono<Boolean> initialize() {
		AuthProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
		if (isBlank(admin.username()) || isBlank(admin.email()) || isBlank(admin.password())) {
			return Mono.just(false);
		}
		passwordPolicy.validate(admin.password(), admin.username(), admin.email());
		String displayName = isBlank(admin.displayName()) ? admin.username() : admin.displayName();
		String passwordHash = passwordEncoder.encode(admin.password());
		return repository.createInitialAdmin(admin.username(), admin.email(), displayName, passwordHash);
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
