package com.camel_hub.advertisement.campaign.delivery;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Task 4 supplies the real implementation which freezes tracking and unsubscribe
 * artifacts before this reactive boundary completes. Task 3 deliberately provides
 * no default implementation.
 */
@FunctionalInterface
public interface CampaignOutboundPreparer {

	Mono<PreparedOutbound> prepare(CampaignDeliveryRepository.ProductionClaim claim);

	record PreparedOutbound(String subject, String html, String text, Map<String, String> headers) {
		private static final Set<String> ALLOWED_HEADERS = Set.of(
				"List-Unsubscribe", "List-Unsubscribe-Post");

		public PreparedOutbound {
			if (subject == null || subject.isBlank() || subject.length() > 998
					|| html == null || html.isBlank() || text == null || text.isBlank()) {
				throw new IllegalArgumentException("Prepared campaign content is invalid");
			}
			if (subject.contains("{{unsubscribe_url}}") || html.contains("{{unsubscribe_url}}")
					|| text.contains("{{unsubscribe_url}}")) {
				throw new IllegalArgumentException("Prepared campaign content is incomplete");
			}
			Map<String, String> safe = new LinkedHashMap<>();
			if (headers != null) {
				headers.forEach((name, value) -> {
					String canonical = name == null ? null : ALLOWED_HEADERS.stream()
							.filter(allowed -> allowed.equalsIgnoreCase(name)).findFirst().orElse(null);
					if (canonical == null || value == null || value.isBlank()
							|| value.length() > 998 || containsControl(value)) {
						throw new IllegalArgumentException("Prepared campaign header is invalid");
					}
					if (safe.putIfAbsent(canonical, value) != null) {
						throw new IllegalArgumentException("Prepared campaign header is duplicated");
					}
				});
			}
			if (!safe.keySet().equals(ALLOWED_HEADERS)
					|| !"List-Unsubscribe=One-Click".equals(safe.get("List-Unsubscribe-Post"))
					|| !validListUnsubscribe(safe.get("List-Unsubscribe"))) {
				throw new IllegalArgumentException("Prepared campaign unsubscribe headers are invalid");
			}
			headers = Map.copyOf(safe);
		}

		@Override
		public Map<String, String> headers() {
			return Map.copyOf(headers);
		}

		private static boolean containsControl(String value) {
			return value.codePoints().anyMatch(Character::isISOControl);
		}

		private static boolean validListUnsubscribe(String value) {
			boolean hasHttps = false;
			for (String element : value.split(",")) {
				String candidate = element.strip();
				if (candidate.length() < 3 || candidate.charAt(0) != '<'
						|| candidate.charAt(candidate.length() - 1) != '>') return false;
				try {
					URI uri = URI.create(candidate.substring(1, candidate.length() - 1));
					if ("https".equalsIgnoreCase(uri.getScheme())) {
						if (uri.getHost() == null || uri.getHost().isBlank()
								|| uri.getUserInfo() != null || uri.getFragment() != null) return false;
						hasHttps = true;
					}
					else if ("mailto".equalsIgnoreCase(uri.getScheme())) {
						String address = uri.getRawSchemeSpecificPart();
						if (address == null || address.isBlank() || !address.contains("@")
								|| uri.getFragment() != null) return false;
					}
					else return false;
				}
				catch (IllegalArgumentException exception) {
					return false;
				}
			}
			return hasHttps;
		}
	}
}
