package com.camel_hub.advertisement.system.personalization;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.UUID;
@RestController
@org.springframework.context.annotation.Profile("!mail-worker")
public class PersonalizationInternalController {
    private final PersonalizationSettingsService service;
    public PersonalizationInternalController(PersonalizationSettingsService service) { this.service = service; }
    public record RuntimeConfiguration(String provider, String model, String apiBaseUrl, String apiAuthScheme, String apiKey) {
        @Override public String toString() { return "RuntimeConfiguration[REDACTED]"; }
    }
    @GetMapping("/internal/personalization/jobs/{jobId}/configuration")
    public Mono<RuntimeConfiguration> get(@PathVariable UUID jobId) {
        return service.jobConfiguration(jobId).map(s -> new RuntimeConfiguration(s.provider(), s.model(), s.apiOrigin(), s.authScheme(), s.apiKey()));
    }
}
