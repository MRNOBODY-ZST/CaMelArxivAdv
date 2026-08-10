package com.camel_hub.advertisement.email.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateAssetCopyServiceTest {

	private final UUID sourceTemplateId = UUID.randomUUID();
	private final UUID targetTemplateId = UUID.randomUUID();
	private final UUID sourceAssetId = UUID.randomUUID();
	private final UUID targetAssetId = UUID.randomUUID();
	private final UUID actorId = UUID.randomUUID();
	private final byte[] bytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
	private TemplateAssetRepository repository;
	private TemplateAssetObjectStore store;
	private TemplateAssetSigner signer;
	private TemplateAssetCopyService service;

	@BeforeEach
	void setUp() {
		repository = mock(TemplateAssetRepository.class);
		store = mock(TemplateAssetObjectStore.class);
		signer = new TemplateAssetSigner(
				"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "http://localhost:8080");
		service = new TemplateAssetCopyService(repository, store, signer);
	}

	@Test
	void duplicatesReferencedObjectsAndRebindsRelativeAndAbsoluteImageUrlsToTheCopy() {
		var source = sourceAsset();
		when(repository.list(sourceTemplateId)).thenReturn(Flux.just(source));
		when(store.get(source.objectKey())).thenReturn(Mono.just(bytes));
		when(store.put(any(), eq("image/png"), eq(bytes))).thenReturn(Mono.empty());
		when(repository.create(eq(targetTemplateId), any(), eq("figure.png"), eq("image/png"),
				eq((long) bytes.length), eq(source.sha256()), eq(actorId)))
				.thenAnswer(invocation -> Mono.just(new TemplateAssetRepository.AssetRecord(
						targetAssetId, targetTemplateId, invocation.getArgument(1), "figure.png", "image/png",
						bytes.length, source.sha256(), actorId, Instant.now())));
		String sourcePath = signer.path(sourceTemplateId, sourceAssetId);
		String html = "<p><img src=\"" + sourcePath + "\"><img src=\"http://localhost:8080"
				+ sourcePath + "\"></p>";

		TemplateAssetCopyService.CopyResult result = service.copyReferencedAssets(
				sourceTemplateId, targetTemplateId, html, actorId).block();

		assertThat(result.html()).contains(signer.path(targetTemplateId, targetAssetId));
		assertThat(result.html()).doesNotContain(sourceTemplateId.toString());
		assertThat(result.objectKeys()).hasSize(1).allMatch(key -> key.startsWith("templates/" + targetTemplateId + "/"));
		ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
		verify(store).put(objectKey.capture(), eq("image/png"), eq(bytes));
		assertThat(objectKey.getValue()).isEqualTo(result.objectKeys().getFirst());
	}

	@Test
	void removesNewObjectIfMetadataCreationFails() {
		var source = sourceAsset();
		when(repository.list(sourceTemplateId)).thenReturn(Flux.just(source));
		when(store.get(source.objectKey())).thenReturn(Mono.just(bytes));
		when(store.put(any(), eq("image/png"), eq(bytes))).thenReturn(Mono.empty());
		when(repository.create(eq(targetTemplateId), any(), any(), any(), anyLong(), any(), eq(actorId)))
				.thenReturn(Mono.error(new IllegalStateException("database unavailable")));
		when(store.remove(any())).thenReturn(Mono.empty());

		assertThatThrownBy(() -> service.copyReferencedAssets(sourceTemplateId, targetTemplateId,
				"<img src=\"" + signer.path(sourceTemplateId, sourceAssetId) + "\">", actorId).block())
				.isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");

		ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
		verify(store).remove(objectKey.capture());
		assertThat(objectKey.getValue()).startsWith("templates/" + targetTemplateId + "/");
	}

	private TemplateAssetRepository.AssetRecord sourceAsset() {
		return new TemplateAssetRepository.AssetRecord(
				sourceAssetId, sourceTemplateId, "templates/source/image.png", "figure.png", "image/png",
				bytes.length, sha256(bytes), actorId, Instant.now());
	}

	private byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
