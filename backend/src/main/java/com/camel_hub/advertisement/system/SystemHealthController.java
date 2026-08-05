package com.camel_hub.advertisement.system;

import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/system")
public class SystemHealthController {

	private final HealthEndpoint healthEndpoint;

	public SystemHealthController(HealthEndpoint healthEndpoint) {
		this.healthEndpoint = healthEndpoint;
	}

	@GetMapping("/health")
	public Mono<SystemHealthResponse> health() {
		return Mono.fromCallable(healthEndpoint::health)
				.subscribeOn(Schedulers.boundedElastic())
				.map(component -> new SystemHealthResponse(statusOf(component), Instant.now()));
	}

	private String statusOf(HealthDescriptor descriptor) {
		return descriptor.getStatus().getCode();
	}

	public record SystemHealthResponse(String status, Instant checkedAt) {
	}
}
