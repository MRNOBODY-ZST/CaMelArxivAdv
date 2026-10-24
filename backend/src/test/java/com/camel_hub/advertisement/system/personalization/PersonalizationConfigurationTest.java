package com.camel_hub.advertisement.system.personalization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class PersonalizationConfigurationTest {
    @Test void openAiEnvironmentPreservesLegacyBearerEvenWhenOldDefaultSaysApiKey() {
        new ApplicationContextRunner().withUserConfiguration(PersonalizationConfiguration.class)
            .withPropertyValues("app.persistence.enabled=false", "app.personalization.provider=openai", "app.personalization-settings.auth-scheme=x-api-key")
            .run(app -> {
                assertThat(app).hasNotFailed();
                var settings = app.getBean(PersonalizationSettingsService.class).effective().block();
                assertThat(settings.authScheme()).isEqualTo("bearer");
                assertThat(settings.apiOrigin()).isEqualTo("https://api.openai.com/v1");
                assertThat(settings.keyConfigured()).isFalse();
            });
    }
}
