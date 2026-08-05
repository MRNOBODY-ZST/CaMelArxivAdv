package com.camel_hub.advertisement.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuditService {

	private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
			"authorization", "cookie", "email", "jwt", "password", "secret", "token");

	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;

	public AuditService(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
	}

	public Mono<Void> record(AuditEvent event) {
		DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
				INSERT INTO audit_logs (
				    actor_user_id, action, resource_type, resource_id, ip_hash, user_agent_summary,
				    trace_id, before_summary, after_summary, result, error_type
				)
				VALUES (
				    :actorUserId, :action, :resourceType, :resourceId, :ipHash, :userAgentSummary,
				    :traceId, CAST(:beforeSummary AS jsonb), CAST(:afterSummary AS jsonb), :result, :errorType
				)
				""")
				.bind("action", event.action())
				.bind("resourceType", event.resourceType())
				.bind("traceId", event.traceId())
				.bind("beforeSummary", json(redact(event.beforeSummary())))
				.bind("afterSummary", json(redact(event.afterSummary())))
				.bind("result", event.result().name());
		statement = bindNullable(statement, "actorUserId", event.actorUserId(), UUID.class);
		statement = bindNullable(statement, "resourceId", event.resourceId(), String.class);
		statement = bindNullable(statement, "ipHash", event.ipHash(), byte[].class);
		statement = bindNullable(statement, "userAgentSummary", event.userAgentSummary(), String.class);
		statement = bindNullable(statement, "errorType", event.errorType(), String.class);
		return statement.fetch().rowsUpdated().then();
	}

	private DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec statement,
			String name,
			Object value,
			Class<?> type
	) {
		return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value == null ? Map.of() : value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Audit summary could not be serialized", exception);
		}
	}

	private Object redact(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sanitized = new LinkedHashMap<>();
			map.forEach((key, nested) -> {
				String name = String.valueOf(key);
				String normalized = name.toLowerCase(Locale.ROOT);
				sanitized.put(name, SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains)
						? "[REDACTED]"
						: redact(nested));
			});
			return sanitized;
		}
		if (value instanceof Iterable<?> iterable) {
			List<Object> sanitized = new ArrayList<>();
			iterable.forEach(item -> sanitized.add(redact(item)));
			return sanitized;
		}
		return value;
	}
}
