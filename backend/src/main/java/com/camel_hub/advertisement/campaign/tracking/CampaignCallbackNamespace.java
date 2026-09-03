package com.camel_hub.advertisement.campaign.tracking;

import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/** Extension point for production and safety callback namespaces. */
public interface CampaignCallbackNamespace {

	Mono<Boolean> observeOpen(String token, HttpHeaders headers, AuthenticationRequestContext request);

	Mono<ResolvedClick> click(
			String token, HttpHeaders headers, AuthenticationRequestContext request, boolean observe);

	Mono<Boolean> unsubscribe(String token, AuthenticationRequestContext request);

	record ResolvedClick(String targetUrl) {
		public ResolvedClick {
			targetUrl = CampaignRedirectTargetPolicy.requireSafe(targetUrl);
		}
	}
}
