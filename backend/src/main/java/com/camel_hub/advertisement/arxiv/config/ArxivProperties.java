package com.camel_hub.advertisement.arxiv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@ConfigurationProperties("app.arxiv")
public record ArxivProperties(
		URI legacyBaseUrl,
		URI oaiBaseUrl,
		Set<String> officialHosts,
		String taxonomySnapshot,
		Duration previewCacheTtl,
		Duration requestTimeout,
		Duration minRequestInterval,
		int maxRetries,
		int previewMaxPageSize,
		int importMaxPapers,
		String userAgent
) {

	private static final Duration OFFICIAL_MINIMUM_INTERVAL = Duration.ofSeconds(3);

	public ArxivProperties {
		officialHosts = Set.copyOf(Objects.requireNonNull(officialHosts, "official hosts are required")
				.stream()
				.map(host -> host.toLowerCase(Locale.ROOT))
				.toList());
		if (officialHosts.isEmpty()) {
			throw new IllegalArgumentException("at least one official arXiv host is required");
		}
		validateEndpoint(legacyBaseUrl, officialHosts, "Legacy API");
		validateEndpoint(oaiBaseUrl, officialHosts, "OAI-PMH");
		if (taxonomySnapshot == null || !taxonomySnapshot.startsWith("classpath:")) {
			throw new IllegalArgumentException("taxonomy snapshot must be a classpath resource");
		}
		requirePositive(previewCacheTtl, "preview cache TTL");
		requirePositive(requestTimeout, "request timeout");
		if (minRequestInterval == null || minRequestInterval.compareTo(OFFICIAL_MINIMUM_INTERVAL) < 0) {
			throw new IllegalArgumentException("arXiv requests must be spaced by at least three seconds");
		}
		if (maxRetries < 0 || maxRetries > 10) {
			throw new IllegalArgumentException("maximum retries must be between zero and ten");
		}
		if (previewMaxPageSize < 1 || previewMaxPageSize > 200) {
			throw new IllegalArgumentException("preview maximum page size must be between one and 200");
		}
		if (importMaxPapers < 1 || importMaxPapers > 1_000_000) {
			throw new IllegalArgumentException("import maximum papers must be between one and 1000000");
		}
		if (userAgent == null || userAgent.isBlank() || userAgent.length() > 300) {
			throw new IllegalArgumentException("arXiv user agent must be present and at most 300 characters");
		}
	}

	private static void validateEndpoint(URI endpoint, Set<String> officialHosts, String name) {
		Objects.requireNonNull(endpoint, name + " endpoint is required");
		if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
			throw new IllegalArgumentException(name + " endpoint must use HTTPS");
		}
		String host = endpoint.getHost();
		if (host == null || !officialHosts.contains(host.toLowerCase(Locale.ROOT))) {
			throw new IllegalArgumentException(name + " endpoint must use an approved host");
		}
		if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
			throw new IllegalArgumentException(name + " endpoint must not contain credentials or a fragment");
		}
	}

	private static void requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
