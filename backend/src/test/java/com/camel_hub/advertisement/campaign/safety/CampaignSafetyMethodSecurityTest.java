package com.camel_hub.advertisement.campaign.safety;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class CampaignSafetyMethodSecurityTest {

	private final ReactiveWebApplicationContextRunner context = new ReactiveWebApplicationContextRunner()
			.withUserConfiguration(MethodSecurityConfiguration.class)
			.withBean(CampaignSafetyService.class, () -> mock(CampaignSafetyService.class));

	@Test
	void realReactiveMethodSecurityContextEnforcesAnonymousDeniedAndAuthorizedAccess() {
		context.run(application -> {
			assertThat(application).hasNotFailed();
			CampaignSafetyController controller = application.getBean(CampaignSafetyController.class);
			assertThat(AopUtils.isAopProxy(controller)).isTrue();
			UUID campaign = UUID.randomUUID();
			CampaignSafetyService service = application.getBean(CampaignSafetyService.class);
			when(service.list(campaign)).thenReturn(Mono.just(List.of()));
			WebTestClient anonymous = WebTestClient.bindToApplicationContext(application)
					.apply(springSecurity()).build();
			anonymous.get().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
					.exchange().expectStatus().isUnauthorized();
			anonymous.mutateWith(mockUser("sender").authorities(new SimpleGrantedAuthority("campaign:send")))
					.get().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
					.exchange().expectStatus().isForbidden();
			anonymous.mutateWith(mockUser("reader").authorities(new SimpleGrantedAuthority("campaign:read")))
					.get().uri("/api/v1/campaigns/{campaign}/safety-runs", campaign)
					.exchange().expectStatus().isOk().expectBody().json("[]");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebFlux
	@org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
	@EnableReactiveMethodSecurity
	static class MethodSecurityConfiguration {
		@Bean
		CampaignSafetyController campaignSafetyController(CampaignSafetyService service) {
			return new CampaignSafetyController(service);
		}

		@Bean
		SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
			return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
					.authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
					.build();
		}
	}
}
