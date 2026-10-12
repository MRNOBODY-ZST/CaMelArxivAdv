package com.camel_hub.advertisement.system.personalization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration(proxyBeanMethods = false)
@Profile("!mail-worker")
public class PersonalizationConfiguration {
    @Bean PersonalizationSettingsService personalizationSettingsService(ObjectProvider<DatabaseClient> db, Environment env) {
        String prefix = "app.personalization-settings.";
        String provider = env.getProperty("app.personalization.provider", "openai");
        String origin = env.getProperty(prefix + "api-origin", "");
        if (origin.isBlank()) origin = provider.equals("anthropic") ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1";
        String auth = env.getProperty(prefix + "auth-scheme", "");
        // The legacy OpenAI client always used bearer, even when the shared env default was x-api-key.
        if (provider.equals("openai")) auth = "bearer";
        if (auth.isBlank()) auth = provider.equals("anthropic") ? "x-api-key" : "bearer";
        var fallback = new PersonalizationSettingsService.EffectiveSettings(0,
            env.getProperty("app.personalization.enabled", Boolean.class, false), provider,
            env.getProperty("app.personalization.model", "gpt-5.6-luna"), PersonalizationPolicy.origin(origin), auth,
            env.getProperty(prefix + "api-key", ""), "ENVIRONMENT", null);
        return new PersonalizationSettingsService(env.getProperty("app.persistence.enabled", Boolean.class, true) ? db.getIfAvailable() : null,
            env.getProperty(prefix + "encryption-key-base64", ""), fallback);
    }
    @Bean PersonalizationProbe personalizationProbe() { return new PersonalizationProbe(); }
}
