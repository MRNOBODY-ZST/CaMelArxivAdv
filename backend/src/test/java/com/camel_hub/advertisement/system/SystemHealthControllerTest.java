package com.camel_hub.advertisement.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = {
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
class SystemHealthControllerTest {

	@Autowired
	private ApplicationContext applicationContext;

	private WebTestClient webTestClient;
	private WebTestClient anonymousWebTestClient;

	@BeforeEach
	void setUp() {
		webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
				.apply(springSecurity())
				.build()
				.mutateWith(mockUser());
		anonymousWebTestClient = WebTestClient.bindToApplicationContext(applicationContext)
				.apply(springSecurity())
				.build();
	}

	@Test
	void exposesSystemHealthWithTraceHeader() {
		webTestClient.get()
				.uri("/api/v1/system/health")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueMatches("X-Trace-Id", "[a-f0-9]{32}")
				.expectBody()
				.jsonPath("$.status").isEqualTo("UP")
				.jsonPath("$.checkedAt").isNotEmpty();
	}

	@Test
	void permitsHealthChecksWithoutAuthentication() {
		anonymousWebTestClient.get()
				.uri("/api/v1/system/health")
				.exchange()
				.expectStatus().isOk();
	}
}
