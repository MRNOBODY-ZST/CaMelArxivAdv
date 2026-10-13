package com.camel_hub.advertisement.system.personalization;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import reactor.core.publisher.Mono;
@RestController
@org.springframework.context.annotation.Profile("!mail-worker")
@RequestMapping("/api/v1/system/personalization")
public class PersonalizationController {
    private final PersonalizationSettingsService service;
    private final PersonalizationProbe probe;
    public PersonalizationController(PersonalizationSettingsService service, PersonalizationProbe probe) { this.service = service; this.probe = probe; }
    @GetMapping @PreAuthorize("hasAuthority('system:manage')")
    public Mono<PersonalizationSettingsService.View> get() { return service.get(); }
    @PutMapping @PreAuthorize("hasAuthority('system:manage')")
    public Mono<PersonalizationSettingsService.View> save(@RequestBody PersonalizationSettingsService.Input input) { return service.save(input); }
    @PostMapping("/test") @PreAuthorize("hasAuthority('system:manage')")
    public Mono<PersonalizationProbe.Result> test(@RequestBody PersonalizationSettingsService.Input input) { return service.prepare(input).flatMap(probe::test); }
}
