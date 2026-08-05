package com.camel_hub.advertisement.arxiv.savedsearch;

import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public class SavedSearchRepository {

	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public SavedSearchRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Flux<SavedSearchRecord> list(UUID ownerId, int offset, int limit) {
		return databaseClient.sql(selectSql() + """
				WHERE owner_user_id = :ownerId
				ORDER BY updated_at DESC, id
				OFFSET :offset LIMIT :limit
				""").bind("ownerId", ownerId).bind("offset", offset).bind("limit", limit)
				.map(this::map).all();
	}

	public Mono<Long> count(UUID ownerId) {
		return databaseClient.sql("SELECT count(*) AS total FROM saved_searches WHERE owner_user_id = :ownerId")
				.bind("ownerId", ownerId)
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<SavedSearchRecord> find(UUID ownerId, UUID id) {
		return databaseClient.sql(selectSql() + "WHERE owner_user_id = :ownerId AND id = :id")
				.bind("ownerId", ownerId).bind("id", id).map(this::map).one();
	}

	public Mono<SavedSearchRecord> create(
			UUID ownerId, String name, String canonicalCriteria, String criteriaHash
	) {
		return databaseClient.sql("""
				INSERT INTO saved_searches (owner_user_id, name, criteria, criteria_hash)
				VALUES (:ownerId, :name, CAST(:criteria AS jsonb), :criteriaHash)
				RETURNING id, owner_user_id, name, CAST(criteria AS text) AS criteria_text,
				          criteria_hash, created_at, updated_at
				""").bind("ownerId", ownerId).bind("name", name)
				.bind("criteria", canonicalCriteria).bind("criteriaHash", criteriaHash)
				.map(this::map).one();
	}

	public Mono<SavedSearchRecord> update(
			UUID ownerId, UUID id, String name, String canonicalCriteria, String criteriaHash
	) {
		return databaseClient.sql("""
				UPDATE saved_searches
				SET name = :name, criteria = CAST(:criteria AS jsonb),
				    criteria_hash = :criteriaHash, updated_at = now()
				WHERE owner_user_id = :ownerId AND id = :id
				RETURNING id, owner_user_id, name, CAST(criteria AS text) AS criteria_text,
				          criteria_hash, created_at, updated_at
				""").bind("ownerId", ownerId).bind("id", id).bind("name", name)
				.bind("criteria", canonicalCriteria).bind("criteriaHash", criteriaHash)
				.map(this::map).one();
	}

	public Mono<Long> delete(UUID ownerId, UUID id) {
		return databaseClient.sql("DELETE FROM saved_searches WHERE owner_user_id = :ownerId AND id = :id")
				.bind("ownerId", ownerId).bind("id", id).fetch().rowsUpdated();
	}

	private String selectSql() {
		return """
				SELECT id, owner_user_id, name, CAST(criteria AS text) AS criteria_text,
				       criteria_hash, created_at, updated_at
				FROM saved_searches
				""";
	}

	private SavedSearchRecord map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new SavedSearchRecord(
				row.get("id", UUID.class), row.get("owner_user_id", UUID.class),
				row.get("name", String.class), criteria(row.get("criteria_text", String.class)),
				row.get("criteria_hash", String.class), row.get("created_at", Instant.class),
				row.get("updated_at", Instant.class));
	}

	private ArxivSearchCriteria criteria(String json) {
		try {
			return objectMapper.readValue(json, ArxivSearchCriteria.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Stored search criteria could not be read", exception);
		}
	}

	public record SavedSearchRecord(
			UUID id, UUID ownerId, String name, ArxivSearchCriteria criteria,
			String criteriaHash, Instant createdAt, Instant updatedAt
	) {
	}
}
