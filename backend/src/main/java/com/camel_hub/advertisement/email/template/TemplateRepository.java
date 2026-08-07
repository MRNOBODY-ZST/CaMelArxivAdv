package com.camel_hub.advertisement.email.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class TemplateRepository {

	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public TemplateRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Flux<TemplateRecord> list(int offset, int limit) {
		return databaseClient.sql(selectHead() + """
				WHERE t.deleted_at IS NULL
				ORDER BY t.updated_at DESC, t.id
				OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit).map(this::map).all();
	}

	public Mono<Long> count() {
		return databaseClient.sql("SELECT count(*) AS total FROM email_templates WHERE deleted_at IS NULL")
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<TemplateRecord> find(UUID id) {
		return databaseClient.sql(selectHead() + "WHERE t.id = :id AND t.deleted_at IS NULL")
				.bind("id", id).map(this::map).one();
	}

	public Flux<TemplateVersionRecord> versions(UUID templateId) {
		return databaseClient.sql(selectVersion() + """
				WHERE template_id = :templateId
				ORDER BY version_number DESC
				""").bind("templateId", templateId).map(this::mapVersion).all();
	}

	public Mono<TemplateVersionRecord> findVersion(UUID templateId, int versionNumber) {
		return databaseClient.sql(selectVersion() + """
				WHERE template_id = :templateId AND version_number = :versionNumber
				""").bind("templateId", templateId).bind("versionNumber", versionNumber)
				.map(this::mapVersion).one();
	}

	public Mono<TemplateMetadata> createTemplate(
			String name, String description, TemplateStatus status, UUID actorId
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO email_templates (name, description, status, created_by, updated_by)
				VALUES (:name, :description, :status, :actorId, :actorId)
				RETURNING id, current_version, lock_version
				""").bind("name", name).bind("status", status.name()).bind("actorId", actorId);
		statement = bindNullable(statement, "description", description, String.class);
		return statement.map((row, metadata) -> new TemplateMetadata(
				row.get("id", UUID.class), requiredLong(row, "current_version"), requiredLong(row, "lock_version"))).one();
	}

	public Mono<TemplateMetadata> advanceHead(
			UUID id, long expectedLockVersion, String name, String description,
			TemplateStatus status, UUID actorId
	) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				UPDATE email_templates
				SET name = :name, description = :description, status = :status,
				    current_version = current_version + 1, lock_version = lock_version + 1,
				    updated_by = :actorId, updated_at = now()
				WHERE id = :id AND deleted_at IS NULL AND lock_version = :expectedLockVersion
				RETURNING id, current_version, lock_version
				""").bind("name", name).bind("status", status.name()).bind("actorId", actorId)
				.bind("id", id).bind("expectedLockVersion", expectedLockVersion);
		statement = bindNullable(statement, "description", description, String.class);
		return statement.map((row, metadata) -> new TemplateMetadata(
				row.get("id", UUID.class), requiredLong(row, "current_version"), requiredLong(row, "lock_version"))).one();
	}

	public Mono<Void> insertVersion(
			UUID templateId, long versionNumber, TemplateModels.PreparedTemplate prepared, UUID actorId
	) {
		return databaseClient.sql("""
				INSERT INTO email_template_versions (
				    template_id, version_number, subject_template, from_name_template, reply_to,
				    html_content, text_content, auto_generate_text, content_size_bytes,
				    validation_result, created_by
				)
				VALUES (
				    :templateId, :versionNumber, :subject, :fromName, :replyTo,
				    :html, :text, :autoGenerateText, :size, CAST(:validation AS jsonb), :actorId
				)
				""").bind("templateId", templateId).bind("versionNumber", versionNumber)
				.bind("subject", prepared.subjectTemplate()).bind("fromName", prepared.fromNameTemplate())
				.bind("replyTo", prepared.replyTo()).bind("html", prepared.sanitizedHtml())
				.bind("text", prepared.textContent()).bind("autoGenerateText", prepared.autoGenerateText())
				.bind("size", prepared.contentSizeBytes())
				.bind("validation", json(prepared.validation())).bind("actorId", actorId)
				.fetch().rowsUpdated().then();
	}

	public Mono<Long> referencedCampaigns(UUID templateId) {
		return databaseClient.sql("SELECT count(*) AS total FROM campaigns WHERE template_id = :templateId")
				.bind("templateId", templateId).map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<Long> softDelete(UUID id, long expectedLockVersion, UUID actorId) {
		return databaseClient.sql("""
				UPDATE email_templates
				SET status = 'ARCHIVED', deleted_at = now(), updated_at = now(),
				    updated_by = :actorId, lock_version = lock_version + 1
				WHERE id = :id AND deleted_at IS NULL AND lock_version = :expectedLockVersion
				""").bind("actorId", actorId).bind("id", id).bind("expectedLockVersion", expectedLockVersion)
				.fetch().rowsUpdated();
	}

	private String selectHead() {
		return """
				SELECT t.id, t.name, t.description, t.status, t.current_version, t.lock_version,
				       t.created_by, t.updated_by, t.created_at, t.updated_at,
				       v.subject_template, v.from_name_template, v.reply_to, v.html_content,
				       v.text_content, v.auto_generate_text, v.content_size_bytes,
				       CAST(v.validation_result AS text) AS validation_text,
				       v.created_at AS version_created_at
				FROM email_templates t
				JOIN email_template_versions v
				  ON v.template_id = t.id AND v.version_number = t.current_version
				""";
	}

	private String selectVersion() {
		return """
				SELECT id, template_id, version_number, subject_template, from_name_template, reply_to,
				       html_content, text_content, auto_generate_text, content_size_bytes,
				       CAST(validation_result AS text) AS validation_text, created_by, created_at
				FROM email_template_versions
				""";
	}

	private TemplateRecord map(Row row, RowMetadata metadata) {
		return new TemplateRecord(
				row.get("id", UUID.class), row.get("name", String.class), row.get("description", String.class),
				TemplateStatus.valueOf(row.get("status", String.class)), requiredLong(row, "current_version"),
				requiredLong(row, "lock_version"), row.get("created_by", UUID.class), row.get("updated_by", UUID.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class),
				row.get("subject_template", String.class), row.get("from_name_template", String.class),
				row.get("reply_to", String.class), row.get("html_content", String.class),
				row.get("text_content", String.class), Boolean.TRUE.equals(row.get("auto_generate_text", Boolean.class)),
				Math.toIntExact(requiredLong(row, "content_size_bytes")),
				validation(row.get("validation_text", String.class)), row.get("version_created_at", Instant.class));
	}

	private TemplateVersionRecord mapVersion(Row row, RowMetadata metadata) {
		return new TemplateVersionRecord(
				row.get("id", UUID.class), row.get("template_id", UUID.class), requiredLong(row, "version_number"),
				row.get("subject_template", String.class), row.get("from_name_template", String.class),
				row.get("reply_to", String.class), row.get("html_content", String.class),
				row.get("text_content", String.class), Boolean.TRUE.equals(row.get("auto_generate_text", Boolean.class)),
				Math.toIntExact(requiredLong(row, "content_size_bytes")),
				validation(row.get("validation_text", String.class)), row.get("created_by", UUID.class),
				row.get("created_at", Instant.class));
	}

	private TemplateModels.ValidationResult validation(String json) {
		try {
			return objectMapper.readValue(json, TemplateModels.ValidationResult.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Stored template validation result could not be read", exception);
		}
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Template validation result could not be stored", exception);
		}
	}

	private long requiredLong(Row row, String name) {
		Number number = row.get(name, Number.class);
		if (number == null) throw new IllegalStateException("Missing template numeric field: " + name);
		return number.longValue();
	}

	private DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement, String name, Object value, Class<?> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	public enum TemplateStatus { DRAFT, ACTIVE, ARCHIVED }

	public record TemplateMetadata(UUID id, long currentVersion, long lockVersion) { }

	public record TemplateRecord(
			UUID id, String name, String description, TemplateStatus status, long currentVersion,
			long lockVersion, UUID createdBy, UUID updatedBy, Instant createdAt, Instant updatedAt,
			String subjectTemplate, String fromNameTemplate, String replyTo, String htmlContent,
			String textContent, boolean autoGenerateText, int contentSizeBytes,
			TemplateModels.ValidationResult validation,
			Instant versionCreatedAt
	) { }

	public record TemplateVersionRecord(
			UUID id, UUID templateId, long versionNumber, String subjectTemplate, String fromNameTemplate,
			String replyTo, String htmlContent, String textContent, boolean autoGenerateText, int contentSizeBytes,
			TemplateModels.ValidationResult validation, UUID createdBy, Instant createdAt
	) { }
}
