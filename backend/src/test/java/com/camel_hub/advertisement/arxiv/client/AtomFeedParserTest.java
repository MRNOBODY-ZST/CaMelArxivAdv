package com.camel_hub.advertisement.arxiv.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AtomFeedParserTest {

	private final AtomFeedParser parser = new AtomFeedParser();

	@Test
	void parsesNamespacedFeedMetadataAndPaperFields() throws IOException {
		byte[] fixture = getClass().getResourceAsStream("/arxiv/legacy-preview.xml").readAllBytes();

		AtomFeed feed = parser.parse(fixture);

		assertThat(feed.totalResults()).isEqualTo(42);
		assertThat(feed.startIndex()).isZero();
		assertThat(feed.itemsPerPage()).isEqualTo(2);
		assertThat(feed.papers()).hasSize(2);
		ArxivPaperPreview first = feed.papers().getFirst();
		assertThat(first.arxivId()).isEqualTo("2608.00001");
		assertThat(first.versionCount()).isEqualTo(3);
		assertThat(first.title()).isEqualTo("Reliable Agents for Scientific Discovery");
		assertThat(first.authors()).extracting(ArxivPaperPreview.Author::name)
				.containsExactly("Ada Lovelace", "Alan Turing");
		assertThat(first.authors().getFirst().affiliations())
				.containsExactly("Analytical Engine Institute");
		assertThat(first.primaryCategory()).isEqualTo("cs.AI");
		assertThat(first.categoryIds()).containsExactly("cs.AI", "cs.LG");
		assertThat(first.doi()).isEqualTo("10.1000/example.1");
		assertThat(first.journalReference()).contains("Reliable Systems");
		assertThat(first.licenseUrl()).isEqualTo("https://creativecommons.org/licenses/by/4.0/");
		assertThat(first.pdfUrl()).isEqualTo("https://arxiv.org/pdf/2608.00001v3");
	}

	@Test
	void rejectsDtdsAndExternalEntities() {
		String malicious = """
				<?xml version="1.0"?>
				<!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
				<feed xmlns="http://www.w3.org/2005/Atom"><title>&xxe;</title></feed>
				""";

		assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(
				malicious.getBytes(StandardCharsets.UTF_8)))
				.withMessageContaining("Atom");
	}
}
