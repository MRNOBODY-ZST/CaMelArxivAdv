package com.camel_hub.advertisement.system.personalization;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PersonalizationSettingsService {
    public record Input(long version, boolean enabled, String provider, String model, String apiOrigin, String authScheme, String apiKey) {
        @Override public String toString() { return "PersonalizationInput[REDACTED]"; }
    }
    public record EffectiveSettings(long version, boolean enabled, String provider, String model, String apiOrigin, String authScheme, String apiKey, String source, Instant updatedAt) {
        public boolean keyConfigured() { return apiKey != null && !apiKey.isBlank(); }
        @Override public String toString() { return "EffectiveSettings[REDACTED]"; }
    }
    public record View(long version, boolean enabled, String provider, String model, String apiOrigin, String authScheme, boolean keyConfigured, String source, Instant updatedAt) {}
    private final DatabaseClient db;
    private final String encryptionKey;
    private final EffectiveSettings fallback;
    public PersonalizationSettingsService(DatabaseClient db, String encryptionKey, EffectiveSettings fallback) {
        this.db = db; this.encryptionKey = encryptionKey; this.fallback = fallback;
    }
    public Mono<EffectiveSettings> effective() {
        return db == null ? Mono.just(fallback) : db.sql("SELECT * FROM personalization_settings WHERE id = 1").map((row, meta) -> read(row)).one().defaultIfEmpty(fallback);
    }
    public Mono<View> get() { return effective().map(this::view); }
    public Mono<EffectiveSettings> prepare(Input input) {
        return effective().map(current -> {
            if (input.version() != current.version()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Settings changed; reload before saving");
            if (!java.util.Set.of("openai", "anthropic").contains(input.provider() == null ? "" : input.provider()) || !java.util.Set.of("bearer", "x-api-key").contains(input.authScheme() == null ? "" : input.authScheme()) || input.model() == null || !input.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid provider, model or authentication scheme");
            String origin;
            try { origin = PersonalizationPolicy.origin(input.apiOrigin()); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
            String key = input.apiKey();
            if (key == null || key.isBlank()) {
                boolean sameOrigin;
                try { sameOrigin = origin.equals(PersonalizationPolicy.origin(current.apiOrigin())); }
                catch (IllegalArgumentException ignored) { sameOrigin = false; }
                if (!input.provider().equals(current.provider()) || !sameOrigin) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A new API key is required when provider or origin changes");
                key = current.apiKey();
            }
            if (key == null) key = "";
            if (key.length() > 4096 || key.chars().anyMatch(c -> c < 33 || c > 126) || input.enabled() && key.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API key is missing or invalid");
            return new EffectiveSettings(current.version(), input.enabled(), input.provider(), input.model(), origin, input.authScheme(), key, "DATABASE", Instant.now());
        });
    }
    private View view(EffectiveSettings s) { return new View(s.version(), s.enabled(), s.provider(), s.model(), s.apiOrigin(), s.authScheme(), s.keyConfigured(), s.source(), s.updatedAt()); }
    private EffectiveSettings read(io.r2dbc.spi.Row row) {
        return new EffectiveSettings(row.get("version", Long.class), Boolean.TRUE.equals(row.get("enabled", Boolean.class)), row.get("provider", String.class), row.get("model", String.class), row.get("api_origin", String.class), row.get("auth_scheme", String.class), crypto().decrypt(row.get("encrypted_key", String.class)), "DATABASE", row.get("updated_at", java.time.OffsetDateTime.class).toInstant());
    }
    private PersonalizationSecretCrypto crypto() { return new PersonalizationSecretCrypto(encryptionKey); }
    public Mono<Void> snapshot(UUID jobId, EffectiveSettings settings) {
        if (db == null) return Mono.empty(); // Compatibility for explicitly persistence-disabled profiles.
        return Mono.defer(() -> bind(db.sql("""
            INSERT INTO personalization_job_snapshots
            (job_id, version, enabled, provider, model, api_origin, auth_scheme, encrypted_key)
            VALUES (:job, :version, :enabled, :provider, :model, :origin, :auth, :key)
            """).bind("job", jobId).bind("version", settings.version()), settings).fetch().rowsUpdated().then());
    }
    public Mono<View> save(Input input) {
        if (db == null) return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Settings persistence is unavailable"));
        return prepare(input).flatMap(s -> bind(db.sql("""
            INSERT INTO personalization_settings (id, version, enabled, provider, model, api_origin, auth_scheme, encrypted_key)
            SELECT 1, 1, :enabled, :provider, :model, :origin, :auth, :key WHERE :version = 0
            ON CONFLICT (id) DO UPDATE SET version = personalization_settings.version + 1,
            enabled = :enabled, provider = :provider, model = :model, api_origin = :origin,
            auth_scheme = :auth, encrypted_key = :key, updated_at = now()
            WHERE personalization_settings.version = :version
            RETURNING *
            """).bind("version", s.version()), s).map((row, meta) -> read(row)).one()
            .switchIfEmpty(Mono.defer(() -> {
                if (s.version() == 0) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Settings changed; reload before saving"));
                return bind(db.sql("""
                    UPDATE personalization_settings SET version = version + 1, enabled = :enabled,
                    provider = :provider, model = :model, api_origin = :origin, auth_scheme = :auth,
                    encrypted_key = :key, updated_at = now() WHERE id = 1 AND version = :version RETURNING *
                    """).bind("version", s.version()), s).map((row, meta) -> read(row)).one()
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Settings changed; reload before saving")));
            })).map(this::view));
    }
    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec sql, EffectiveSettings s) {
        return sql.bind("enabled", s.enabled()).bind("provider", s.provider()).bind("model", s.model())
            .bind("origin", s.apiOrigin()).bind("auth", s.authScheme()).bind("key", crypto().encrypt(s.apiKey()));
    }
    public Mono<EffectiveSettings> jobConfiguration(UUID jobId) {
        return (db == null ? Mono.<EffectiveSettings>empty() : db.sql("SELECT * FROM personalization_job_snapshots WHERE job_id = :job").bind("job", jobId).map((row, meta) -> read(row)).one())
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job configuration was not found")));
    }
}
