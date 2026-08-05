package com.camel_hub.advertisement.arxiv.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ArxivPropertiesTest {

	@Test
	void rejectsARequestIntervalBelowTheOfficialMinimum() {
		assertThatIllegalArgumentException().isThrownBy(() -> properties(Duration.ofMillis(2999)))
				.withMessageContaining("three seconds");
	}

	@Test
	void rejectsNonHttpsOrUnapprovedOfficialEndpoints() {
		assertThatIllegalArgumentException().isThrownBy(() -> new ArxivProperties(
				URI.create("http://export.arxiv.org/api/query"),
				URI.create("https://oaipmh.arxiv.org/oai"),
				Set.of("export.arxiv.org", "oaipmh.arxiv.org"),
				"classpath:arxiv/taxonomy-2026-08.json",
				Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(3),
				3, 100, 10_000, "CaMelArxivAdv/0.1 (admin@example.invalid)"))
				.withMessageContaining("HTTPS");
		assertThatIllegalArgumentException().isThrownBy(() -> new ArxivProperties(
				URI.create("https://untrusted.example/api/query"),
				URI.create("https://oaipmh.arxiv.org/oai"),
				Set.of("export.arxiv.org", "oaipmh.arxiv.org"),
				"classpath:arxiv/taxonomy-2026-08.json",
				Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(3),
				3, 100, 10_000, "CaMelArxivAdv/0.1 (admin@example.invalid)"))
				.withMessageContaining("approved host");
	}

	private ArxivProperties properties(Duration interval) {
		return new ArxivProperties(
				URI.create("https://export.arxiv.org/api/query"),
				URI.create("https://oaipmh.arxiv.org/oai"),
				Set.of("export.arxiv.org", "oaipmh.arxiv.org"),
				"classpath:arxiv/taxonomy-2026-08.json",
				Duration.ofHours(24), Duration.ofSeconds(30), interval,
				3, 100, 10_000, "CaMelArxivAdv/0.1 (admin@example.invalid)");
	}
}
