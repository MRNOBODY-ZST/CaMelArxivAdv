package com.camel_hub.advertisement.campaign.partner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PartnerInviteServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private static final String URL = "https://api.camel-hub.cn/partner/claim?token=secret-token";
    private static final byte[] HMAC = new byte[32];

    @Test
    void repeatedPrepareReusesPersistedCapabilityWithoutSecondPost() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        PartnerInviteService service = service(repository, client);

        PartnerInviteService.Invite first = service.prepare(HMAC, "author@example.test").block();
        PartnerInviteService.Invite second = service.prepare(HMAC, "author@example.test").block();

        assertThat(first.claimUrl()).isEqualTo(URL);
        assertThat(second.claimUrl()).isEqualTo(URL);
        assertThat(repository.rows).hasSize(1);
        assertThat(client.posts.get()).isEqualTo(1);
        assertThat(client.gets.get()).isEqualTo(1);
        assertThat(client.lastIdentityHash).isEqualTo(repository.find(HMAC).block().hash());
        assertThat(new String(repository.rows.values().iterator().next().encryptedUrl(), StandardCharsets.UTF_8))
                .doesNotContain("secret-token");
        assertThat(first.toString()).doesNotContain("secret-token");
    }

    @Test
    void readyClaimedRemotelyClearsCapabilityAndRemainsTerminal() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        PartnerInviteService service = service(repository, client);
        service.prepare(HMAC, "author@example.test").block();
        client.status = PartnerInviteClient.IdentityStatus.CLAIMED;

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("CLAIMED");
        assertThat(repository.find(HMAC).block().state()).isEqualTo(PartnerInviteRepository.State.CLAIMED);
        assertThat(repository.find(HMAC).block().encryptedUrl()).isNull();
        client.status = PartnerInviteClient.IdentityStatus.PENDING;
        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("CLAIMED");
        assertThat(client.posts.get()).isEqualTo(1);
        assertThat(client.gets.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-found", "unavailable", "empty"})
    void readyUnconfirmedIdentityNeverReturnsOrReplacesCapability(String response) {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        PartnerInviteService service = service(repository, client);
        service.prepare(HMAC, "author@example.test").block();
        byte[] ciphertext = repository.find(HMAC).block().encryptedUrl();
        client.status = response.equals("not-found") ? PartnerInviteClient.IdentityStatus.NOT_FOUND : null;
        client.failGet = response.equals("unavailable");

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("HELD");
        assertThat(client.posts.get()).isEqualTo(1);
        assertThat(repository.find(HMAC).block().encryptedUrl()).isEqualTo(ciphertext);
    }

    @Test
    void readyPendingDoesNotReturnStaleCapabilityAfterConcurrentClaimedTransition() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        PartnerInviteService service = service(repository, client);
        service.prepare(HMAC, "author@example.test").block();
        String hash = repository.find(HMAC).block().hash();
        client.beforeIdentityResult = () -> repository.transition(HMAC, hash,
                PartnerInviteRepository.State.CLAIMED, NOW).block();

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("CLAIMED");
        assertThat(repository.find(HMAC).block().encryptedUrl()).isNull();
        assertThat(client.posts.get()).isEqualTo(1);
    }

    @Test
    void readyPendingRechecksExpiryAfterRemoteWait() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        PartnerInviteService service = service(repository, client, clock);
        service.prepare(HMAC, "author@example.test").block();
        client.beforeIdentityResult = () -> when(clock.instant()).thenReturn(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("EXPIRED");
        assertThat(client.posts.get()).isEqualTo(1);
    }

    @Test
    void uncertainReservationNeverRepostsAndMayOnlyReconcileClaimed() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        client.failPost = true;
        PartnerInviteService service = service(repository, client);

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class);
        client.status = PartnerInviteClient.IdentityStatus.PENDING;
        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class)
                .hasMessageContaining("HELD");
        assertThat(client.posts.get()).isEqualTo(1);
        client.status = PartnerInviteClient.IdentityStatus.CLAIMED;
        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class)
                .hasMessageContaining("CLAIMED");
        assertThat(client.posts.get()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidMailboxBeforeReservation() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        assertThatThrownBy(() -> service(repository, client).prepare(HMAC, "invalid mailbox").block())
                .isInstanceOf(PartnerInviteException.class)
                .hasMessageContaining("INVALID_INPUT");
        assertThat(repository.rows).isEmpty();
        assertThat(client.posts.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {".author@example.test", "author.@example.test", "author..name@example.test"})
    void rejectsEmptyLocalPartAtomBeforeReservationOrPost(String email) {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();

        assertThatThrownBy(() -> service(repository, client).prepare(HMAC, email).block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("INVALID_INPUT");
        assertThat(repository.rows).isEmpty();
        assertThat(client.posts.get()).isZero();
    }

    @Test
    void confirmedClaimedReconciliationWinsOverReadyPersistedWhileGetWasInFlight() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        PartnerInviteService service = service(repository, client);
        PartnerInviteCrypto crypto = new PartnerInviteCrypto(Base64.getEncoder().encodeToString(new byte[32]));
        repository.reserve(HMAC, "stable-hash", NOW).block();
        client.status = PartnerInviteClient.IdentityStatus.CLAIMED;
        client.beforeIdentityResult = () -> repository.ready(HMAC, "stable-hash",
                crypto.encrypt(HMAC, URL), NOW.plusSeconds(3600), NOW).block();

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("CLAIMED");
        assertThat(repository.find(HMAC).block().state()).isEqualTo(PartnerInviteRepository.State.CLAIMED);
        assertThat(repository.find(HMAC).block().encryptedUrl()).isNull();
        assertThat(client.posts.get()).isZero();
    }

    @Test
    void concurrentPreparesNeverIssueTwoPosts() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        PartnerInviteService service = service(repository, client);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(index -> pool.submit(() -> {
                start.await();
                try { service.prepare(HMAC, "author@example.test").block(); }
                catch (PartnerInviteException ignored) { /* REQUESTING is deliberately held. */ }
                return null;
            })).toList();
            start.countDown();
            for (var call : calls) call.get();
        }
        assertThat(client.posts.get()).isEqualTo(1);
        assertThat(repository.rows).hasSize(1);
    }

    @Test
    void expiredReadyCapabilityIsHeldWithoutReposting() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        PartnerInviteService service = service(repository, client);
        service.prepare(HMAC, "author@example.test").block();
        repository.rows.computeIfPresent(Arrays.toString(HMAC), (ignored, row) ->
                new PartnerInviteRepository.Row(row.hash(), row.state(), row.encryptedUrl(), NOW.minusSeconds(1)));

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("EXPIRED");
        assertThat(client.posts.get()).isEqualTo(1);
    }

    @Test
    void diagnosticsExposeOnlySafeReasonCode() {
        PartnerInviteException exception = new PartnerInviteException(PartnerInviteException.Reason.HELD);
        assertThat(exception.reasonCode()).isEqualTo("HELD");
        assertThat(exception.toString()).doesNotContain("secret-token", "test-bearer");
    }

    @Test
    void alreadyClaimedPostNeverCreatesOrStoresCapability() {
        MemoryRepository repository = new MemoryRepository();
        FakeClient client = new FakeClient();
        client.claimedPost = true;
        PartnerInviteService service = service(repository, client);

        assertThatThrownBy(() -> service.prepare(HMAC, "author@example.test").block())
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("CLAIMED");
        assertThat(repository.rows.values().iterator().next().state())
                .isEqualTo(PartnerInviteRepository.State.CLAIMED);
        assertThat(repository.rows.values().iterator().next().encryptedUrl()).isNull();
        assertThat(client.posts.get()).isEqualTo(1);
    }

    private static PartnerInviteService service(MemoryRepository repository, FakeClient client) {
        return service(repository, client, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PartnerInviteService service(MemoryRepository repository, FakeClient client, Clock clock) {
        PartnerInviteProperties properties = new PartnerInviteProperties(true, "test-bearer",
                Base64.getEncoder().encodeToString(new byte[32]), null);
        return new PartnerInviteService(repository, client,
                new PartnerInviteCrypto(properties.encryptionKeyBase64()), properties,
                clock);
    }

    private static final class FakeClient implements PartnerInviteClient {
        final AtomicInteger posts = new AtomicInteger();
        final AtomicInteger gets = new AtomicInteger();
        String lastIdentityHash;
        boolean failGet;
        boolean failPost;
        boolean claimedPost;
        IdentityStatus status = IdentityStatus.PENDING;
        Runnable beforeIdentityResult = () -> { };

        @Override public Mono<PendingInvite> create(String hash, String email) {
            posts.incrementAndGet();
            if (claimedPost) return Mono.error(new PartnerInviteException(PartnerInviteException.Reason.CLAIMED));
            return failPost ? Mono.error(new IllegalStateException("raw secret-token test-bearer"))
                    : Mono.just(new PendingInvite(URL, NOW.plusSeconds(3600)));
        }

        @Override public Mono<IdentityStatus> identity(String hash) {
            return Mono.fromSupplier(() -> {
                gets.incrementAndGet();
                lastIdentityHash = hash;
                beforeIdentityResult.run();
                if (failGet) throw new IllegalStateException("raw secret-token test-bearer");
                return status;
            });
        }
    }

    private static final class MemoryRepository implements PartnerInviteRepository {
        final Map<String, Row> rows = new ConcurrentHashMap<>();

        @Override public Mono<Boolean> reserve(byte[] emailHmac, String hash, Instant now) {
            return Mono.fromSupplier(() -> rows.putIfAbsent(key(emailHmac),
                    new Row(hash, State.REQUESTING, null, null)) == null);
        }

        @Override public Mono<Row> find(byte[] emailHmac) {
            return Mono.justOrEmpty(rows.get(key(emailHmac)));
        }

        @Override public Mono<Boolean> ready(byte[] emailHmac, String hash, byte[] encryptedUrl, Instant expiresAt, Instant now) {
            return Mono.fromSupplier(() -> rows.computeIfPresent(key(emailHmac), (ignored, row) ->
                    row.state() == State.REQUESTING ? new Row(hash, State.READY, encryptedUrl, expiresAt) : row)
                    .state() == State.READY);
        }

        @Override public Mono<Void> transition(byte[] emailHmac, String hash, State state, Instant now) {
            return Mono.fromRunnable(() -> rows.computeIfPresent(key(emailHmac), (ignored, row) ->
                    row.hash().equals(hash) && (row.state() == State.REQUESTING || row.state() == State.HELD
                            || (state == State.CLAIMED && row.state() == State.READY))
                            ? new Row(hash, state, null, null) : row));
        }

        private static String key(byte[] bytes) { return Arrays.toString(bytes); }
    }
}
