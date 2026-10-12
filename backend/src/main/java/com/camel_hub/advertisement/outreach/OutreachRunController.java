package com.camel_hub.advertisement.outreach;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.security.Principal;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/outreach-runs")
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class OutreachRunController {
    private final OutreachRunService service;
    public OutreachRunController(OutreachRunService service) { this.service=service; }
    @GetMapping @PreAuthorize("hasAuthority('campaign:read')")
    Mono<PageResponse<OutreachDtos.RunView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(page,pageSize); }
    @GetMapping("/options") @PreAuthorize("hasAuthority('campaign:read')")
    Mono<OutreachDtos.Options> options() { return service.options(); }
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('campaign:read')")
    Mono<OutreachDtos.RunView> get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('paper:import') and hasAuthority('campaign:create') and hasAuthority('campaign:approve') and hasAuthority('campaign:send')")
    Mono<OutreachDtos.RunView> create(@RequestBody OutreachDtos.CreateRequest request,Principal principal,ServerWebExchange exchange) {
        return service.create(RequestContextSupport.actorId(principal),request,RequestContextSupport.context(exchange));
    }
    @PostMapping("/{id}/cancel") @PreAuthorize("hasAuthority('campaign:pause')")
    Mono<OutreachDtos.RunView> cancel(@PathVariable UUID id,@Valid @RequestBody OutreachDtos.CancelRequest request,Principal principal,ServerWebExchange exchange) {
        return service.cancel(id,RequestContextSupport.actorId(principal),request.expectedLockVersion(),RequestContextSupport.context(exchange));
    }
}
