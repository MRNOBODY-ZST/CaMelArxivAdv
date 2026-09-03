package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.campaign.tracking.CampaignCallbackNamespace;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MailOpenController {
	private static final byte[] PIXEL = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
	private final MailTrackingService service;
	private final List<CampaignCallbackNamespace> campaignNamespaces;

	public MailOpenController(MailTrackingService service) {
		this(service, new CampaignCallbackNamespace[0]);
	}

	public MailOpenController(MailTrackingService service, CampaignCallbackNamespace... campaignNamespaces) {
		this.service = service;
		this.campaignNamespaces = List.of(campaignNamespaces);
	}

	@Autowired
	public MailOpenController(
			MailTrackingService service, ObjectProvider<CampaignCallbackNamespace> campaignNamespaces
	) {
		this.service = service;
		this.campaignNamespaces = campaignNamespaces.orderedStream().toList();
	}

	@RequestMapping({"/t/o/{token}", "/t/o/{*invalidToken}", "/t/o"})
	Mono<ResponseEntity<byte[]>> pixel(@PathVariable Map<String, String> variables, ServerWebExchange exchange) {
		HttpMethod method = exchange.getRequest().getMethod();
		if (method == HttpMethod.HEAD) return Mono.just(gif());
		if (method != HttpMethod.GET) {
			return Mono.just(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.GET, HttpMethod.HEAD)
					.header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build());
		}
		return Mono.defer(() -> service.observe(variables.get("token"), exchange.getRequest().getHeaders())
				.then(Flux.fromIterable(campaignNamespaces)
						.concatMap(namespace -> namespace.observeOpen(
								variables.get("token"), exchange.getRequest().getHeaders(),
								RequestContextSupport.context(exchange)).onErrorReturn(false))
						.filter(Boolean.TRUE::equals).next().then()))
				.onErrorResume(ignored -> Mono.empty()).thenReturn(gif());
	}

	private ResponseEntity<byte[]> gif() {
		return ResponseEntity.ok().contentType(MediaType.IMAGE_GIF).contentLength(PIXEL.length)
				.header("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate")
				.header("Pragma", "no-cache").header("Expires", "0")
				.header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff").body(PIXEL);
	}
}
