package com.camel_hub.advertisement.campaign.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartnerInviteClientTest {
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private static final String HASH = "abc_SAFE-hash";
    private static final String URL = "https://api.camel-hub.cn/partner/claim?token=secret-token";
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger redirects = new AtomicInteger();
    private HttpServer server;
    private HttpPartnerInviteClient client;

    @BeforeEach
    void startLocalServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.createContext("/redirect-target", exchange -> {
            redirects.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
        client = new HttpPartnerInviteClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                origin, new PartnerInviteProperties(true, "local-bearer-secret", "", null),
                json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach void stopLocalServer() { server.stop(0); }

    @Test
    void createSendsOnlyHashAndEmailWithBearerAuthorization() throws Exception {
        reply.set(new Reply(201, "{\"success\":true,\"data\":{\"status\":\"pending\",\"hash\":\""
                + HASH + "\",\"claim_url\":\"" + URL + "\",\"expires_at\":1790786826}}", null));

        PartnerInviteClient.PendingInvite invite = client.create(HASH, "author@example.test").block();

        assertThat(invite.claimUrl()).isEqualTo(URL);
        assertThat(path.get()).isEqualTo("/api/partner/invites");
        assertThat(auth.get()).isEqualTo("Bearer local-bearer-secret");
        JsonNode sent = json.readTree(body.get());
        assertThat(sent.size()).isEqualTo(2);
        assertThat(sent.path("hash").asText()).isEqualTo(HASH);
        assertThat(sent.path("email").asText()).isEqualTo("author@example.test");
        assertThat(invite.toString()).doesNotContain("secret-token");
    }

    @Test
    void rejectsHostileClaimUrlsWithoutLeakingPayload() {
        for (String url : new String[] {
                "https://attacker.invalid/partner/claim?token=x",
                "https://api.camel-hub.cn.evil.invalid/partner/claim?token=x",
                "https://user@api.camel-hub.cn/partner/claim?token=x",
                "https://api.camel-hub.cn/partner/claim?token=x#fragment",
                "https://api.camel-hub.cn/partner/claim?token=x&next=y",
                "https://api.camel-hub.cn/partner/%63laim?token=x",
                "https://api.camel-hub.cn/partner/claim?token=x%0d%0a"
        }) {
            reply.set(new Reply(200, "{\"success\":true,\"data\":{\"status\":\"pending\",\"hash\":\""
                    + HASH + "\",\"claim_url\":\"" + url + "\",\"expires_at\":1790786826}}", null));
            assertThatThrownBy(() -> client.create(HASH, "author@example.test").block())
                    .isInstanceOf(PartnerInviteException.class)
                    .hasMessageNotContaining("local-bearer-secret")
                    .hasMessageNotContaining("token=x");
        }
    }

    @Test
    void oversizedResponseAndRedirectFailClosedWithoutFollowing() {
        reply.set(new Reply(200, "x".repeat(20_000), null));
        assertThatThrownBy(() -> client.create(HASH, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class);
        reply.set(new Reply(302, "", "/redirect-target"));
        assertThatThrownBy(() -> client.create(HASH, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class);
        assertThat(redirects.get()).isZero();
    }

    @Test
    void identityReadsDocumentedFlatClaimedButUnknown404IsNotPermissionToRetry() {
        reply.set(new Reply(200, "{\"status\":\"claimed\",\"user_id\":123}", null));
        assertThat(client.identity(HASH).block()).isEqualTo(PartnerInviteClient.IdentityStatus.CLAIMED);
        assertThat(path.get()).isEqualTo("/api/partner/identities/" + HASH);
        reply.set(new Reply(404, "identity not found", null));
        assertThatThrownBy(() -> client.identity(HASH).block())
                .isInstanceOf(PartnerInviteException.class);
    }

    @Test
    void claimedPostResponseIsReportedWithoutInventingAUrl() {
        reply.set(new Reply(200, "{\"success\":true,\"data\":{\"status\":\"claimed\",\"user_id\":123}}", null));
        assertThatThrownBy(() -> client.create(HASH, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class)
                .hasMessageContaining("CLAIMED");
    }

    private void handle(HttpExchange exchange) throws IOException {
        auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        path.set(exchange.getRequestURI().getPath());
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Reply current = reply.get();
        if (current.redirect != null) exchange.getResponseHeaders().add("Location", current.redirect);
        byte[] response = current.body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(current.status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record Reply(int status, String body, String redirect) { }
}
