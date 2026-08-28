package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.common.api.PageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static com.camel_hub.advertisement.email.tracking.MailTrackingModels.*;

@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MailTrackingController {
	private final MailTrackingService service;

	public MailTrackingController(MailTrackingService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/mail-tracking/status")
	@PreAuthorize("hasAnyAuthority('smtp:read', 'template:read')")
	Mono<TrackingStatus> status() {
		return Mono.fromSupplier(service::status);
	}

	@GetMapping("/api/v1/mail-send-records")
	@PreAuthorize("hasAuthority('smtp:read')")
	Mono<PageResponse<MailSendRecord>> list(
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize
	) {
		return service.list(page, pageSize);
	}

	@GetMapping("/api/v1/mail-send-records/{id}")
	@PreAuthorize("hasAuthority('smtp:read')")
	Mono<Detail> detail(@PathVariable String id) {
		try {
			UUID parsed = UUID.fromString(id);
			if (id.length() != 36 || !parsed.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
			return service.detail(parsed);
		}
		catch (IllegalArgumentException ignored) {
			return Mono.error(new MailTrackingValidationException("Mail send record ID is invalid"));
		}
	}
}
