package com.camel_hub.advertisement.identity.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = {
		"app.auth.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.auth.fingerprint-hmac-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
				+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
				+ "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
})
class IdentityOpenApiIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_openapi_test")
			.withUsername("camel")
			.withPassword("camel-test-only");
	static {
		POSTGRES.start();
	}

	@Autowired
	private ApplicationContext applicationContext;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
		registry.add("spring.flyway.user", POSTGRES::getUsername);
		registry.add("spring.flyway.password", POSTGRES::getPassword);
		registry.add("spring.r2dbc.url", IdentityOpenApiIntegrationTest::r2dbcUrl);
		registry.add("spring.r2dbc.username", POSTGRES::getUsername);
		registry.add("spring.r2dbc.password", POSTGRES::getPassword);
	}

	@Test
	void publishesEveryPhaseTwoIdentityAdministrationPath() {
		WebTestClient webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
				.apply(springSecurity())
				.build();

		webTestClient.get().uri("/api/openapi.json")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.paths['/api/v1/auth/login']").exists()
				.jsonPath("$.paths['/api/v1/auth/refresh']").exists()
				.jsonPath("$.paths['/api/v1/auth/me']").exists()
				.jsonPath("$.paths['/api/v1/users']").exists()
				.jsonPath("$.paths['/api/v1/users/{id}/reset-password']").exists()
				.jsonPath("$.paths['/api/v1/roles']").exists()
				.jsonPath("$.paths['/api/v1/permissions']").exists()
				.jsonPath("$.paths['/api/v1/audit-logs']").exists();
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
