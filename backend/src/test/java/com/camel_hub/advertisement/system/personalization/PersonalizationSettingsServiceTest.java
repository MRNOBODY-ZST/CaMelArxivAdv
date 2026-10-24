package com.camel_hub.advertisement.system.personalization;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.Base64;

class PersonalizationSettingsServiceTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static PersonalizationSettingsService.EffectiveSettings fallback() {
        return new PersonalizationSettingsService.EffectiveSettings(0, true, "openai", "test-model", "https://api.example.com/v1", "bearer", "env-secret", "ENVIRONMENT", null);
    }
    @Test void preservesKeyOnlyForSameNormalizedProviderAndOrigin() {
        var service = new PersonalizationSettingsService(null, KEY, fallback());
        var same = service.prepare(new PersonalizationSettingsService.Input(0, true, "openai", "other-model", "https://API.example.com/", "bearer", " ")).block();
        assertThat(same.apiKey()).isEqualTo("env-secret");
        assertThatThrownBy(() -> service.prepare(new PersonalizationSettingsService.Input(0, true, "openai", "m", "https://other.example.com", "bearer", "")).block()).hasMessageContaining("new API key");
        assertThatThrownBy(() -> service.prepare(new PersonalizationSettingsService.Input(4, true, "openai", "m", "https://api.example.com", "bearer", "key")).block()).hasMessageContaining("changed");
    }
    @Test void fallbackAndSecretDtosDoNotExposeKeys() {
        var service = new PersonalizationSettingsService(null, KEY, fallback());
        assertThat(service.effective().block().source()).isEqualTo("ENVIRONMENT");
        assertThat(service.effective().block().toString()).doesNotContain("env-secret");
        assertThat(new PersonalizationSettingsService.Input(0, true, "openai", "m", "https://example.com", "bearer", "private-key").toString()).doesNotContain("private-key");
        assertThat(service.get().block().keyConfigured()).isTrue();
    }
    @Test void preservesKeyAcrossEquivalentIpv6OriginSpellings() {
        for (String currentOrigin : new String[]{"https://[2606:4700:4700::1111]/v1", "https://[2606:4700:4700:0000:0000:0000:0000:1111]:443/"}) {
            var current = new PersonalizationSettingsService.EffectiveSettings(0, true, "openai", "test-model",
                    currentOrigin, "bearer", "env-secret", "ENVIRONMENT", null);
            var service = new PersonalizationSettingsService(null, KEY, current);
            for (String inputOrigin : new String[]{"https://[2606:4700:4700:0:0:0:0:1111]/v1", "https://[2606:4700:4700::1111]/v1"}) {
                var prepared = service.prepare(new PersonalizationSettingsService.Input(0, true, "openai", "m", inputOrigin, "bearer", "")).block();
                assertThat(prepared.apiKey()).isEqualTo("env-secret");
                assertThat(prepared.apiOrigin()).isEqualTo("https://[2606:4700:4700:0:0:0:0:1111]/v1");
            }
            assertThatThrownBy(() -> service.prepare(new PersonalizationSettingsService.Input(0, true, "openai", "m", "https://[2606:4700:4700::1001]/v1", "bearer", "")).block())
                    .hasMessageContaining("new API key");
        }
    }
    @Test void refusesKeyReuseFromMissingOrInvalidExistingOrigins() {
        for (String currentOrigin : new String[]{null, "", "https://[::1]/v1", "not-an-origin"}) {
            var current = new PersonalizationSettingsService.EffectiveSettings(0, false, "openai", "test-model",
                    currentOrigin, "bearer", "env-secret", "ENVIRONMENT", null);
            var service = new PersonalizationSettingsService(null, KEY, current);
            assertThatThrownBy(() -> service.prepare(new PersonalizationSettingsService.Input(0, true, "openai", "m", "https://[2606:4700:4700::1111]/v1", "bearer", "")).block())
                    .hasMessageContaining("new API key");
        }
    }
    @Test void rejectsModelBeyondPipelineLimitWithoutEchoingInput() {
        var service = new PersonalizationSettingsService(null, KEY, fallback());
        assertThatThrownBy(() -> service.prepare(new PersonalizationSettingsService.Input(0, true, "openai", "m".repeat(121), "https://api.example.com", "bearer", "private-key")).block())
            .hasMessageContaining("Invalid provider, model or authentication scheme").hasMessageNotContaining("private-key");
    }
}
