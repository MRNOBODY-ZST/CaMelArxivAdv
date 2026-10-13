package com.camel_hub.advertisement.campaign.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignSafetyPropertiesBindingTest {
	@Test
	void bindsNamedMapAlongsideTheBackwardCompatibleFixedRecipient() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
				.withPropertyValues("app.campaign-safety.enabled=true",
						"app.campaign-safety.recipient=fixed@example.test",
						"app.campaign-safety.maximum-recipients=20",
						"app.campaign-safety.destinations.qq=owner@QQ.COM",
						"app.campaign-safety.destinations.gmail=")
				.run(context -> {
					assertThat(context).hasNotFailed();
					CampaignSafetyProperties properties = context.getBean(CampaignSafetyProperties.class);
					assertThat(properties.validatedRecipient()).isEqualTo("fixed@example.test");
					assertThat(properties.destinations()).containsOnlyKeys("qq").containsEntry("qq", "owner@qq.com");
				});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CampaignSafetyProperties.class)
	static class PropertiesConfiguration { }
}
