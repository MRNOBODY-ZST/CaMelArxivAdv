package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.campaign.tracking.CampaignCallbackNamespace;
import com.camel_hub.advertisement.campaign.tracking.CampaignRedirectTargetPolicy;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MailClickController {
	private final MailTrackingService service;
	private final List<CampaignCallbackNamespace> campaignNamespaces;

	public MailClickController(MailTrackingService service) {
		this(service, new CampaignCallbackNamespace[0]);
	}

	public MailClickController(MailTrackingService service, CampaignCallbackNamespace... campaignNamespaces) {
		this.service = service;
		this.campaignNamespaces = List.of(campaignNamespaces);
	}

	@Autowired
	public MailClickController(
			MailTrackingService service, ObjectProvider<CampaignCallbackNamespace> campaignNamespaces
	) {
		this.service = service;
		this.campaignNamespaces = campaignNamespaces.orderedStream().toList();
	}

	@RequestMapping({"/t/c/{token}", "/t/c/{*invalidToken}", "/t/c"})
	Mono<ResponseEntity<Void>> click(@PathVariable Map<String, String> variables, ServerWebExchange exchange) {
		HttpMethod method = exchange.getRequest().getMethod();
		if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
			return Mono.just(headers(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED))
					.allow(HttpMethod.GET, HttpMethod.HEAD).<Void>build());
		}
		Mono<String> testMail = service.click(
				variables.get("token"), exchange.getRequest().getHeaders(), method == HttpMethod.GET)
				.map(MailTrackingModels.ResolvedClick::targetUrl);
		Mono<String> campaign = Flux.fromIterable(campaignNamespaces)
				.concatMap(namespace -> namespace.click(
						variables.get("token"), exchange.getRequest().getHeaders(),
						RequestContextSupport.context(exchange), method == HttpMethod.GET)
						.map(CampaignCallbackNamespace.ResolvedClick::targetUrl)
						.onErrorResume(ignored -> Mono.empty()))
				.next();
		return testMail.switchIfEmpty(campaign)
				.flatMap(target -> Mono.fromCallable(() -> CampaignRedirectTargetPolicy.requireSafe(
						target, service.status().callbackBaseUrl())).onErrorResume(ignored -> Mono.empty()))
				.map(target -> headers(ResponseEntity.status(HttpStatus.FOUND))
						.location(URI.create(target)).<Void>build())
				.defaultIfEmpty(headers(ResponseEntity.status(HttpStatus.NOT_FOUND)).<Void>build());
	}

	private ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder response) {
		return response.header("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate")
				.header("Pragma", "no-cache").header("Expires", "0")
				.header("Referrer-Policy", "no-referrer")
				.header("X-Content-Type-Options", "nosniff");
	}
}
