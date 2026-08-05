package com.camel_hub.advertisement.arxiv.taxonomy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TaxonomyRepository {

	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public TaxonomyRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Mono<Boolean> hasActiveSnapshot() {
		return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM arxiv_category_snapshots WHERE active = true) AS present")
				.map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
				.one();
	}

	public Mono<java.util.Set<String>> activeCategoryIds() {
		return databaseClient.sql("SELECT category_id FROM arxiv_categories WHERE active = true")
				.map((row, metadata) -> row.get("category_id", String.class))
				.all()
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	public Mono<UUID> applySnapshot(TaxonomySnapshot snapshot) {
		return upsertSnapshot(snapshot)
				.flatMap(snapshotId -> upsertGroups(snapshot.categories())
						.then(upsertArchives(snapshot.categories()))
						.then(upsertCategories(snapshotId, snapshot.categories()))
						.then(deactivateMissing(snapshotId))
						.then(activateSnapshot(snapshotId))
						.thenReturn(snapshotId));
	}

	public Mono<TaxonomyData> loadActive() {
		Mono<SnapshotRow> snapshot = databaseClient.sql("""
				SELECT snapshot_version, source_type, source_url, source_updated_at,
				       coalesce(applied_at, fetched_at) AS synced_at, CAST(metadata AS text) AS metadata_text
				FROM arxiv_category_snapshots
				WHERE active = true
				""")
				.map((row, metadata) -> new SnapshotRow(
						row.get("snapshot_version", String.class),
						row.get("source_type", String.class),
						row.get("source_url", String.class),
						row.get("source_updated_at", Instant.class),
						row.get("synced_at", Instant.class),
						row.get("metadata_text", String.class)))
				.one();
		Mono<List<TaxonomyCategory>> categories = databaseClient.sql("""
				SELECT c.group_id, c.group_name, c.archive_id, c.archive_name,
				       c.category_id, c.category_name, c.description, c.is_alias, c.alias_target
				FROM arxiv_categories c
				JOIN arxiv_groups g ON g.id = c.group_ref_id
				LEFT JOIN arxiv_archives a ON a.id = c.archive_ref_id
				WHERE c.active = true
				ORDER BY lower(g.group_name), lower(coalesce(a.archive_name, c.archive_name)), c.category_id
				""")
				.map((row, metadata) -> new TaxonomyCategory(
						row.get("group_id", String.class), row.get("group_name", String.class),
						row.get("archive_id", String.class), row.get("archive_name", String.class),
						row.get("category_id", String.class), row.get("category_name", String.class),
						row.get("description", String.class),
						Boolean.TRUE.equals(row.get("is_alias", Boolean.class)),
						row.get("alias_target", String.class)))
				.all().collectList();
		return Mono.zip(snapshot, categories)
				.map(tuple -> {
					SnapshotRow row = tuple.getT1();
					return new TaxonomyData(
							row.snapshotVersion(), row.sourceType(), sourceUrls(row), row.sourceUpdatedAt(),
							row.syncedAt(), tuple.getT2());
				});
	}

	public Mono<SyncJob> createOrFindDailySyncJob(UUID actorUserId, String dayKey) {
		String idempotencyKey = "arxiv-taxonomy-sync:" + dayKey;
		return databaseClient.sql("""
				INSERT INTO jobs (type, status, created_by, parameters, idempotency_key, current_stage)
				VALUES ('ARXIV_SYNC_TAXONOMY', 'PENDING', :actorUserId, '{}'::jsonb, :idempotencyKey, 'WAITING_FOR_WORKER')
				ON CONFLICT (idempotency_key) DO NOTHING
				RETURNING id, status
				""")
				.bind("actorUserId", actorUserId)
				.bind("idempotencyKey", idempotencyKey)
				.map((row, metadata) -> new SyncJob(
						row.get("id", UUID.class), row.get("status", String.class), true))
				.one()
				.flatMap(job -> appendCreatedEvent(job.id()).thenReturn(job))
				.switchIfEmpty(findSyncJob(idempotencyKey));
	}

	public Mono<Long> countCategoryRows(String categoryId) {
		return databaseClient.sql("SELECT count(*) AS total FROM arxiv_categories WHERE category_id = :categoryId")
				.bind("categoryId", categoryId)
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<Boolean> isCategoryActive(String categoryId) {
		return databaseClient.sql("SELECT active FROM arxiv_categories WHERE category_id = :categoryId")
				.bind("categoryId", categoryId)
				.map((row, metadata) -> Boolean.TRUE.equals(row.get("active", Boolean.class))).one();
	}

	private Mono<UUID> upsertSnapshot(TaxonomySnapshot snapshot) {
		String metadata = json(Map.of(
				"sourceUrls", snapshot.sourceUrls(),
				"generatedAt", snapshot.generatedAt().toString(),
				"payloadSha256", snapshot.payloadSha256()));
		return databaseClient.sql("""
				INSERT INTO arxiv_category_snapshots (
				    snapshot_version, source_type, source_url, payload_sha256, item_count,
				    source_updated_at, fetched_at, metadata
				)
				VALUES (
				    :version, :sourceType, :sourceUrl, :payloadSha256, :itemCount,
				    :sourceUpdatedAt, :fetchedAt, CAST(:metadata AS jsonb)
				)
				ON CONFLICT (snapshot_version) DO UPDATE SET
				    source_type = EXCLUDED.source_type,
				    source_url = EXCLUDED.source_url,
				    payload_sha256 = EXCLUDED.payload_sha256,
				    item_count = EXCLUDED.item_count,
				    source_updated_at = EXCLUDED.source_updated_at,
				    fetched_at = EXCLUDED.fetched_at,
				    metadata = EXCLUDED.metadata
				RETURNING id
				""")
				.bind("version", snapshot.snapshotVersion())
				.bind("sourceType", snapshot.sourceType())
				.bind("sourceUrl", snapshot.sourceUrls().getFirst())
				.bind("payloadSha256", snapshot.payloadSha256())
				.bind("itemCount", snapshot.categories().size())
				.bind("sourceUpdatedAt", snapshot.sourceUpdatedAt())
				.bind("fetchedAt", snapshot.generatedAt())
				.bind("metadata", metadata)
				.map((row, ignored) -> row.get("id", UUID.class))
				.one();
	}

	private Mono<Void> upsertGroups(List<TaxonomyCategory> categories) {
		Map<String, TaxonomyCategory> groups = new LinkedHashMap<>();
		categories.forEach(category -> groups.putIfAbsent(category.groupId(), category));
		return Flux.fromIterable(groups.values())
				.concatMap(category -> databaseClient.sql("""
						INSERT INTO arxiv_groups (group_id, group_name, active, source_updated_at, synced_at)
						VALUES (:groupId, :groupName, true, now(), now())
						ON CONFLICT (group_id) DO UPDATE SET
						    group_name = EXCLUDED.group_name, active = true,
						    source_updated_at = EXCLUDED.source_updated_at, synced_at = EXCLUDED.synced_at
						""")
						.bind("groupId", category.groupId())
						.bind("groupName", category.groupName())
						.fetch().rowsUpdated())
				.then();
	}

	private Mono<Void> upsertArchives(List<TaxonomyCategory> categories) {
		Map<String, TaxonomyCategory> archives = new LinkedHashMap<>();
		categories.forEach(category -> archives.putIfAbsent(category.archiveId(), category));
		return Flux.fromIterable(archives.values())
				.concatMap(category -> databaseClient.sql("""
						INSERT INTO arxiv_archives (
						    group_ref_id, archive_id, archive_name, active, source_updated_at, synced_at
						)
						SELECT id, :archiveId, :archiveName, true, now(), now()
						FROM arxiv_groups WHERE group_id = :groupId
						ON CONFLICT (archive_id) DO UPDATE SET
						    group_ref_id = EXCLUDED.group_ref_id, archive_name = EXCLUDED.archive_name,
						    active = true, source_updated_at = EXCLUDED.source_updated_at,
						    synced_at = EXCLUDED.synced_at
						""")
						.bind("archiveId", category.archiveId())
						.bind("archiveName", category.archiveName())
						.bind("groupId", category.groupId())
						.fetch().rowsUpdated())
				.then();
	}

	private Mono<Void> upsertCategories(UUID snapshotId, List<TaxonomyCategory> categories) {
		return Flux.fromIterable(categories)
				.concatMap(category -> {
					DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
							INSERT INTO arxiv_categories (
							    group_ref_id, archive_ref_id, group_id, group_name, archive_id, archive_name,
							    category_id, category_name, description, is_alias, alias_target,
							    active, source_updated_at, synced_at, snapshot_id
							)
							SELECT g.id, a.id, :groupId, :groupName, :archiveId, :archiveName,
							       :categoryId, :categoryName, :description, :isAlias, :aliasTarget,
							       true, now(), now(), :snapshotId
							FROM arxiv_groups g
							JOIN arxiv_archives a ON a.archive_id = :archiveId
							WHERE g.group_id = :groupId
							ON CONFLICT (category_id) DO UPDATE SET
							    group_ref_id = EXCLUDED.group_ref_id, archive_ref_id = EXCLUDED.archive_ref_id,
							    group_id = EXCLUDED.group_id, group_name = EXCLUDED.group_name,
							    archive_id = EXCLUDED.archive_id, archive_name = EXCLUDED.archive_name,
							    category_name = EXCLUDED.category_name, description = EXCLUDED.description,
							    is_alias = EXCLUDED.is_alias, alias_target = EXCLUDED.alias_target,
							    active = true, source_updated_at = EXCLUDED.source_updated_at,
							    synced_at = EXCLUDED.synced_at, snapshot_id = EXCLUDED.snapshot_id
							""")
							.bind("groupId", category.groupId())
							.bind("groupName", category.groupName())
							.bind("archiveId", category.archiveId())
							.bind("archiveName", category.archiveName())
							.bind("categoryId", category.categoryId())
							.bind("categoryName", category.categoryName())
							.bind("description", category.description())
							.bind("isAlias", category.alias())
							.bind("snapshotId", snapshotId);
					statement = category.aliasTarget() == null
							? statement.bindNull("aliasTarget", String.class)
							: statement.bind("aliasTarget", category.aliasTarget());
					return statement.fetch().rowsUpdated();
				})
				.then();
	}

	private Mono<Void> deactivateMissing(UUID snapshotId) {
		return databaseClient.sql("UPDATE arxiv_categories SET active = false WHERE snapshot_id IS DISTINCT FROM :snapshotId")
				.bind("snapshotId", snapshotId)
				.fetch().rowsUpdated()
				.then(databaseClient.sql("""
						UPDATE arxiv_archives a SET active = EXISTS (
						    SELECT 1 FROM arxiv_categories c WHERE c.archive_ref_id = a.id AND c.active = true)
						""").fetch().rowsUpdated())
				.then(databaseClient.sql("""
						UPDATE arxiv_groups g SET active = EXISTS (
						    SELECT 1 FROM arxiv_categories c WHERE c.group_ref_id = g.id AND c.active = true)
						""").fetch().rowsUpdated())
				.then();
	}

	private Mono<Void> activateSnapshot(UUID snapshotId) {
		return databaseClient.sql("UPDATE arxiv_category_snapshots SET active = false WHERE id <> :snapshotId")
				.bind("snapshotId", snapshotId).fetch().rowsUpdated()
				.then(databaseClient.sql("""
						UPDATE arxiv_category_snapshots
						SET active = true, applied_at = now()
						WHERE id = :snapshotId
						""").bind("snapshotId", snapshotId).fetch().rowsUpdated())
				.then();
	}

	private Mono<Void> appendCreatedEvent(UUID jobId) {
		return databaseClient.sql("""
				INSERT INTO job_events (job_id, event_type, stage, message)
				VALUES (:jobId, 'JOB_CREATED', 'WAITING_FOR_WORKER', 'Taxonomy synchronization requested')
				""")
				.bind("jobId", jobId).fetch().rowsUpdated().then();
	}

	private Mono<SyncJob> findSyncJob(String idempotencyKey) {
		return databaseClient.sql("SELECT id, status FROM jobs WHERE idempotency_key = :idempotencyKey")
				.bind("idempotencyKey", idempotencyKey)
				.map((row, metadata) -> new SyncJob(
						row.get("id", UUID.class), row.get("status", String.class), false))
				.one();
	}

	private List<String> sourceUrls(SnapshotRow row) {
		try {
			Map<String, Object> metadata = objectMapper.readValue(
					row.metadataText(), new TypeReference<>() { });
			Object urls = metadata.get("sourceUrls");
			if (urls instanceof List<?> list) {
				return list.stream().map(String::valueOf).toList();
			}
			return List.of(row.sourceUrl());
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("stored taxonomy metadata is invalid", exception);
		}
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("taxonomy metadata could not be serialized", exception);
		}
	}

	private record SnapshotRow(
			String snapshotVersion, String sourceType, String sourceUrl,
			Instant sourceUpdatedAt, Instant syncedAt, String metadataText
	) {
	}

	public record TaxonomyData(
			String snapshotVersion, String sourceType, List<String> sourceUrls,
			Instant sourceUpdatedAt, Instant syncedAt, List<TaxonomyCategory> categories
	) {
	}

	public record SyncJob(UUID id, String status, boolean created) {
	}
}
