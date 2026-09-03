package com.camel_hub.advertisement;

import com.camel_hub.advertisement.arxiv.api.ArxivImportController;
import com.camel_hub.advertisement.arxiv.client.AtomFeedParser;
import com.camel_hub.advertisement.campaign.CampaignController;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryExecutor;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryListener;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryScheduler;
import com.camel_hub.advertisement.identity.api.AuthController;
import com.camel_hub.advertisement.job.api.JobController;
import com.camel_hub.advertisement.job.domain.JobStateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.profiles.active=mail-worker",
		"app.auth.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.auth.fingerprint-hmac-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.persistence.enabled=false",
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration,"
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
				+ "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class MailWorkerProfileIsolationTest {

	@Autowired
	private ApplicationContext context;

	@MockitoBean
	private KafkaTemplate<String, String> kafkaTemplate;

	@Test
	void doesNotExposeBusinessApiControllers() {
		assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
		assertThat(context.getBeansOfType(AuthController.class)).isEmpty();
		assertThat(context.getBeansOfType(JobController.class)).isEmpty();
		assertThat(context.getBeansOfType(ArxivImportController.class)).isEmpty();
		assertThat(context.getBeansOfType(CampaignController.class)).isEmpty();
		assertThat(context.getBeansOfType(CampaignDeliveryExecutor.class)).isEmpty();
		assertThat(context.getBeansOfType(CampaignDeliveryListener.class)).isEmpty();
		assertThat(context.getBeansOfType(CampaignDeliveryScheduler.class)).isEmpty();
	}

	@Test
	void loadsOnlyDeliveryWorkerInfrastructure() {
		assertThat(context.getBeansOfType(ObjectMapper.class)).hasSize(1);
		assertThat(context.getBeansOfType(PasswordEncoder.class)).isEmpty();
		assertThat(context.getBeansOfType(JobStateMachine.class)).isEmpty();
		assertThat(context.getBeansOfType(AtomFeedParser.class)).isEmpty();
		assertThat(context.getBeansOfType(KafkaAdmin.NewTopics.class))
				.containsOnlyKeys("campaignDeliveryTopics");
	}

	@Test
	void retainsAnInternalActuatorReadinessEndpointForContainerHealthChecks() throws Exception {
		assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
		int port = ((ReactiveWebServerApplicationContext) context).getWebServer().getPort();
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(
						"http://127.0.0.1:" + port + "/actuator/health/readiness")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\"");
	}
}
