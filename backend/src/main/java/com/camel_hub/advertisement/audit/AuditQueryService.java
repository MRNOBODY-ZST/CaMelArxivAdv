package com.camel_hub.advertisement.audit;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.service.AdministrationValidationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AuditQueryService {

	private static final int MAXIMUM_PAGE_SIZE = 100;
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};
	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public AuditQueryService(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Mono<PageResponse<AuditLogView>> query(
			int page,
			int pageSize,
			Instant from,
			Instant to,
			UUID actorId,
			String action,
			String resource,
			String result
	) {
		validate(page, pageSize, from, to);
		String normalizedAction = blankToEmpty(action);
		String normalizedResource = blankToEmpty(resource);
		String normalizedResult = blankToEmpty(result).toUpperCase(java.util.Locale.ROOT);
		int offset = (page - 1) * pageSize;
		String filters = """
				WHERE (CAST(:fromTime AS timestamptz) IS NULL OR logs.occurred_at >= :fromTime)
				  AND (CAST(:toTime AS timestamptz) IS NULL OR logs.occurred_at <= :toTime)
				  AND (CAST(:actorId AS uuid) IS NULL OR logs.actor_user_id = :actorId)
				  AND (:action = '' OR logs.action = :action)
				  AND (:resource = '' OR logs.resource_type ILIKE '%' || :resource || '%'
				       OR logs.resource_id ILIKE '%' || :resource || '%')
				  AND (:result = '' OR logs.result = :result)
				""";
		DatabaseClient.GenericExecuteSpec itemStatement = databaseClient.sql("""
				SELECT logs.id, logs.actor_user_id, users.username AS actor_username, logs.action,
				       logs.resource_type, logs.resource_id, logs.occurred_at, logs.trace_id,
				       logs.before_summary::text AS before_summary,
				       logs.after_summary::text AS after_summary,
				       logs.result, logs.error_type
				FROM audit_logs logs
				LEFT JOIN users ON users.id = logs.actor_user_id
				""" + filters + """
				ORDER BY logs.occurred_at DESC, logs.id
				LIMIT :pageSize OFFSET :offset
				""");
		itemStatement = bindFilters(
				itemStatement, from, to, actorId, normalizedAction, normalizedResource, normalizedResult)
				.bind("pageSize", pageSize)
				.bind("offset", offset);
		Mono<List<AuditLogView>> items = itemStatement
				.map((row, metadata) -> map(row))
				.all().collectList();

		DatabaseClient.GenericExecuteSpec countStatement = databaseClient.sql("""
				SELECT count(*) AS total FROM audit_logs logs
				""" + filters);
		Mono<Long> total = bindFilters(
				countStatement, from, to, actorId, normalizedAction, normalizedResource, normalizedResult)
				.map((row, metadata) -> row.get("total", Long.class))
				.one();
		return Mono.zip(items, total)
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	private DatabaseClient.GenericExecuteSpec bindFilters(
			DatabaseClient.GenericExecuteSpec statement,
			Instant from,
			Instant to,
			UUID actorId,
			String action,
			String resource,
			String result
	) {
		statement = from == null ? statement.bindNull("fromTime", Instant.class) : statement.bind("fromTime", from);
		statement = to == null ? statement.bindNull("toTime", Instant.class) : statement.bind("toTime", to);
		statement = actorId == null ? statement.bindNull("actorId", UUID.class) : statement.bind("actorId", actorId);
		return statement.bind("action", action).bind("resource", resource).bind("result", result);
	}

	private AuditLogView map(Row row) {
		return new AuditLogView(
				row.get("id", UUID.class), row.get("actor_user_id", UUID.class),
				row.get("actor_username", String.class), row.get("action", String.class),
				row.get("resource_type", String.class), row.get("resource_id", String.class),
				toInstant(row.get("occurred_at", OffsetDateTime.class)), row.get("trace_id", String.class),
				parse(row.get("before_summary", String.class)), parse(row.get("after_summary", String.class)),
				AuditResult.valueOf(row.get("result", String.class)), row.get("error_type", String.class));
	}

	private Map<String, Object> parse(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
			throw new IllegalStateException("Stored audit summary is invalid", exception);
		}
	}

	private static void validate(int page, int pageSize, Instant from, Instant to) {
		if (page < 1 || pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE) {
			throw new AdministrationValidationException(
					"Page must be positive and pageSize must be between 1 and 100");
		}
		if (from != null && to != null && from.isAfter(to)) {
			throw new AdministrationValidationException("Audit start time must not be after end time");
		}
	}

	private static String blankToEmpty(String value) {
		return value == null ? "" : value.strip();
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	public record AuditLogView(
			UUID id,
			UUID actorUserId,
			String actorUsername,
			String action,
			String resourceType,
			String resourceId,
			Instant occurredAt,
			String traceId,
			Map<String, Object> beforeSummary,
			Map<String, Object> afterSummary,
			AuditResult result,
			String errorType
	) {
	}
}
