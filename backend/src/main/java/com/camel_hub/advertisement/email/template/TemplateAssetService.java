package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class TemplateAssetService {

	private final TemplateRepository templates;
	private final TemplateAssetRepository repository;
	private final TemplateAssetObjectStore store;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TemplateAssetSigner signer;
	private final int maxBytes;

	public TemplateAssetService(
			TemplateRepository templates, TemplateAssetRepository repository, TemplateAssetObjectStore store,
			AuditService auditService, SensitiveValueHasher hasher, TemplateAssetSigner signer, int maxBytes
	) {
		this.templates = templates;
		this.repository = repository;
		this.store = store;
		this.auditService = auditService;
		this.hasher = hasher;
		this.signer = signer;
		this.maxBytes = maxBytes;
	}

	public int maxBytes() {
		return maxBytes;
	}

	public Mono<java.util.List<AssetView>> list(UUID templateId) {
		return requireTemplate(templateId).thenMany(repository.list(templateId)).map(this::view).collectList();
	}

	public Mono<AssetView> upload(
			UUID actorId, UUID templateId, String filename, String contentType, byte[] bytes,
			AuthenticationRequestContext context
	) {
		TemplateAssetPayload payload = TemplateAssetPayload.validate(contentType, bytes, maxBytes);
		String safeFilename = filename(filename);
		String objectKey = "templates/" + templateId + "/" + UUID.randomUUID() + "." + payload.extension();
		byte[] digest = sha256(bytes);
		return requireTemplate(templateId)
				.then(store.put(objectKey, payload.contentType(), bytes))
				.then(repository.create(templateId, objectKey, safeFilename, payload.contentType(),
						bytes.length, digest, actorId))
				.onErrorResume(error -> store.remove(objectKey).onErrorComplete().then(Mono.error(error)))
				.flatMap(asset -> audit("EMAIL_TEMPLATE_ASSET_UPLOADED", asset, actorId, context).thenReturn(asset))
				.map(this::view);
	}

	public Mono<AssetContent> content(UUID templateId, UUID assetId) {
		return requireTemplate(templateId)
				.then(Mono.defer(() -> repository.find(templateId, assetId)
						.switchIfEmpty(Mono.error(new TemplateNotFoundException()))))
				.flatMap(asset -> store.get(asset.objectKey())
						.map(bytes -> new AssetContent(asset.contentType(), asset.originalFilename(), bytes)));
	}

	public Mono<AssetContent> signedContent(UUID templateId, UUID assetId, String signature) {
		if (!signer.verify(templateId, assetId, signature)) return Mono.error(new TemplateNotFoundException());
		return content(templateId, assetId);
	}

	public Mono<Void> delete(
			UUID actorId, UUID templateId, UUID assetId, AuthenticationRequestContext context
	) {
		return repository.find(templateId, assetId).switchIfEmpty(Mono.error(new TemplateNotFoundException()))
				.flatMap(asset -> repository.countVersionReferences(signer.resourcePath(templateId, assetId))
						.flatMap(references -> {
							if (references > 0) return Mono.error(new TemplateConflictException(
									"Template image is referenced by an immutable version"));
							return store.remove(asset.objectKey()).then(repository.delete(templateId, assetId));
						})
						.flatMap(rows -> rows == 1
								? audit("EMAIL_TEMPLATE_ASSET_DELETED", asset, actorId, context)
								: Mono.error(new TemplateNotFoundException())));
	}

	private Mono<TemplateRepository.TemplateRecord> requireTemplate(UUID templateId) {
		return templates.find(templateId).switchIfEmpty(Mono.error(new TemplateNotFoundException()));
	}

	private AssetView view(TemplateAssetRepository.AssetRecord asset) {
		return new AssetView(asset.id(), asset.templateId(), asset.originalFilename(), asset.contentType(),
				asset.sizeBytes(), signer.path(asset.templateId(), asset.id()),
				asset.createdAt());
	}

	private String filename(String value) {
		String normalized = value == null ? "image" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		normalized = normalized.replace('\\', '_').replace('/', '_');
		if (normalized.isEmpty()) normalized = "image";
		if (normalized.length() > 255 || normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new TemplateValidationException("Template image filename is invalid");
		}
		return normalized;
	}

	private byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private Mono<Void> audit(
			String action, TemplateAssetRepository.AssetRecord asset, UUID actorId,
			AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(actorId, action, "EMAIL_TEMPLATE_ASSET", asset.id().toString(),
				hasher.hash(context.ipAddress()), context.userAgentSummary(), context.traceId(), Map.of(),
				Map.of("templateId", asset.templateId(), "contentType", asset.contentType(),
						"sizeBytes", asset.sizeBytes()), AuditResult.SUCCESS, null));
	}

	public record AssetView(
			UUID id, UUID templateId, String originalFilename, String contentType, long sizeBytes,
			String objectUrl, Instant createdAt
	) { }

	public record AssetContent(String contentType, String filename, byte[] bytes) { }
}
