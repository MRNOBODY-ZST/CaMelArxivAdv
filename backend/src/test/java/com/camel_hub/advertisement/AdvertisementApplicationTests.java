package com.camel_hub.advertisement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class AdvertisementApplicationTests {

	@Test
	void contextLoads() {
	}

}
