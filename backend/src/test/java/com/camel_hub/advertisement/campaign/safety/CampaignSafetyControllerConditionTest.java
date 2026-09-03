package com.camel_hub.advertisement.campaign.safety;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CampaignSafetyControllerConditionTest {

	@Test
	void controllerRemainsAvailableForReadAndCancelWhenSafetySendingIsDisabled() {
		context("api").withPropertyValues(
				"app.persistence.enabled=true", "app.campaign-safety.enabled=true")
				.run(application -> assertThat(application).hasSingleBean(CampaignSafetyController.class));
		context("api").withPropertyValues(
				"app.persistence.enabled=true", "app.campaign-safety.enabled=false")
				.run(application -> assertThat(application).hasSingleBean(CampaignSafetyController.class));
		context("api").withPropertyValues(
				"app.persistence.enabled=false", "app.campaign-safety.enabled=true")
				.run(application -> assertThat(application).doesNotHaveBean(CampaignSafetyController.class));
		context("mail-worker").withPropertyValues(
				"app.persistence.enabled=true", "app.campaign-safety.enabled=true")
				.run(application -> assertThat(application).doesNotHaveBean(CampaignSafetyController.class));
	}

	private ApplicationContextRunner context(String profile) {
		return new ApplicationContextRunner()
				.withInitializer(application -> application.getEnvironment().setActiveProfiles(profile))
				.withUserConfiguration(CampaignSafetyController.class)
				.withBean(CampaignSafetyService.class, () -> mock(CampaignSafetyService.class));
	}
}
