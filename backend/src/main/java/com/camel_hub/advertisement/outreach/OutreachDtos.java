package com.camel_hub.advertisement.outreach;

import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;

public final class OutreachDtos {
    private OutreachDtos() { }
    @JsonDeserialize(using=CreateDeserializer.class)
    public record CreateRequest(String name, String targetMode, int targetCount, UUID smtpAccountId,
            UUID templateId, String purpose, ArxivSearchCriteria criteria, String confirmation, String idempotencyKey) { }
    @JsonDeserialize(using=CancelDeserializer.class)
    public record CancelRequest(@NotNull @PositiveOrZero Long expectedLockVersion) { }
    public record SenderOption(UUID id, String name, String fromEmail, String replyToEmail, boolean available, String message) { }
    public record TemplateOption(UUID id, String name, int currentVersion, boolean available, String message) { }
    public record Limits(int maxEmails, int maxPapers, int searchPaperBudget) { }
    public record Options(List<SenderOption> senders, List<TemplateOption> templates, Limits limits, String productionHoldReason) { }
    public record RunView(UUID id, String name, String targetMode, int targetCount, String status, String stage,
            String message, int paperCount, int extractedCount, int eligibleCount, int selectedCount,
            int smtpAccepted, int failedCount, int unknownCount, UUID campaignId, UUID importJobId,
            UUID extractionJobId, UUID smtpAccountId, UUID templateId, long lockVersion, Instant createdAt,
            Instant updatedAt, List<String> allowedActions) { }

    /** A count or selector must never be silently coerced or overwritten before confirmation. */
    public static final class CreateDeserializer extends ValueDeserializer<CreateRequest> {
        private static final Set<String> FIELDS=Set.of("name","targetMode","targetCount","smtpAccountId","templateId","purpose","criteria","confirmation","idempotencyKey");
        @Override public CreateRequest deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()!=JsonToken.START_OBJECT) throw invalid(parser);
            var seen=new HashSet<String>();
            String name=null,mode=null,purpose=null,confirmation=null,key=null;
            int count=0;UUID smtp=null,template=null;ArxivSearchCriteria criteria=null;
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                if(parser.currentToken()!=JsonToken.PROPERTY_NAME) throw invalid(parser);
                String field=parser.currentName();
                if(!FIELDS.contains(field)||!seen.add(field)) throw invalid(parser);
                JsonToken token=parser.nextToken();
                if("targetCount".equals(field)) {
                    if(token!=JsonToken.VALUE_NUMBER_INT) throw invalid(parser);
                    count=parser.getIntValue();
                } else if("criteria".equals(field)) {
                    if(token!=JsonToken.START_OBJECT) throw invalid(parser);
                    criteria=context.readValue(parser,ArxivSearchCriteria.class);
                } else {
                    if(token!=JsonToken.VALUE_STRING) throw invalid(parser);
                    String text=parser.getString();
                    switch(field) {
                        case "name" -> name=text;
                        case "targetMode" -> mode=text;
                        case "purpose" -> purpose=text;
                        case "confirmation" -> confirmation=text;
                        case "idempotencyKey" -> key=text;
                        case "smtpAccountId" -> smtp=id(parser,text);
                        case "templateId" -> template=id(parser,text);
                        default -> throw invalid(parser);
                    }
                }
            }
            if(!seen.equals(FIELDS)) throw invalid(parser);
            return new CreateRequest(name,mode,count,smtp,template,purpose,criteria,confirmation,key);
        }
        private UUID id(JsonParser parser,String text) {
            try {UUID id=UUID.fromString(text);if(!id.toString().equalsIgnoreCase(text)) throw invalid(parser);return id;}
            catch(IllegalArgumentException e) {throw invalid(parser);}
        }
        private MismatchedInputException invalid(JsonParser parser) { return MismatchedInputException.from(parser,CreateRequest.class,"Provide all documented outreach fields exactly once; targetCount must be an integer"); }
    }
    public static final class CancelDeserializer extends ValueDeserializer<CancelRequest> {
        @Override public CancelRequest deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()!=JsonToken.START_OBJECT||parser.nextToken()!=JsonToken.PROPERTY_NAME
                    ||!"expectedLockVersion".equals(parser.currentName())||parser.nextToken()!=JsonToken.VALUE_NUMBER_INT) throw invalid(parser);
            long version=parser.getLongValue();
            if(version<0||parser.nextToken()!=JsonToken.END_OBJECT) throw invalid(parser);
            return new CancelRequest(version);
        }
        private MismatchedInputException invalid(JsonParser parser) {return MismatchedInputException.from(parser,CancelRequest.class,"Provide one nonnegative integer expectedLockVersion");}
    }
}
