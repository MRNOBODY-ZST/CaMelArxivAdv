package com.camel_hub.advertisement.outreach.bulk;

import com.camel_hub.advertisement.common.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.security.Principal;
import java.util.UUID;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;

@RestController @Profile("api") @RequestMapping("/api/v1/outreach-bulk-runs")
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class BulkOutreachController {
    private final BulkOutreachService service;
    public BulkOutreachController(BulkOutreachService service) {this.service=service;}
    @GetMapping @PreAuthorize("hasAuthority('campaign:read')")
    public Mono<PageResponse<BulkOutreachView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.list(page,pageSize);}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('campaign:read')")
    public Mono<BulkOutreachView> get(@PathVariable UUID id) {return service.get(id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('paper:import') and hasAuthority('campaign:create') and hasAuthority('campaign:approve') and hasAuthority('campaign:send')")
    public Mono<BulkOutreachView> create(@RequestBody BulkOutreachCommand command,Principal principal,ServerWebExchange exchange) {return service.create(RequestContextSupport.actorId(principal),command,RequestContextSupport.context(exchange));}
    @PostMapping("/{id}/cancel") @PreAuthorize("hasAuthority('campaign:pause')")
    public Mono<BulkOutreachView> cancel(@PathVariable UUID id,@Valid @RequestBody CancelRequest command,Principal principal,ServerWebExchange exchange) {return service.cancel(id,RequestContextSupport.actorId(principal),command.expectedVersion(),RequestContextSupport.context(exchange));}
    @JsonDeserialize(using=CancelDeserializer.class)
    public record CancelRequest(@NotNull @Min(0) Long expectedVersion) { }
    public static final class CancelDeserializer extends ValueDeserializer<CancelRequest> {
        @Override public CancelRequest deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()!=JsonToken.START_OBJECT||parser.nextToken()!=JsonToken.PROPERTY_NAME||!"expectedVersion".equals(parser.currentName())||parser.nextToken()!=JsonToken.VALUE_NUMBER_INT)throw invalid(parser);
            long version=parser.getLongValue();if(version<0||parser.nextToken()!=JsonToken.END_OBJECT)throw invalid(parser);return new CancelRequest(version);
        }
        private MismatchedInputException invalid(JsonParser parser) {return MismatchedInputException.from(parser,CancelRequest.class,"Provide one nonnegative integer expectedVersion");}
    }
}
