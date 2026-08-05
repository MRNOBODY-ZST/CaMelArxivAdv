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

	@BeforeEach
	void setUp() {
		webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
				.apply(springSecurity())
				.build()
				.mutateWith(mockUser());
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
}
