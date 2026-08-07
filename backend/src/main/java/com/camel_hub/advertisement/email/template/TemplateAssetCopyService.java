package com.camel_hub.advertisement.email.template;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TemplateAssetCopyService {

	private final TemplateAssetRepository repository;
	private final TemplateAssetObjectStore store;
	private final TemplateAssetSigner signer;

	public TemplateAssetCopyService(
			TemplateAssetRepository repository, TemplateAssetObjectStore store, TemplateAssetSigner signer
	) {
		this.repository = repository;
		this.store = store;
		this.signer = signer;
	}

	public Mono<CopyResult> copyReferencedAssets(
			UUID sourceTemplateId, UUID targetTemplateId, String html, UUID actorId
	) {
		List<String> objectKeys = new ArrayList<>();
		return repository.list(sourceTemplateId)
				.filter(asset -> referenced(html, asset))
				.concatMap(asset -> copyOne(targetTemplateId, actorId, asset, objectKeys))
				.collectList()
				.map(mappings -> new CopyResult(rewrite(html, mappings), List.copyOf(objectKeys)))
				.onErrorResume(error -> cleanup(objectKeys).then(Mono.error(error)));
	}

	public Mono<Void> cleanup(List<String> objectKeys) {
		if (objectKeys == null || objectKeys.isEmpty()) return Mono.empty();
		return Flux.fromIterable(objectKeys).concatMap(key -> store.remove(key).onErrorComplete()).then();
	}

	private Mono<AssetMapping> copyOne(
			UUID targetTemplateId, UUID actorId, TemplateAssetRepository.AssetRecord source,
			List<String> objectKeys
	) {
		String objectKey = "templates/" + targetTemplateId + "/" + UUID.randomUUID()
				+ "." + extension(source.contentType());
		return store.get(source.objectKey())
				.switchIfEmpty(Mono.error(new IllegalStateException("Stored template image is unavailable")))
				.flatMap(bytes -> {
					if (bytes.length != source.sizeBytes()
							|| !MessageDigest.isEqual(sha256(bytes), source.sha256())) {
						return Mono.error(new IllegalStateException("Template image integrity verification failed"));
					}
					return store.put(objectKey, source.contentType(), bytes)
							.doOnSuccess(ignored -> objectKeys.add(objectKey))
							.then(repository.create(targetTemplateId, objectKey, source.originalFilename(),
									source.contentType(), source.sizeBytes(), source.sha256(), actorId))
							.switchIfEmpty(Mono.error(
									new IllegalStateException("Copied template image metadata was not created")));
				}).map(copied -> new AssetMapping(source.templateId(), source.id(), targetTemplateId, copied.id()));
	}

	private boolean referenced(String html, TemplateAssetRepository.AssetRecord asset) {
		Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
		return document.select("img[src]").stream().map(image -> image.attr("src"))
				.anyMatch(source -> signer.matchesAssetUrl(source, asset.templateId(), asset.id()));
	}

	private String rewrite(String html, List<AssetMapping> mappings) {
		Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
		for (Element image : document.select("img[src]")) {
			for (AssetMapping mapping : mappings) {
				if (signer.matchesAssetUrl(image.attr("src"), mapping.sourceTemplateId(), mapping.sourceAssetId())) {
					image.attr("src", signer.path(mapping.targetTemplateId(), mapping.targetAssetId()));
					break;
				}
			}
		}
		document.outputSettings().prettyPrint(false);
		return document.body().html();
	}

	private String extension(String contentType) {
		return switch (contentType) {
			case "image/png" -> "png";
			case "image/jpeg" -> "jpg";
			case "image/gif" -> "gif";
			case "image/webp" -> "webp";
			default -> throw new IllegalStateException("Stored template image type is invalid");
		};
	}

	private byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private record AssetMapping(
			UUID sourceTemplateId, UUID sourceAssetId, UUID targetTemplateId, UUID targetAssetId
	) { }

	public record CopyResult(String html, List<String> objectKeys) { }
}
