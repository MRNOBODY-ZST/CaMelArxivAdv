package com.camel_hub.advertisement.campaign.safety;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@FunctionalInterface
public interface CampaignSafetyOutboundPreparer {
	Mono<PreparedSafetyOutbound> prepare(CampaignDeliveryRepository.SafetyClaim claim);

	default PreparedSafetyOutbound validateForSend(
			CampaignDeliveryRepository.SafetyClaim claim, PreparedSafetyOutbound prepared
	) {
		return prepared;
	}

	record PreparedSafetyOutbound(
			String recipient, String subject, String html, String text, Map<String, String> headers
	) {
		public PreparedSafetyOutbound {
			headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
		}

		@Override public Map<String, String> headers() {
			return Map.copyOf(headers);
		}
	}
}
