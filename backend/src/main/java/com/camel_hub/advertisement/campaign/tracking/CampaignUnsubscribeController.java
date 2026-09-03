package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.common.api.RequestContextSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class CampaignUnsubscribeController {
	private static final Logger LOGGER = LoggerFactory.getLogger(CampaignUnsubscribeController.class);

	private static final byte[] CONFIRMATION = ("<!doctype html><html><head><meta charset=\"utf-8\">"
			+ "<title>Unsubscribe</title></head><body><main><h1>Confirm unsubscribe</h1>"
			+ "<p>Submit this form to stop future campaign messages.</p>"
			+ "<form method=\"post\" action=\"\"><button type=\"submit\">Unsubscribe</button></form>"
			+ "</main></body></html>").getBytes(StandardCharsets.UTF_8);
	private static final byte[] COMPLETE = ("<!doctype html><html><head><meta charset=\"utf-8\">"
			+ "<title>Unsubscribe</title></head><body><main><h1>Request received</h1>"
			+ "<p>Your preference has been recorded.</p></main></body></html>")
			.getBytes(StandardCharsets.UTF_8);
	private static final byte[] RETRY = ("<!doctype html><html><head><meta charset=\"utf-8\">"
			+ "<title>Unsubscribe</title></head><body><main><h1>Request not completed</h1>"
			+ "<p>Please retry this request.</p></main></body></html>")
			.getBytes(StandardCharsets.UTF_8);

	private final List<CampaignCallbackNamespace> namespaces;

	public CampaignUnsubscribeController(CampaignCallbackNamespace... namespaces) {
		this.namespaces = List.of(namespaces);
	}

	@Autowired
	public CampaignUnsubscribeController(ObjectProvider<CampaignCallbackNamespace> namespaces) {
		this.namespaces = namespaces.orderedStream().toList();
	}

	@RequestMapping({"/u/{token}", "/u/{*invalidToken}", "/u"})
	Mono<ResponseEntity<byte[]>> unsubscribe(
			@PathVariable Map<String, String> variables, ServerWebExchange exchange
	) {
		HttpMethod method = exchange.getRequest().getMethod();
		if (method == HttpMethod.HEAD) return Mono.just(response(CONFIRMATION, true));
		if (method == HttpMethod.GET) return Mono.just(response(CONFIRMATION, false));
		if (method != HttpMethod.POST) {
			return Mono.just(headers(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED))
					.allow(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST).build());
		}
		return Flux.fromIterable(namespaces)
				.concatMap(namespace -> namespace.unsubscribe(
						variables.get("token"), RequestContextSupport.context(exchange)))
				.filter(Boolean.TRUE::equals).next().thenReturn(response(COMPLETE, false))
				.onErrorResume(ignored -> {
					LOGGER.warn("Campaign unsubscribe persistence failed");
					return Mono.just(response(HttpStatus.SERVICE_UNAVAILABLE, RETRY));
				});
	}

	private ResponseEntity<byte[]> response(byte[] body, boolean head) {
		ResponseEntity.BodyBuilder response = headers(ResponseEntity.ok())
				.contentType(new MediaType("text", "html", StandardCharsets.UTF_8));
		return head ? response.<byte[]>build() : response.body(body);
	}

	private ResponseEntity<byte[]> response(HttpStatus status, byte[] body) {
		return headers(ResponseEntity.status(status))
				.contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
				.body(body);
	}

	private ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder response) {
		return response.header("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate")
				.header("Pragma", "no-cache").header("Expires", "0")
				.header("Referrer-Policy", "no-referrer")
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Security-Policy",
						"default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
	}
}
