package com.camel_hub.advertisement.campaign.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;

/** Strict, bounded HTTP boundary. Errors never include response bodies or request headers. */
public final class HttpPartnerInviteClient implements PartnerInviteClient {
    private static final URI PRODUCTION_ORIGIN = URI.create("https://api.camel-hub.cn");
    private static final Pattern SAFE_HASH = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9._~-]{1,1024}$");
    private static final int MAX_BODY = 16 * 1024;
    private final HttpClient http;
    private final URI origin;
    private final PartnerInviteProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public HttpPartnerInviteClient(PartnerInviteProperties properties, ObjectMapper json, Clock clock) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.requestTimeout()).build(), PRODUCTION_ORIGIN, properties, json, clock);
    }

    /** Local-server test seam; production construction always uses the fixed HTTPS origin. */
    HttpPartnerInviteClient(HttpClient http, URI origin, PartnerInviteProperties properties,
            ObjectMapper json, Clock clock) {
        this.http = http;
        this.origin = origin;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override public Mono<PendingInvite> create(String hash, String email) {
        return Mono.defer(() -> {
            if (!validHash(hash) || email == null || email.length() > 50) return unavailable();
            try {
                byte[] body = json.writeValueAsBytes(new CreatePayload(hash, email));
                HttpRequest request = base(origin.resolve("/api/partner/invites"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                return exchange(request).map(response -> {
                    JsonNode data = data(response);
                    if ("claimed".equals(text(data, "status"))) {
                        throw new PartnerInviteException(PartnerInviteException.Reason.CLAIMED);
                    }
                    if (!"pending".equals(text(data, "status")) || !hash.equals(text(data, "hash"))) {
                        throw safeError();
                    }
                    String url = text(data, "claim_url");
                    JsonNode expiry = data.get("expires_at");
                    if (!validClaimUrl(url) || expiry == null || !expiry.isIntegralNumber()
                            || !expiry.canConvertToLong()) throw safeError();
                    Instant expiresAt = Instant.ofEpochSecond(expiry.longValue());
                    if (!expiresAt.isAfter(clock.instant())) throw safeError();
                    return new PendingInvite(url, expiresAt);
                });
            } catch (Exception ignored) {
                return unavailable();
            }
        });
    }

    @Override public Mono<IdentityStatus> identity(String hash) {
        return Mono.defer(() -> {
            if (!validHash(hash)) return unavailable();
            HttpRequest request = base(origin.resolve("/api/partner/identities/" + hash)).GET().build();
            return exchange(request).map(response -> {
                JsonNode data = data(response);
                return switch (text(data, "status")) {
                    case "claimed" -> IdentityStatus.CLAIMED;
                    case "pending" -> IdentityStatus.PENDING;
                    case "not_found" -> IdentityStatus.NOT_FOUND;
                    default -> throw safeError();
                };
            });
        });
    }

    private HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.bearerToken())
                .header("Accept", "application/json");
    }

    private Mono<JsonNode> exchange(HttpRequest request) {
        return Mono.fromCallable(() -> {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200 && response.statusCode() != 201) throw safeError();
                byte[] bytes = body.readNBytes(MAX_BODY + 1);
                if (bytes.length == 0 || bytes.length > MAX_BODY) throw safeError();
                return json.readTree(bytes);
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.requestTimeout())
                .onErrorMap(error -> safeError());
    }

    private JsonNode data(JsonNode response) {
        if (response == null || !response.isObject()) throw safeError();
        JsonNode success = response.get("success");
        if (success != null && !success.isNull() && !success.asBoolean(false)) throw safeError();
        JsonNode wrapped = response.get("data");
        JsonNode data = wrapped == null ? response : wrapped;
        if (!data.isObject()) throw safeError();
        return data;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static boolean validHash(String hash) { return hash != null && SAFE_HASH.matcher(hash).matches(); }

    static boolean validClaimUrl(String value) {
        if (value == null || value.length() > 2048 || value.chars().anyMatch(c -> c <= 0x20 || c >= 0x7f)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && "api.camel-hub.cn".equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getFragment() == null
                    && "api.camel-hub.cn".equals(uri.getRawAuthority())
                    && "/partner/claim".equals(uri.getRawPath())
                    && uri.getRawQuery() != null && uri.getRawQuery().startsWith("token=")
                    && SAFE_TOKEN.matcher(uri.getRawQuery().substring(6)).matches();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static PartnerInviteException safeError() {
        return new PartnerInviteException(PartnerInviteException.Reason.UNAVAILABLE);
    }

    private static <T> Mono<T> unavailable() { return Mono.error(safeError()); }

    private record CreatePayload(String hash, String email) { }
}
