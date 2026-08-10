package com.camel_hub.advertisement.email.template;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateAssetSignerTest {

	private final TemplateAssetSigner signer = new TemplateAssetSigner(
			"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "http://localhost:8080");

	@Test
	void signsTamperEvidentPathsAndMakesThemAbsoluteForMime() {
		UUID templateId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		String path = signer.path(templateId, assetId);

		assertThat(path).startsWith("/api/v1/template-assets/" + templateId + "/" + assetId)
				.contains("?signature=");
		assertThat(signer.verify(templateId, assetId, signer.signature(templateId, assetId))).isTrue();
		assertThat(signer.verify(templateId, UUID.randomUUID(), signer.signature(templateId, assetId))).isFalse();
		assertThat(signer.absolutizeHtml("<p><img src=\"" + path + "\"></p>"))
				.contains("src=\"http://localhost:8080/api/v1/template-assets/");
	}

	@Test
	void recognizesOnlyValidRelativeOrPublicAbsoluteAssetCapabilities() {
		UUID templateId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		String path = signer.path(templateId, assetId);

		assertThat(signer.matchesAssetUrl(path, templateId, assetId)).isTrue();
		assertThat(signer.matchesAssetUrl("http://localhost:8080" + path, templateId, assetId)).isTrue();
		assertThat(signer.matchesAssetUrl(path + "x", templateId, assetId)).isFalse();
		assertThat(signer.matchesAssetUrl(path, templateId, UUID.randomUUID())).isFalse();
		assertThat(signer.matchesAssetUrl("https://example.org" + path, templateId, assetId)).isFalse();
	}
}
