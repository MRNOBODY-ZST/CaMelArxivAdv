package com.camel_hub.advertisement.outreach.bulk;

import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;

@JsonDeserialize(using=BulkOutreachCommand.Deserializer.class)
public record BulkOutreachCommand(String name, String purpose, int targetPapers,
        UUID smtpAccountId, UUID templateId, List<ArxivSearchCriteria> searchPlan,
        boolean incremental, boolean acceptDeliverabilityRisk, String confirmation,
        String idempotencyKey) {
    /** Strict boundary prevents coercion, duplicate overrides and hidden query changes. */
    public static final class Deserializer extends ValueDeserializer<BulkOutreachCommand> {
        private static final Set<String> FIELDS=Set.of("name","purpose","targetPapers","smtpAccountId","templateId","searchPlan","incremental","acceptDeliverabilityRisk","confirmation","idempotencyKey");
        private static final Set<String> CRITERIA=Set.of("categoryIds","categoryMode","submittedFrom","submittedTo","updatedFrom","updatedTo","titleKeywords","abstractKeywords","authorKeywords","hasDoi","hasJournalReference","sourceAvailable","sortBy","sortOrder","page","pageSize");
        @Override public BulkOutreachCommand deserialize(JsonParser p,DeserializationContext context) {
            if(p.currentToken()!=JsonToken.START_OBJECT) throw invalid(p);
            var seen=new HashSet<String>();String name=null,purpose=null,confirmation=null,key=null;int target=0;UUID smtp=null,template=null;List<ArxivSearchCriteria> plan=null;boolean incremental=false,risk=false;
            while(p.nextToken()!=JsonToken.END_OBJECT) {
                if(p.currentToken()!=JsonToken.PROPERTY_NAME) throw invalid(p);String field=p.currentName();if(!FIELDS.contains(field)||!seen.add(field)) throw invalid(p);JsonToken token=p.nextToken();
                switch(field) {
                    case "targetPapers" -> {if(token!=JsonToken.VALUE_NUMBER_INT)throw invalid(p);target=p.getIntValue();}
                    case "incremental","acceptDeliverabilityRisk" -> {if(token!=JsonToken.VALUE_TRUE&&token!=JsonToken.VALUE_FALSE)throw invalid(p);if(field.equals("incremental"))incremental=p.getBooleanValue();else risk=p.getBooleanValue();}
                    case "searchPlan" -> {if(token!=JsonToken.START_ARRAY)throw invalid(p);plan=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(plan.size()==12)throw invalid(p);plan.add(criteria(p,context));}}
                    default -> {if(token!=JsonToken.VALUE_STRING)throw invalid(p);String text=p.getString();switch(field){case "name"->name=text;case "purpose"->purpose=text;case "confirmation"->confirmation=text;case "idempotencyKey"->key=text;case "smtpAccountId"->smtp=id(p,text);case "templateId"->template=id(p,text);default->throw invalid(p);}}
                }
            }
            if(!seen.equals(FIELDS))throw invalid(p);
            return new BulkOutreachCommand(name,purpose,target,smtp,template,List.copyOf(plan),incremental,risk,confirmation,key);
        }
        private ArxivSearchCriteria criteria(JsonParser p,DeserializationContext context) {
            if(p.currentToken()!=JsonToken.START_OBJECT)throw invalid(p);var seen=new HashSet<String>();var values=new HashMap<String,Object>();
            while(p.nextToken()!=JsonToken.END_OBJECT) {
                if(p.currentToken()!=JsonToken.PROPERTY_NAME)throw invalid(p);String field=p.currentName();if(!CRITERIA.contains(field)||!seen.add(field))throw invalid(p);JsonToken token=p.nextToken();
                if(token==JsonToken.VALUE_NULL) {if(field.equals("page")||field.equals("pageSize"))throw invalid(p);continue;}
                switch(field) {
                    case "categoryIds" -> {if(token!=JsonToken.START_ARRAY)throw invalid(p);var categories=new ArrayList<String>();while(p.nextToken()!=JsonToken.END_ARRAY){if(p.currentToken()!=JsonToken.VALUE_STRING||categories.size()==100)throw invalid(p);categories.add(p.getString());}values.put(field,List.copyOf(categories));}
                    case "page","pageSize" -> {if(token!=JsonToken.VALUE_NUMBER_INT)throw invalid(p);values.put(field,p.getIntValue());}
                    case "hasDoi","hasJournalReference","sourceAvailable" -> {if(token!=JsonToken.VALUE_TRUE&&token!=JsonToken.VALUE_FALSE)throw invalid(p);values.put(field,p.getBooleanValue());}
                    default -> {if(token!=JsonToken.VALUE_STRING)throw invalid(p);Object value=switch(field){case "submittedFrom","submittedTo","updatedFrom","updatedTo"->context.readValue(p,LocalDate.class);case "categoryMode"->context.readValue(p,ArxivSearchCriteria.CategoryMode.class);case "sortBy"->context.readValue(p,ArxivSearchCriteria.SortBy.class);case "sortOrder"->context.readValue(p,ArxivSearchCriteria.SortOrder.class);default->p.getString();};values.put(field,value);}
                }
            }
            @SuppressWarnings("unchecked") List<String> categories=(List<String>)values.getOrDefault("categoryIds",List.of());
            return new ArxivSearchCriteria(categories,(ArxivSearchCriteria.CategoryMode)values.get("categoryMode"),(LocalDate)values.get("submittedFrom"),(LocalDate)values.get("submittedTo"),(LocalDate)values.get("updatedFrom"),(LocalDate)values.get("updatedTo"),(String)values.get("titleKeywords"),(String)values.get("abstractKeywords"),(String)values.get("authorKeywords"),(Boolean)values.get("hasDoi"),(Boolean)values.get("hasJournalReference"),(Boolean)values.get("sourceAvailable"),(ArxivSearchCriteria.SortBy)values.get("sortBy"),(ArxivSearchCriteria.SortOrder)values.get("sortOrder"),(Integer)values.getOrDefault("page",1),(Integer)values.getOrDefault("pageSize",100));
        }
        private UUID id(JsonParser p,String text) {try{var id=UUID.fromString(text);if(!id.toString().equalsIgnoreCase(text))throw invalid(p);return id;}catch(IllegalArgumentException e){throw invalid(p);}}
        private MismatchedInputException invalid(JsonParser p) {return MismatchedInputException.from(p,BulkOutreachCommand.class,"Provide documented bulk fields exactly once, with typed integer/boolean values and literal criteria");}
    }
}
