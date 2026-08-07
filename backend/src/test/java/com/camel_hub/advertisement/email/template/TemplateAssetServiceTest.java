package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateAssetServiceTest {

	private final UUID actorId = UUID.randomUUID();
	private final UUID templateId = UUID.randomUUID();
	private TemplateRepository templates;
	private TemplateAssetRepository repository;
	private TemplateAssetObjectStore store;
	private TemplateAssetService service;
	private TemplateAssetSigner signer;

	@BeforeEach
	void setUp() {
		templates = mock(TemplateRepository.class);
		repository = mock(TemplateAssetRepository.class);
		store = mock(TemplateAssetObjectStore.class);
		signer = new TemplateAssetSigner(
				"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "http://localhost:8080");
		AuditService audit = mock(AuditService.class);
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(templates.find(templateId)).thenReturn(Mono.just(mock(TemplateRepository.TemplateRecord.class)));
		when(audit.record(any())).thenReturn(Mono.empty());
		when(hasher.hash(any())).thenReturn(new byte[] {1});
		service = new TemplateAssetService(templates, repository, store, audit, hasher, signer, 5_242_880);
	}

	@Test
	void storesRandomizedPrivateObjectAndReturnsAuthorizedContentUrl() {
		byte[] bytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
		when(store.put(any(), any(), any())).thenReturn(Mono.empty());
		when(repository.create(any(), any(), any(), any(), anyLong(), any(), any()))
				.thenAnswer(invocation -> Mono.just(new TemplateAssetRepository.AssetRecord(
						UUID.randomUUID(), templateId, invocation.getArgument(1), "figure.png", "image/png",
						(long) bytes.length, invocation.getArgument(5), actorId, Instant.now())));

		TemplateAssetService.AssetView view = service.upload(actorId, templateId, "figure.png", "image/png", bytes,
				new AuthenticationRequestContext("127.0.0.1", "JUnit", "asset-test")).block();

		assertThat(view.objectUrl()).startsWith("/api/v1/template-assets/" + templateId + "/")
				.contains("/content?signature=");
		ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
		verify(store).put(objectKey.capture(), org.mockito.ArgumentMatchers.eq("image/png"),
				org.mockito.ArgumentMatchers.same(bytes));
		assertThat(objectKey.getValue()).doesNotContain("figure.png").startsWith("templates/" + templateId + "/");
	}

	@Test
	void readsOnlyAnAssetBelongingToTheRequestedTemplate() {
		UUID assetId = UUID.randomUUID();
		byte[] bytes = "private-image".getBytes(StandardCharsets.UTF_8);
		var record = new TemplateAssetRepository.AssetRecord(
				assetId, templateId, "templates/key.png", "figure.png", "image/png",
				(long) bytes.length, new byte[32], actorId, Instant.now());
		when(repository.find(templateId, assetId)).thenReturn(Mono.just(record));
		when(store.get("templates/key.png")).thenReturn(Mono.just(bytes));

		TemplateAssetService.AssetContent content = service.content(templateId, assetId).block();

		assertThat(content.bytes()).isEqualTo(bytes);
		assertThat(content.contentType()).isEqualTo("image/png");
	}

	@Test
	void requiresALiveTemplateBeforeServingAuthorizedOrSignedContent() {
		UUID assetId = UUID.randomUUID();
		when(templates.find(templateId)).thenReturn(Mono.empty());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.content(templateId, assetId).block())
				.isInstanceOf(TemplateNotFoundException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.signedContent(
				templateId, assetId, signer.signature(templateId, assetId)).block())
				.isInstanceOf(TemplateNotFoundException.class);
		verify(repository, never()).find(templateId, assetId);
	}

	@Test
	void rejectsDeletionWhileAnyImmutableVersionReferencesTheAsset() {
		UUID assetId = UUID.randomUUID();
		var record = new TemplateAssetRepository.AssetRecord(
				assetId, templateId, "templates/key.png", "figure.png", "image/png",
				9, new byte[32], actorId, Instant.now());
		when(repository.find(templateId, assetId)).thenReturn(Mono.just(record));
		when(repository.countVersionReferences(signer.resourcePath(templateId, assetId))).thenReturn(Mono.just(1L));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(
				actorId, templateId, assetId,
				new AuthenticationRequestContext("127.0.0.1", "JUnit", "asset-delete")).block())
				.isInstanceOf(TemplateConflictException.class)
				.hasMessageContaining("version");
		verify(store, never()).remove("templates/key.png");
	}
}
