package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.common.api.RequestContextSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/templates/{templateId}/assets")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TemplateAssetController {

	private final TemplateAssetService service;

	public TemplateAssetController(TemplateAssetService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('template:read')")
	Mono<List<TemplateAssetService.AssetView>> list(@PathVariable UUID templateId) {
		return service.list(templateId);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<TemplateAssetService.AssetView> upload(
			@PathVariable UUID templateId, @RequestPart("file") FilePart file,
			Principal principal, ServerWebExchange exchange
	) {
		String contentType = file.headers().getContentType() == null ? null
				: file.headers().getContentType().toString();
		return DataBufferUtils.join(file.content(), service.maxBytes() + 1)
				.map(this::bytes)
				.flatMap(bytes -> service.upload(RequestContextSupport.actorId(principal), templateId,
						file.filename(), contentType, bytes, RequestContextSupport.context(exchange)))
				.onErrorMap(DataBufferLimitException.class,
						error -> new TemplateValidationException("Template image exceeds the configured size limit"));
	}

	@GetMapping("/{assetId}/content")
	@PreAuthorize("hasAuthority('template:read')")
	Mono<ResponseEntity<byte[]>> content(@PathVariable UUID templateId, @PathVariable UUID assetId) {
		return service.content(templateId, assetId).map(content -> ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().mustRevalidate())
				.header("X-Content-Type-Options", "nosniff")
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
						.filename(content.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
				.body(content.bytes()));
	}

	@DeleteMapping("/{assetId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<Void> delete(
			@PathVariable UUID templateId, @PathVariable UUID assetId,
			Principal principal, ServerWebExchange exchange
	) {
		return service.delete(RequestContextSupport.actorId(principal), templateId, assetId,
				RequestContextSupport.context(exchange));
	}

	private byte[] bytes(DataBuffer buffer) {
		try {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			return bytes;
		}
		finally {
			DataBufferUtils.release(buffer);
		}
	}
}
