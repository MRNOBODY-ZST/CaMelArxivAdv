package com.camel_hub.advertisement.campaign.partner;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PartnerInviteRepositoryIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("partner_invite_test").withUsername("camel").withPassword("camel-test-only");
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private static DatabaseClient database;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        database = DatabaseClient.create(ConnectionFactories.get(url));
    }

    @Test void concurrentReservationAcrossRepositoryInstancesHasOneWinnerAndOneStableHash() {
        byte[] contact = uniqueContact();
        PartnerInviteRepository first = new R2dbcPartnerInviteRepository(database);
        PartnerInviteRepository second = new R2dbcPartnerInviteRepository(database);

        var results = Mono.zip(first.reserve(contact, "binding-one", NOW),
                second.reserve(contact, "binding-two", NOW)).block();

        assertThat(results.getT1() ^ results.getT2()).isTrue();
        assertThat(first.find(contact).block().hash()).isIn("binding-one", "binding-two");
        assertThat(second.find(contact).block().hash()).isEqualTo(first.find(contact).block().hash());
    }

    @Test void readyCapabilitySurvivesRepositoryReloadOnlyAsCiphertext() {
        byte[] contact = uniqueContact();
        String url = "https://api.camel-hub.cn/partner/claim?token=secret-token";
        PartnerInviteCrypto crypto = new PartnerInviteCrypto(Base64.getEncoder().encodeToString(new byte[32]));
        PartnerInviteRepository first = new R2dbcPartnerInviteRepository(database);
        assertThat(first.reserve(contact, "stable-binding", NOW).block()).isTrue();
        assertThat(first.ready(contact, "stable-binding", crypto.encrypt(contact, url),
                NOW.plusSeconds(3600), NOW).block()).isTrue();

        PartnerInviteRepository.Row reloaded = new R2dbcPartnerInviteRepository(database).find(contact).block();
        assertThat(reloaded.state()).isEqualTo(PartnerInviteRepository.State.READY);
        assertThat(reloaded.hash()).isEqualTo("stable-binding");
        assertThat(new String(reloaded.encryptedUrl(), StandardCharsets.UTF_8)).doesNotContain("secret-token");
        assertThat(crypto.decrypt(contact, reloaded.encryptedUrl())).isEqualTo(url);
        assertThat(reloaded.toString()).doesNotContain("secret-token");
    }

    @Test void confirmedClaimClearsReadyCapabilityPersistedDuringReconciliation() {
        byte[] contact = uniqueContact();
        PartnerInviteRepository repository = new R2dbcPartnerInviteRepository(database);
        PartnerInviteCrypto crypto = new PartnerInviteCrypto(Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(repository.reserve(contact, "ready-before-claim", NOW).block()).isTrue();
        assertThat(repository.ready(contact, "ready-before-claim", crypto.encrypt(contact,
                "https://api.camel-hub.cn/partner/claim?token=secret-token"),
                NOW.plusSeconds(3600), NOW).block()).isTrue();

        repository.transition(contact, "ready-before-claim", PartnerInviteRepository.State.CLAIMED, NOW).block();

        PartnerInviteRepository.Row reloaded = new R2dbcPartnerInviteRepository(database).find(contact).block();
        assertThat(reloaded.state()).isEqualTo(PartnerInviteRepository.State.CLAIMED);
        assertThat(reloaded.encryptedUrl()).isNull();
        assertThat(reloaded.expiresAt()).isNull();
    }

    private static byte[] uniqueContact() {
        byte[] contact = new byte[32];
        new java.security.SecureRandom().nextBytes(contact);
        return contact;
    }
}
