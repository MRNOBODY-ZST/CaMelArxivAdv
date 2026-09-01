package com.camel_hub.advertisement.email.tracking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MailClickController {
	private final MailTrackingService service;

	public MailClickController(MailTrackingService service) {
		this.service = service;
	}

	@RequestMapping({"/t/c/{token}", "/t/c/{*invalidToken}", "/t/c"})
	Mono<ResponseEntity<Void>> click(@PathVariable Map<String, String> variables, ServerWebExchange exchange) {
		HttpMethod method = exchange.getRequest().getMethod();
		if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
			return Mono.just(headers(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED))
					.allow(HttpMethod.GET, HttpMethod.HEAD).<Void>build());
		}
		return service.click(variables.get("token"), exchange.getRequest().getHeaders(), method == HttpMethod.GET)
				.map(resolved -> headers(ResponseEntity.status(HttpStatus.FOUND))
						.location(URI.create(resolved.targetUrl())).<Void>build())
				.defaultIfEmpty(headers(ResponseEntity.status(HttpStatus.NOT_FOUND)).<Void>build());
	}

	private ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder response) {
		return response.header("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate")
				.header("Pragma", "no-cache").header("Expires", "0")
				.header("Referrer-Policy", "no-referrer")
				.header("X-Content-Type-Options", "nosniff");
	}
}
