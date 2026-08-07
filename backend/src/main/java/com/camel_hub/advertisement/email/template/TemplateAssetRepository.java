package com.camel_hub.advertisement.email.template;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class TemplateAssetRepository {

	private final DatabaseClient databaseClient;

	public TemplateAssetRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<AssetRecord> list(UUID templateId) {
		return databaseClient.sql(select() + " WHERE template_id = :templateId ORDER BY created_at DESC, id")
				.bind("templateId", templateId).map(this::map).all();
	}

	public Mono<AssetRecord> find(UUID templateId, UUID assetId) {
		return databaseClient.sql(select() + " WHERE template_id = :templateId AND id = :assetId")
				.bind("templateId", templateId).bind("assetId", assetId).map(this::map).one();
	}

	public Mono<AssetRecord> create(
			UUID templateId, String objectKey, String originalFilename, String contentType,
			long sizeBytes, byte[] sha256, UUID actorId
	) {
		return databaseClient.sql("""
				INSERT INTO template_assets (
				    template_id, object_key, original_filename, content_type, size_bytes, sha256, created_by
				) VALUES (:templateId, :objectKey, :filename, :contentType, :sizeBytes, :sha256, :actorId)
				RETURNING id, template_id, object_key, original_filename, content_type, size_bytes,
				          sha256, created_by, created_at
				""").bind("templateId", templateId).bind("objectKey", objectKey).bind("filename", originalFilename)
				.bind("contentType", contentType).bind("sizeBytes", sizeBytes).bind("sha256", sha256)
				.bind("actorId", actorId).map(this::map).one();
	}

	public Mono<Long> delete(UUID templateId, UUID assetId) {
		return databaseClient.sql("DELETE FROM template_assets WHERE template_id = :templateId AND id = :assetId")
				.bind("templateId", templateId).bind("assetId", assetId).fetch().rowsUpdated();
	}

	public Mono<Long> countVersionReferences(String assetPath) {
		return databaseClient.sql("""
				SELECT count(*) AS total
				FROM email_template_versions
				WHERE strpos(html_content, :assetPath) > 0
				""").bind("assetPath", assetPath)
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	private String select() {
		return """
				SELECT id, template_id, object_key, original_filename, content_type, size_bytes,
				       sha256, created_by, created_at
				FROM template_assets
				""";
	}

	private AssetRecord map(Row row, RowMetadata metadata) {
		Number size = row.get("size_bytes", Number.class);
		return new AssetRecord(row.get("id", UUID.class), row.get("template_id", UUID.class),
				row.get("object_key", String.class), row.get("original_filename", String.class),
				row.get("content_type", String.class), size == null ? 0 : size.longValue(),
				row.get("sha256", byte[].class), row.get("created_by", UUID.class), row.get("created_at", Instant.class));
	}

	public record AssetRecord(
			UUID id, UUID templateId, String objectKey, String originalFilename, String contentType,
			long sizeBytes, byte[] sha256, UUID createdBy, Instant createdAt
	) { }
}
