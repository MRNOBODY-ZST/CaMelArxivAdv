package com.camel_hub.advertisement.outreach.schedule;
import com.camel_hub.advertisement.system.personalization.WeeklyTrendClient.Evidence;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.time.Duration;
import reactor.core.publisher.Mono;
import com.camel_hub.advertisement.system.personalization.WeeklyTrendClient;
import com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService;
public final class TrendDiscovery {
    public record Topic(String phrase,List<String> sourceIds,List<String> categoryIds) {}
    public record Result(boolean verified,String reason,List<Topic> topics,List<Evidence> evidence,long settingsVersion,String provider,String model,int inputTokens,int outputTokens,int modelCalls,boolean usageComplete) {
        public Result(boolean verified,String reason,List<Topic> topics,List<Evidence> evidence,long settingsVersion,String provider,String model,int inputTokens,int outputTokens,int modelCalls){this(verified,reason,topics,evidence,settingsVersion,provider,model,inputTokens,outputTokens,modelCalls,inputTokens>=0&&outputTokens>=0&&modelCalls>=0);}
    }
    static Result incompleteUsage(Result result){return new Result(result.verified(),result.reason(),result.topics(),result.evidence(),result.settingsVersion(),result.provider(),result.model(),result.inputTokens(),result.outputTokens(),result.modelCalls(),false);}
    private final WeeklyTrendClient client;
    private final PersonalizationSettingsService settings;
    public TrendDiscovery(WeeklyTrendClient client,PersonalizationSettingsService settings) {this.client=client;this.settings=settings;}
    public Mono<Result> discover(List<String> seeds,Instant now) {
        return settings.effective().flatMap(s->{
            if(!s.enabled()||!s.keyConfigured())return Mono.just(fallback(seeds,"PROVIDER_DISABLED",s.version(),s.provider(),s.model(),0,0,0));
            return client.evidence(now).flatMap(evidence->client.rank(s,seeds,evidence).map(rank->{
                if(rank.text()==null)return withEvidence(fallback(seeds,rank.reason(),s.version(),s.provider(),s.model(),rank.inputTokens(),rank.outputTokens(),rank.calls()),evidence);
                try{return new Result(true,"DATED_FEED_EVIDENCE",validate(rank.text(),evidence,now),List.copyOf(evidence),s.version(),s.provider(),s.model(),rank.inputTokens(),rank.outputTokens(),rank.calls());}
                catch(RuntimeException e){return withEvidence(fallback(seeds,"UNSUBSTANTIATED_MODEL_OUTPUT",s.version(),s.provider(),s.model(),rank.inputTokens(),rank.outputTokens(),rank.calls()),evidence);}
            }));
        }).onErrorReturn(fallback(seeds,"EVIDENCE_UNAVAILABLE",0,"unavailable","unavailable",0,0,0));
    }
    private static Result withEvidence(Result fallback,List<Evidence> evidence){return new Result(false,fallback.reason(),fallback.topics(),List.copyOf(evidence),fallback.settingsVersion(),fallback.provider(),fallback.model(),fallback.inputTokens(),fallback.outputTokens(),fallback.modelCalls(),fallback.usageComplete());}
    static List<Topic> validate(String raw,List<Evidence> evidence,Instant now) {
        try{
            if(raw.length()>20000||evidence.size()>30)throw new IllegalArgumentException();
            Set<String> valid=evidence.stream().filter(e->e.publishedAt()!=null&&!e.publishedAt().isAfter(now)&&!e.publishedAt().isBefore(now.minus(Duration.ofDays(14)))).map(Evidence::id).collect(java.util.stream.Collectors.toSet());
            var root=tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw);
            var topics=root.path("topics");if(!topics.isArray()||topics.isEmpty()||topics.size()>8)throw new IllegalArgumentException();
            var result=new ArrayList<Topic>();
            for(var topic:topics){
                if(!topic.path("phrase").isString())throw new IllegalArgumentException();
                String phrase=WeeklySchedulePolicy.directions(List.of(topic.path("phrase").asString())).getFirst();
                var ids=topic.path("sourceIds");if(!ids.isArray()||ids.isEmpty()||ids.size()>30)throw new IllegalArgumentException();
                var cited=new ArrayList<String>();for(var id:ids){if(!id.isString()||!valid.contains(id.asString()))throw new IllegalArgumentException();cited.add(id.asString());}
                var categories=new ArrayList<String>();if(topic.has("categoryIds")){if(!topic.path("categoryIds").isArray())throw new IllegalArgumentException();for(var category:topic.path("categoryIds")){if(!category.isString()||!Set.of("cs.AI","cs.CL","cs.MA","cs.LG","q-fin.CP","q-fin.ST","q-fin.TR").contains(category.asString()))throw new IllegalArgumentException();categories.add(category.asString());}}
                result.add(new Topic(phrase,List.copyOf(cited),List.copyOf(categories)));
            }
            return List.copyOf(result);
        }catch(RuntimeException e){throw new IllegalArgumentException("Ranking must cite dated input evidence and use literal research phrases");}
    }
    static Result fallback(List<String> seeds,String reason,long version,String provider,String model,int input,int output,int calls) { return new Result(false,reason,WeeklySchedulePolicy.directions(seeds).stream().map(s->new Topic(s,List.of(),List.of())).toList(),List.of(),version,provider,model,input,output,calls); }
}
