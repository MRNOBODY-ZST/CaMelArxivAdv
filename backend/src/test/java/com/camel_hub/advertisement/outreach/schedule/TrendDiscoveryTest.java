package com.camel_hub.advertisement.outreach.schedule;
import com.camel_hub.advertisement.system.personalization.WeeklyTrendClient.Evidence;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TrendDiscoveryTest {
    private final Instant now=Instant.parse("2026-10-12T01:00:00Z");
    private final Evidence source=new Evidence("feed-0-0","New research", "https://arxiv.org/abs/2610.01234",Instant.parse("2026-10-10T01:00:00Z"));
    @Test void rankedTopicsMustCiteOnlyDatedRecentInputIds() {
        var valid=TrendDiscovery.validate("{\"topics\":[{\"phrase\":\"multi-agent\",\"sourceIds\":[\"feed-0-0\"],\"categoryIds\":[\"cs.MA\"]}]}",List.of(source),now);
        assertEquals("multi-agent",valid.getFirst().phrase());
        for(var evidence:List.of(List.<Evidence>of(),List.of(new Evidence(source.id(),source.title(),source.url(),null)),List.of(new Evidence(source.id(),source.title(),source.url(),now.minusSeconds(15*86400)))))
            assertThrows(IllegalArgumentException.class,()->TrendDiscovery.validate("{\"topics\":[{\"phrase\":\"multi-agent\",\"sourceIds\":[\"feed-0-0\"]}]}",evidence,now));
        assertThrows(IllegalArgumentException.class,()->TrendDiscovery.validate("{\"topics\":[{\"phrase\":\"Agent\",\"sourceIds\":[\"invented\"]}]}",List.of(source),now));
    }
    @Test void malformedRankingAndUnsafeCategoriesCannotBecomeQueries() {
        for(String raw:List.of("not json","{\"topics\":[]}","{\"topics\":[{\"phrase\":\"all:agent\",\"sourceIds\":[\"feed-0-0\"]}]}","{\"topics\":[{\"phrase\":\"Agent\",\"sourceIds\":[\"feed-0-0\"],\"categoryIds\":[\"all:evil\"]}]}"))
            assertThrows(IllegalArgumentException.class,()->TrendDiscovery.validate(raw,List.of(source),now));
    }
    @Test void absentEvidenceKeepsSeedsAndMakesNoVerifiedNewsClaim() {
        var fallback=TrendDiscovery.fallback(List.of("Agent","ChatGPT","DOT"),"PROVIDER_DISABLED",0,"openai","test",0,0,0);
        assertFalse(fallback.verified());
        assertEquals("PROVIDER_DISABLED",fallback.reason());
        assertEquals(List.of("Agent","ChatGPT","DOT"),fallback.topics().stream().map(TrendDiscovery.Topic::phrase).toList());
        assertTrue(fallback.evidence().isEmpty());
    }
    @Test void malformedOutputRetainsDatedEvidenceForDurableReviewButClaimsNoTrend(){
        var settings=new com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService(null,"",new com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService.EffectiveSettings(2,true,"openai","test","https://api.example.com/v1","bearer","secret-key","ENVIRONMENT",null));
        var client=new com.camel_hub.advertisement.system.personalization.WeeklyTrendClient(){
            @Override public reactor.core.publisher.Mono<List<Evidence>> evidence(Instant ignored){return reactor.core.publisher.Mono.just(List.of(source));}
            @Override public reactor.core.publisher.Mono<Ranking> rank(com.camel_hub.advertisement.system.personalization.PersonalizationSettingsService.EffectiveSettings s,List<String> seeds,List<Evidence> evidence){return reactor.core.publisher.Mono.just(new Ranking("{\"topics\":[{\"phrase\":\"Agent\",\"sourceIds\":[\"invented\"]}]}","RANKED",20,10,1));}
        };
        var fallback=new TrendDiscovery(client,settings).discover(List.of("Agent"),now).block();
        assertFalse(fallback.verified());assertEquals("UNSUBSTANTIATED_MODEL_OUTPUT",fallback.reason());assertEquals(List.of(source),fallback.evidence());assertEquals(1,fallback.modelCalls());assertFalse(fallback.toString().contains("secret-key"));
    }
}
