package com.camel_hub.advertisement.email.tracking;

import org.springframework.http.HttpHeaders;

import java.util.Locale;

import static com.camel_hub.advertisement.email.tracking.MailTrackingModels.Classification;

public final class MailOpenClassifier {
	public Observation classify(HttpHeaders headers) {
		String userAgent = bounded(headers.getFirst(HttpHeaders.USER_AGENT), 512);
		Classification classification = Classification.UNCLASSIFIED;
		String reason = "no_known_automation_signal";
		for (String header : new String[] {"Sec-Purpose", "Purpose", "X-Purpose", "X-Moz"}) {
			String value = bounded(headers.getFirst(header), 128);
			if (value.contains("prefetch") || value.contains("preview")) {
				classification = Classification.PREFETCH;
				reason = "prefetch_header";
				break;
			}
		}
		if (classification == Classification.UNCLASSIFIED) {
			if (userAgent.contains("googleimageproxy") || userAgent.contains("yahoomailproxy")
					|| userAgent.contains("yahoo mail proxy") || userAgent.contains("outlookimageproxy")) {
				classification = Classification.IMAGE_PROXY;
				reason = "image_proxy_user_agent";
			}
			else if (userAgent.contains("bot") || userAgent.contains("crawler") || userAgent.contains("spider")
					|| userAgent.contains("scanner") || userAgent.contains("proofpoint")
					|| userAgent.contains("barracuda") || userAgent.contains("mimecast")) {
				classification = Classification.BOT;
				reason = "bot_user_agent";
			}
		}
		return new Observation(classification, reason, MailTrackingSigner.digest(classification.name() + "\n" + userAgent));
	}

	private String bounded(String value, int limit) {
		return value == null ? "" : value.substring(0, Math.min(value.length(), limit)).toLowerCase(Locale.ROOT)
				.replaceAll("[\\p{Cntrl}]", " ").strip();
	}

	public record Observation(Classification classification, String reason, byte[] fingerprintHash) { }
}
