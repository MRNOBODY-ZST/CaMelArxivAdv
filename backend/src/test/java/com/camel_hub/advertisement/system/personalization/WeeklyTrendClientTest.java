package com.camel_hub.advertisement.system.personalization;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class WeeklyTrendClientTest {
    @Test void errorAndRedirectStreamsAreCanceledAtTheByteBoundForFeedsAndProvider(){
        for(var status:List.of(HttpStatus.FOUND,HttpStatus.BAD_GATEWAY))for(boolean provider:List.of(false,true)){
            var consumed=new java.util.concurrent.atomic.AtomicInteger();var canceled=new java.util.concurrent.atomic.AtomicBoolean();
            var bytes=reactor.core.publisher.Flux.range(0,100).<org.springframework.core.io.buffer.DataBuffer>map(i->{consumed.incrementAndGet();return new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(new byte[16384]);}).doOnCancel(()->canceled.set(true));
            var client=new WeeklyTrendClient(WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(status).body(bytes).build())).build());
            if(provider)assertThat(client.rank(settings(true),List.of("Agent"),List.of(evidence())).block().text()).isNull();else assertThat(client.evidence(Instant.now()).block()).isEmpty();
            assertThat(consumed.get()).isLessThanOrEqualTo(5);assertThat(canceled.get()).isTrue();
        }
    }
    @Test void malformedOutputRetainsKnownUsageAndMissingUsageIsNotReportedAsZero(){
        var malformed=new WeeklyTrendClient(WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.OK).body("{\"status\":\"incomplete\",\"output\":[],\"usage\":{\"input_tokens\":123,\"output_tokens\":45}}").build())).build());
        var known=malformed.rank(settings(true),List.of("Agent"),List.of(evidence())).block();
        assertThat(known.text()).isNull();assertThat(known.inputTokens()).isEqualTo(123);assertThat(known.outputTokens()).isEqualTo(45);
        var missing=new WeeklyTrendClient(WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.OK).body("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}").build())).build());
        var unknown=missing.rank(settings(true),List.of("Agent"),List.of(evidence())).block();assertThat(unknown.inputTokens()).isEqualTo(-1);assertThat(unknown.outputTokens()).isEqualTo(-1);
    }
    @Test void onlyDatedRecentFeedEntriesAreEvidenceAndXmlCannotResolveEntities() {
        String rss="<rss><channel><item><title>Recent paper</title><link>https://arxiv.org/abs/2610.01234</link><pubDate>Sat, 10 Oct 2026 01:00:00 GMT</pubDate></item><item><title>Undated</title></item><item><title>Old</title><pubDate>Wed, 01 Jan 2020 00:00:00 GMT</pubDate></item></channel></rss>";
        var result=WeeklyTrendClient.parseFeed(rss,0,Instant.parse("2026-10-12T01:00:00Z"));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Recent paper");
        assertThat(WeeklyTrendClient.parseFeed("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>",0,Instant.now())).isEmpty();
    }
    @Test void disabledProviderDoesNotSendAndErrorsNeverExposeConfiguredKey() {
        var requests=new java.util.ArrayList<ClientRequest>();
        var client=new WeeklyTrendClient(WebClient.builder().exchangeFunction(r->{requests.add(r);return Mono.error(new IllegalStateException("secret-key"));}).build());
        var disabled=client.rank(settings(false),List.of("Agent"),List.of()).block();
        assertThat(disabled.reason()).isEqualTo("PROVIDER_DISABLED");
        assertThat(requests).isEmpty();
        var failed=client.rank(settings(true),List.of("Agent"),List.of(evidence())).block();
        assertThat(failed.reason()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(failed.toString()).doesNotContain("secret-key");
    }
    @Test void redirectsOversizedBodiesAndPrivateOriginsFailClosed() {
        for(var response:List.of(ClientResponse.create(HttpStatus.FOUND).header("Location","http://127.0.0.1").build(),ClientResponse.create(HttpStatus.OK).body("x".repeat(65537)).build())) {
            var client=new WeeklyTrendClient(WebClient.builder().exchangeFunction(r->Mono.just(response)).build());
            assertThat(client.rank(settings(true),List.of("Agent"),List.of(evidence())).block().text()).isNull();
        }
        var client=new WeeklyTrendClient(WebClient.builder().exchangeFunction(r->Mono.error(new AssertionError("must not send"))).build());
        var privateSettings=new PersonalizationSettingsService.EffectiveSettings(1,true,"openai","test","https://127.0.0.1/v1","bearer","secret-key","DATABASE",null);
        assertThat(client.rank(privateSettings,List.of("Agent"),List.of(evidence())).block().reason()).isEqualTo("PROVIDER_UNAVAILABLE");
    }
    @Test void outboundRequestCapsTokensAndMakesOneCallAndNoArbitraryFeedUrls() {
        var requests=new java.util.ArrayList<ClientRequest>();
        var bodies=new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        var client=new WeeklyTrendClient(WebClient.builder().exchangeFunction(r->{requests.add(r);var wire=new org.springframework.mock.http.client.reactive.MockClientHttpRequest(r.method(),r.url());
            return r.writeTo(wire,ExchangeStrategies.withDefaults()).then(Mono.defer(wire::getBodyAsString)).map(json->{bodies.add(tools.jackson.databind.json.JsonMapper.builder().build().readTree(json));return ClientResponse.create(HttpStatus.OK).body("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"topics\\\":[]}\"}]}],\"usage\":{\"input_tokens\":20,\"output_tokens\":10}}").build();});}).build());
        var result=client.rank(settings(true),List.of("Agent"),List.of(evidence())).block();
        assertThat(result.calls()).isEqualTo(1);
        assertThat(result.inputTokens()).isEqualTo(20);
        assertThat(requests).hasSize(1);
        assertThat(bodies.getFirst().path("max_output_tokens").asInt()).isEqualTo(4096);
        assertThat(bodies.getFirst().path("store").asBoolean()).isFalse();
        assertThat(WeeklyTrendClient.FEEDS).hasSizeLessThanOrEqualTo(6);
    }
    private WeeklyTrendClient.Evidence evidence(){return new WeeklyTrendClient.Evidence("feed-0-0","Paper","https://arxiv.org/abs/2610.01234",Instant.parse("2026-10-10T01:00:00Z"));}
    private PersonalizationSettingsService.EffectiveSettings settings(boolean enabled){return new PersonalizationSettingsService.EffectiveSettings(1,enabled,"openai","test","https://api.example.com/v1","bearer","secret-key","DATABASE",null);}
}
