package com.camel_hub.advertisement.system.personalization;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import java.util.List;
public class PersonalizationProbe {
    private static final PublicAddressResolver RESOLVER = new PublicAddressResolver(java.net.InetAddress::getAllByName);
    private final WebClient client;
    public PersonalizationProbe() {
        this(WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
            .resolver(RESOLVER).followRedirect(false).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(10))))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(65536)).build());
    }
    PersonalizationProbe(WebClient client) { this.client = client; }
    public record Result(boolean success, String message, String provider, String model, long latencyMs) {}
    public Mono<Result> test(PersonalizationSettingsService.EffectiveSettings settings) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            if (!settings.keyConfigured()) return Mono.just(result(false, "API key is required to test the provider connection", settings, start));
            boolean anthropic = settings.provider().equals("anthropic");
            var request = client.post().uri(settings.apiOrigin() + (anthropic ? "/messages" : "/responses"))
                .header("Content-Type", "application/json");
            if (settings.authScheme().equals("bearer")) request.header("Authorization", "Bearer " + settings.apiKey());
            else request.header("x-api-key", settings.apiKey());
            if (anthropic) request.header("anthropic-version", "2023-06-01");
            Map<String, Object> body = anthropic
                ? Map.of("model", settings.model(), "max_tokens", 16, "messages", List.of(Map.of("role", "user", "content", "Reply OK.")))
                : Map.of("model", settings.model(), "max_output_tokens", 16, "input", "Reply OK.", "store", false);
            return request.bodyValue(body).exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful()) {
                    String message = switch (response.statusCode().value()) {
                        case 401, 403 -> "Provider authentication failed; check API key and authentication scheme";
                        case 404 -> "Provider endpoint or model was not found";
                        case 429 -> "Provider rate or quota limit reached";
                        default -> "Provider rejected the test request";
                    };
                    return response.releaseBody().thenReturn(result(false, message, settings, start));
                }
                return response.bodyToMono(String.class).defaultIfEmpty("").map(value -> {
                    boolean valid = validResponse(value, anthropic);
                    return result(valid, valid ? "Provider connection succeeded" : "Provider returned an unexpected response", settings, start);
                });
            }).timeout(Duration.ofSeconds(15)).onErrorResume(error -> Mono.just(new Result(false,
                error instanceof java.util.concurrent.TimeoutException ? "Provider connection timed out" : "Provider connection failed", settings.provider(), settings.model(), (System.nanoTime() - start) / 1_000_000)));
        });
    }
    private Result result(boolean success, String message, PersonalizationSettingsService.EffectiveSettings settings, long start) {
        return new Result(success, message, settings.provider(), settings.model(), (System.nanoTime() - start) / 1_000_000);
    }
    private boolean validResponse(String body, boolean anthropic) {
        try {
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
            if (json == null || json.has("error")) return false;
            if (anthropic) return hasText(json.path("content"), "text");
            if (!"completed".equals(json.path("status").asString())) return false;
            for (var item : json.path("output")) {
                if ("message".equals(item.path("type").asString()) && hasText(item.path("content"), "output_text")) return true;
            }
            return false;
        } catch (RuntimeException ignored) { return false; }
    }
    private boolean hasText(tools.jackson.databind.JsonNode content, String type) {
        if (!content.isArray()) return false;
        for (var item : content) {
            if (type.equals(item.path("type").asString()) && item.path("text").isString() && !item.path("text").asString().isBlank()) return true;
        }
        return false;
    }
}
