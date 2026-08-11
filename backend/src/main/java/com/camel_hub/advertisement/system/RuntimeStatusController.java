package com.camel_hub.advertisement.system;

import com.camel_hub.advertisement.campaign.PersonalizationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@Profile("api")
@RequestMapping("/api/v1/system/runtime")
public class RuntimeStatusController {

	private final PersonalizationProperties personalization;
	private final RuntimeStatusProperties runtime;

	public RuntimeStatusController(
			PersonalizationProperties personalization, RuntimeStatusProperties runtime
	) {
		this.personalization = personalization;
		this.runtime = runtime;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('system:manage')")
	Mono<RuntimeStatus> status() {
		return Mono.just(new RuntimeStatus(
				personalization.enabled(), personalization.provider(), personalization.model(),
				runtime.rayConfigured(), runtime.kafkaConfigured(), runtime.liveSmtpAllowed(),
				runtime.publicMailboxAllowed(),
				personalization.enabled() && runtime.rayConfigured() && runtime.kafkaConfigured()));
	}

	public record RuntimeStatus(
			boolean personalizationEnabled, String provider, String model, boolean rayConfigured,
			boolean kafkaConfigured, boolean liveSmtpAllowed, boolean publicMailboxAllowed,
			boolean generationReady
	) { }
}
