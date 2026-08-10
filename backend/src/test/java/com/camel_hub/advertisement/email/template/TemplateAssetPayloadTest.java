package com.camel_hub.advertisement.email.template;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateAssetPayloadTest {

	@Test
	void acceptsOnlySupportedImagesWhoseSignatureMatchesTheirMediaType() {
		byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
		byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1};
		byte[] gif = "GIF89a-content".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] webp = "RIFF1234WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

		assertThat(TemplateAssetPayload.validate("image/png", png, 32).extension()).isEqualTo("png");
		assertThat(TemplateAssetPayload.validate("image/jpeg", jpeg, 32).extension()).isEqualTo("jpg");
		assertThat(TemplateAssetPayload.validate("image/gif", gif, 32).extension()).isEqualTo("gif");
		assertThat(TemplateAssetPayload.validate("image/webp", webp, 32).extension()).isEqualTo("webp");
	}

	@Test
	void rejectsSpoofedEmptyAndOversizedUploads() {
		assertThatThrownBy(() -> TemplateAssetPayload.validate("image/png", "not-png".getBytes(), 32))
				.isInstanceOf(TemplateValidationException.class);
		assertThatThrownBy(() -> TemplateAssetPayload.validate("image/svg+xml", "<svg/>".getBytes(), 32))
				.isInstanceOf(TemplateValidationException.class);
		assertThatThrownBy(() -> TemplateAssetPayload.validate("image/png", new byte[0], 32))
				.isInstanceOf(TemplateValidationException.class);
		assertThatThrownBy(() -> TemplateAssetPayload.validate("image/png", new byte[33], 32))
				.isInstanceOf(TemplateValidationException.class);
	}
}
