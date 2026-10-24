package com.camel_hub.advertisement.system;

import com.camel_hub.advertisement.campaign.PersonalizationProperties;
import com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
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
	private final PersonalizationSettingsService settings;

	public RuntimeStatusController(
			PersonalizationProperties personalization, RuntimeStatusProperties runtime
	) {
		this(personalization, runtime, null);
	}

	@Autowired
	public RuntimeStatusController(PersonalizationProperties personalization, RuntimeStatusProperties runtime,
			@Nullable PersonalizationSettingsService settings) {
		this.personalization = personalization;
		this.runtime = runtime;
		this.settings = settings;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('system:manage')")
	Mono<RuntimeStatus> status() {
		if (settings != null) {
			return settings.effective().map(effective -> new RuntimeStatus(
					effective.enabled(), effective.provider(), effective.model(),
					runtime.rayConfigured(), runtime.kafkaConfigured(), runtime.liveSmtpAllowed(),
					runtime.publicMailboxAllowed(), effective.enabled() && effective.keyConfigured()
						&& runtime.rayConfigured() && runtime.kafkaConfigured()));
		}
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
