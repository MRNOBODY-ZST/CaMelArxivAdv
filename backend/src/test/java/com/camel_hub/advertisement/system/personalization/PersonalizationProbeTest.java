package com.camel_hub.advertisement.system.personalization;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
class PersonalizationProbeTest {
    @Test void rejectsMissingKeysBeforeSendingAnyProviderRequestEvenWhenDisabled() {
        var requests = new java.util.ArrayList<ClientRequest>();
        var probe = new PersonalizationProbe(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}").build());
        }).build());
        for (String key : new String[]{null, "", "   "}) {
            var settings = new PersonalizationSettingsService.EffectiveSettings(0, false, "openai", "test-model",
                    "https://api.example.com/v1", "bearer", key, "ENVIRONMENT", null);
            var result = probe.test(settings).block();
            assertThat(result.success()).isFalse();
            assertThat(result.message()).containsIgnoringCase("API key");
        }
        assertThat(requests).isEmpty();
    }
    @Test void usesResponsesAndMessagesEndpointsWithSanitizedProviderFailures() {
        var requests = new java.util.ArrayList<ClientRequest>();
        var bodies = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        var probe = new PersonalizationProbe(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            var wire = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(wire, ExchangeStrategies.withDefaults()).then(Mono.defer(wire::getBodyAsString)).map(json -> {
                bodies.add(tools.jackson.databind.json.JsonMapper.builder().build().readTree(json));
                return ClientResponse.create(HttpStatus.UNAUTHORIZED).body("raw-provider-secret").build();
            });
        }).build());
        var result = probe.test(PersonalizationSettingsServiceTest.fallback()).block();
        assertThat(result.success()).isFalse();
        assertThat(result.toString()).doesNotContain("raw-provider-secret", "env-secret");
        assertThat(requests.getFirst().url().toString()).isEqualTo("https://api.example.com/v1/responses");
        assertThat(requests.getFirst().headers().getFirst("Authorization")).isEqualTo("Bearer env-secret");
        assertThat(bodies.getFirst().path("input").asText()).isEqualTo("Reply OK.");
        assertThat(bodies.getFirst().path("max_output_tokens").asInt()).isEqualTo(16);
        assertThat(bodies.getFirst().path("store").asBoolean()).isFalse();
        probe.test(new PersonalizationSettingsService.EffectiveSettings(0,true,"anthropic","claude-test","https://api.anthropic.com/v1","x-api-key","key","ENVIRONMENT",null)).block();
        assertThat(requests.getLast().url().toString()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(requests.getLast().headers().getFirst("x-api-key")).isEqualTo("key");
        assertThat(requests.getLast().headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(bodies.getLast().path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(bodies.getLast().path("max_tokens").asInt()).isEqualTo(16);
    }
    @Test void sanitizesTransportExceptionsAndReportsSuccessWithoutReturningProviderBody() {
        var failure = new PersonalizationProbe(WebClient.builder().exchangeFunction(r -> Mono.error(new IllegalStateException("secret-in-error"))).build());
        assertThat(failure.test(PersonalizationSettingsServiceTest.fallback()).block().toString()).doesNotContain("secret-in-error");
        var success = new PersonalizationProbe(WebClient.builder().exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.OK).body("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}").build())).build());
        assertThat(success.test(PersonalizationSettingsServiceTest.fallback()).block().success()).isTrue();
    }
    @Test void rejectsHtmlAndErrorBodiesEvenWithSuccessHttpStatus() {
        for (String body : new String[]{"<html>gateway</html>", "{\"error\":{\"message\":\"private-error\"}}", "{\"output\":[]}", "{\"status\":\"incomplete\",\"output\":[]}"}) {
            var probe = new PersonalizationProbe(WebClient.builder().exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build())).build());
            var result = probe.test(PersonalizationSettingsServiceTest.fallback()).block();
            assertThat(result.success()).as(body).isFalse();
            assertThat(result.message()).doesNotContain("gateway", "private-error");
        }
    }
    @Test void acceptsAnthropicTextAndProvidesSafeStatusHints() {
        var anthropic = new PersonalizationSettingsService.EffectiveSettings(0,true,"anthropic","claude-test","https://api.anthropic.com/v1","x-api-key","key","ENVIRONMENT",null);
        var good = new PersonalizationProbe(WebClient.builder().exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.OK).body("{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}").build())).build());
        assertThat(good.test(anthropic).block().success()).isTrue();
        var unauthorized = new PersonalizationProbe(WebClient.builder().exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).body("private-message").build())).build());
        assertThat(unauthorized.test(anthropic).block().message()).containsIgnoringCase("authentication").doesNotContain("private-message");
    }
}
