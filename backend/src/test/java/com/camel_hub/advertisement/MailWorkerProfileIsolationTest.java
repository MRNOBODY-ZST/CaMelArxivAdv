package com.camel_hub.advertisement;

import com.camel_hub.advertisement.arxiv.api.ArxivImportController;
import com.camel_hub.advertisement.identity.api.AuthController;
import com.camel_hub.advertisement.job.api.JobController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
		"spring.profiles.active=mail-worker",
		"app.auth.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.auth.fingerprint-hmac-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.persistence.enabled=false",
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration,"
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
				+ "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class MailWorkerProfileIsolationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void doesNotExposeBusinessApiControllers() {
		assertThat(context.getBeansOfType(AuthController.class)).isEmpty();
		assertThat(context.getBeansOfType(JobController.class)).isEmpty();
		assertThat(context.getBeansOfType(ArxivImportController.class)).isEmpty();
	}
}
