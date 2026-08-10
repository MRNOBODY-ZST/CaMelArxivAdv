package com.camel_hub.advertisement.system;

import com.camel_hub.advertisement.campaign.PersonalizationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
	RuntimeStatus status() {
		return new RuntimeStatus(
				personalization.enabled(), personalization.provider(), personalization.model(),
				runtime.rayConfigured(), runtime.rabbitConfigured(), runtime.liveSmtpAllowed(),
				personalization.enabled() && runtime.rayConfigured() && runtime.rabbitConfigured());
	}

	public record RuntimeStatus(
			boolean personalizationEnabled, String provider, String model, boolean rayConfigured,
			boolean rabbitConfigured, boolean liveSmtpAllowed, boolean generationReady
	) { }
}
