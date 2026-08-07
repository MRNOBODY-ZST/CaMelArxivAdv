package com.camel_hub.advertisement.email.template;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.template.assets")
public record TemplateAssetProperties(
		@NotBlank String endpoint,
		@NotBlank String accessKey,
		@NotBlank String secretKey,
		@NotBlank String bucket,
		@NotBlank String signingKeyBase64,
		@NotBlank String publicBaseUrl,
		@Min(1) @Max(5_242_880) int maxBytes
) { }
