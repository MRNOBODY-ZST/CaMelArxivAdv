package com.camel_hub.advertisement.email.template;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/template-assets/{templateId}/{assetId}/content")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SignedTemplateAssetController {

	private final TemplateAssetService service;

	public SignedTemplateAssetController(TemplateAssetService service) {
		this.service = service;
	}

	@GetMapping
	Mono<ResponseEntity<byte[]>> content(
			@PathVariable UUID templateId, @PathVariable UUID assetId, @RequestParam String signature
	) {
		return service.signedContent(templateId, assetId, signature).map(content -> ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic().immutable())
				.header("X-Content-Type-Options", "nosniff")
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
						.filename(content.filename(), StandardCharsets.UTF_8).build().toString())
				.body(content.bytes()));
	}
}
