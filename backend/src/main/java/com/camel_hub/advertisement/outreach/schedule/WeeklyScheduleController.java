package com.camel_hub.advertisement.outreach.schedule;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ServerWebExchange;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import com.camel_hub.advertisement.common.api.PageResponse;
import java.util.UUID;
import java.security.Principal;
import reactor.core.publisher.Mono;
@RestController @Profile("api")
@RequestMapping("/api/v1/outreach-schedules")
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class WeeklyScheduleController {
    private final WeeklyScheduleService service;
    public WeeklyScheduleController(WeeklyScheduleService service){this.service=service;}
    public record VersionRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion) {}
    @GetMapping @PreAuthorize("hasAuthority('campaign:read')")
    public Mono<PageResponse<WeeklyScheduleView>> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return service.list(page,pageSize);}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('campaign:read')")
    public Mono<WeeklyScheduleView> get(@PathVariable UUID id){return service.get(id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('paper:import') and hasAuthority('campaign:create') and hasAuthority('campaign:approve') and hasAuthority('campaign:send')")
    public Mono<WeeklyScheduleView> create(@RequestBody WeeklyScheduleCommand command,Principal principal,ServerWebExchange exchange){return service.create(RequestContextSupport.actorId(principal),command,RequestContextSupport.context(exchange));}
    @PostMapping("/{id}/pause") @PreAuthorize("hasAuthority('campaign:pause')")
    public Mono<WeeklyScheduleView> pause(@PathVariable UUID id,@jakarta.validation.Valid @RequestBody VersionRequest request,Principal principal,ServerWebExchange exchange){return service.changeEnabled(id,RequestContextSupport.actorId(principal),request.expectedVersion(),false,RequestContextSupport.context(exchange));}
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('campaign:pause') and hasAuthority('paper:import') and hasAuthority('campaign:create') and hasAuthority('campaign:approve') and hasAuthority('campaign:send')")
    public Mono<WeeklyScheduleView> resume(@PathVariable UUID id,@jakarta.validation.Valid @RequestBody VersionRequest request,Principal principal,ServerWebExchange exchange){return service.changeEnabled(id,RequestContextSupport.actorId(principal),request.expectedVersion(),true,RequestContextSupport.context(exchange));}
}
