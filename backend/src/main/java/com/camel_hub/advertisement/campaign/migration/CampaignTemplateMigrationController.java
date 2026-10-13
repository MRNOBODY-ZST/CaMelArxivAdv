package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.security.Principal;
import java.util.*;
import static com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;

/** HTTP guards supplement, never replace, the service's locked authority checks. */
@RestController
@Profile("api")
@RequestMapping("/api/v1/campaigns/{campaignId}/template-migrations")
@ConditionalOnProperty(prefix="app.persistence",name="enabled",havingValue="true",matchIfMissing=true)
public class CampaignTemplateMigrationController {
    private final CampaignTemplateMigrationService service;
    public CampaignTemplateMigrationController(CampaignTemplateMigrationService service){this.service=service;}

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('campaign:read') and hasAuthority('campaign:create')")
    public Mono<MigrationPreview> preview(@PathVariable UUID campaignId,@RequestBody PreviewRequest request,Principal principal){
        return service.preview(campaignId,RequestContextSupport.actorId(principal),request.command());
    }
    @PostMapping
    @PreAuthorize("hasAuthority('campaign:read') and hasAuthority('campaign:create')")
    public Mono<MigrationResult> migrate(@PathVariable UUID campaignId,@RequestBody MigrationRequest request,Principal principal,ServerWebExchange exchange){
        return service.migrate(campaignId,RequestContextSupport.actorId(principal),RequestContextSupport.context(exchange),request.command());
    }
    @GetMapping("/by-key")
    @PreAuthorize("hasAuthority('campaign:read')")
    public Mono<MigrationResult> byKey(@PathVariable UUID campaignId,@RequestParam String key,Principal principal){
        return service.getByKey(campaignId,RequestContextSupport.actorId(principal),key);
    }
    @GetMapping("/{migrationId}")
    @PreAuthorize("hasAuthority('campaign:read')")
    public Mono<MigrationResult> get(@PathVariable UUID campaignId,@PathVariable UUID migrationId,Principal principal){
        return service.get(campaignId,migrationId,RequestContextSupport.actorId(principal));
    }

    @JsonDeserialize(using=PreviewDeserializer.class)
    public record PreviewRequest(PreviewCommand command) { }
    @JsonDeserialize(using=MigrationDeserializer.class)
    public record MigrationRequest(MigrationCommand command) { }

    public static final class PreviewDeserializer extends ValueDeserializer<PreviewRequest> {
        @Override public PreviewRequest deserialize(JsonParser parser,DeserializationContext context)throws JacksonException{
            var fields=read(parser,false);
            return new PreviewRequest(new PreviewCommand((UUID)fields.get("targetTemplateId"),(UUID)fields.get("targetTemplateVersionId"),ids(fields)));
        }
    }
    public static final class MigrationDeserializer extends ValueDeserializer<MigrationRequest> {
        @Override public MigrationRequest deserialize(JsonParser parser,DeserializationContext context)throws JacksonException{
            var fields=read(parser,true);
            return new MigrationRequest(new MigrationCommand((Long)fields.get("expectedLockVersion"),(UUID)fields.get("targetTemplateId"),(UUID)fields.get("targetTemplateVersionId"),(Long)fields.get("expectedTemplateLockVersion"),ids(fields),(String)fields.get("expectedMembershipDigest"),(String)fields.get("idempotencyKey")));
        }
    }
    @SuppressWarnings("unchecked") private static List<UUID> ids(Map<String,Object> fields){return (List<UUID>)fields.get("recipientIds");}
    private static Map<String,Object> read(JsonParser parser,boolean commit)throws JacksonException{
        if(!parser.isExpectedStartObjectToken())invalid(parser);
        Set<String> required=commit?Set.of("targetTemplateId","targetTemplateVersionId","recipientIds","expectedLockVersion","expectedTemplateLockVersion","expectedMembershipDigest","idempotencyKey"):Set.of("targetTemplateId","targetTemplateVersionId","recipientIds");
        Map<String,Object> fields=new HashMap<>();
        while(parser.nextToken()!=JsonToken.END_OBJECT){
            if(parser.currentToken()!=JsonToken.PROPERTY_NAME)invalid(parser);
            String name=parser.currentName();
            if(!required.contains(name)||fields.containsKey(name))invalid(parser);
            JsonToken token=parser.nextToken();
            Object value;
            switch(name){
                case "targetTemplateId","targetTemplateVersionId" -> value=uuid(parser,token);
                case "expectedLockVersion","expectedTemplateLockVersion" -> {
                    if(token!=JsonToken.VALUE_NUMBER_INT)invalid(parser);
                    long version=parser.getLongValue();if(version<0)invalid(parser);value=version;
                }
                case "recipientIds" -> {
                    if(token!=JsonToken.START_ARRAY)invalid(parser);
                    List<UUID> ids=new ArrayList<>();
                    while((token=parser.nextToken())!=JsonToken.END_ARRAY){if(ids.size()>=120)invalid(parser);ids.add(uuid(parser,token));}
                    if(new HashSet<>(ids).size()!=ids.size())invalid(parser);value=List.copyOf(ids);
                }
                default -> {
                    if(token!=JsonToken.VALUE_STRING)invalid(parser);
                    String string=parser.getString();
                    if(name.equals("idempotencyKey")&&!string.matches("[A-Za-z0-9_-]{1,128}"))invalid(parser);
                    if(name.equals("expectedMembershipDigest")&&!string.matches("[0-9a-f]{64}"))invalid(parser);
                    value=string;
                }
            }
            fields.put(name,value);
        }
        if(!fields.keySet().equals(required))invalid(parser);
        return fields;
    }
    private static UUID uuid(JsonParser parser,JsonToken token)throws JacksonException{
        if(token!=JsonToken.VALUE_STRING)invalid(parser);
        String value=parser.getString();
        if(!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))invalid(parser);
        try{return UUID.fromString(value);}catch(IllegalArgumentException failure){throw new CampaignValidationException("Migration identifier is invalid");}
    }
    private static void invalid(JsonParser parser)throws DatabindException{throw DatabindException.from(parser,"Invalid campaign template migration request");}
}
