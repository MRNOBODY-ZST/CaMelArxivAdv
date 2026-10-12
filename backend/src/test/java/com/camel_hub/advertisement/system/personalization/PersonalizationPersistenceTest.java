package com.camel_hub.advertisement.system.personalization;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PersonalizationPersistenceTest {
    @Test void savesReloadsRejectsStaleWritesAndFreezesIndependentJobSnapshots() {
        try (var pg = new PostgreSQLContainer("postgres:18-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
            var db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://" + pg.getUsername() + ":" + pg.getPassword() + "@" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/" + pg.getDatabaseName()));
            var service = new PersonalizationSettingsService(db, PersonalizationSettingsServiceTest.KEY, PersonalizationSettingsServiceTest.fallback());
            var input = new PersonalizationSettingsService.Input(0, true, "anthropic", "model-a", "https://api.anthropic.com", "x-api-key", "db-secret");
            assertThat(service.save(input).block().version()).isEqualTo(1);
            assertThatThrownBy(() -> service.save(input).block()).hasMessageContaining("changed");
            var reloaded = new PersonalizationSettingsService(db, PersonalizationSettingsServiceTest.KEY, PersonalizationSettingsServiceTest.fallback());
            var frozen = reloaded.effective().block();
            assertThat(frozen.apiKey()).isEqualTo("db-secret");
            var id = UUID.randomUUID(); reloaded.snapshot(id, frozen).block();
            service.save(new PersonalizationSettingsService.Input(1, true, "anthropic", "model-b", "https://api.anthropic.com", "x-api-key", "next-secret")).block();
            assertThat(service.jobConfiguration(id).block().apiKey()).isEqualTo("db-secret");
            assertThat(service.jobConfiguration(id).block().model()).isEqualTo("model-a");
            assertThatThrownBy(() -> service.snapshot(id, service.effective().block()).block()).isNotNull();
            assertThat(db.sql("SELECT encrypted_key FROM personalization_settings").map((row, meta) -> row.get(0, String.class)).one().block()).doesNotContain("next-secret");
            var update = new PersonalizationSettingsService.Input(2, true, "anthropic", "model-c", "https://api.anthropic.com", "x-api-key", "last-secret");
            var writes = reactor.core.publisher.Mono.zip(service.save(update).materialize(), service.save(update).materialize()).block();
            assertThat(java.util.stream.Stream.of(writes.getT1(), writes.getT2()).filter(reactor.core.publisher.Signal::isOnNext).count()).isEqualTo(1);
            assertThat(service.get().block().version()).isEqualTo(3);
            var tx = org.springframework.transaction.reactive.TransactionalOperator.create(new org.springframework.r2dbc.connection.R2dbcTransactionManager(db.getConnectionFactory()));
            UUID rolledBack = UUID.randomUUID();
            assertThatThrownBy(() -> tx.transactional(service.snapshot(rolledBack, frozen).then(reactor.core.publisher.Mono.error(new IllegalStateException("rollback")))).block()).hasMessage("rollback");
            assertThatThrownBy(() -> service.jobConfiguration(rolledBack).block()).hasMessageContaining("not found");
        }
    }
}
