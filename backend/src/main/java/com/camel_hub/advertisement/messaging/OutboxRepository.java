package com.camel_hub.advertisement.messaging;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class OutboxRepository {

	private final DatabaseClient databaseClient;

	public OutboxRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<OutboxMessage> claimBatch(int limit) {
		if (limit < 1 || limit > 100) {
			return Flux.error(new IllegalArgumentException("Outbox batch size is invalid"));
		}
		return databaseClient.sql("""
				WITH candidates AS (
				    SELECT id FROM outbox_messages
				    WHERE published_at IS NULL AND available_at <= now()
				      AND topic_name IN (
				          'camel.arxiv.jobs.v1', 'camel.arxiv.results.v1',
				          'camel.mail.personalization.jobs.v1', 'camel.mail.personalization.results.v1',
				          'camel.mail.delivery.jobs.v1'
				      )
				    ORDER BY available_at, id
				    FOR UPDATE SKIP LOCKED
				    LIMIT :limit
				)
				UPDATE outbox_messages message
				SET attempt_count = message.attempt_count + 1,
				    available_at = now() + interval '30 seconds'
				FROM candidates
				WHERE message.id = candidates.id
				RETURNING message.id, message.topic_name, message.routing_key,
				          message.message_type, message.message_version,
				          CAST(message.payload AS text) AS payload_text, message.attempt_count
				""").bind("limit", limit)
				.map((row, metadata) -> new OutboxMessage(
						row.get("id", UUID.class), row.get("topic_name", String.class),
						row.get("routing_key", String.class), row.get("message_type", String.class),
						row.get("message_version", Integer.class), row.get("payload_text", String.class),
						row.get("attempt_count", Integer.class)))
				.all();
	}

	public Mono<Void> markPublished(UUID id) {
		return databaseClient.sql("""
				UPDATE outbox_messages
				SET published_at = now(), last_error = NULL
				WHERE id = :id AND published_at IS NULL
				""").bind("id", id).fetch().rowsUpdated().then();
	}

	public Mono<Void> markFailed(UUID id, String error) {
		String safeError = sanitize(error);
		return databaseClient.sql("""
				UPDATE outbox_messages
				SET last_error = :error,
				    available_at = now() + make_interval(secs => LEAST(300, (1 << LEAST(attempt_count, 8))))
				WHERE id = :id AND published_at IS NULL
				""").bind("error", safeError).bind("id", id).fetch().rowsUpdated().then();
	}

	private String sanitize(String error) {
		String value = error == null ? "Publisher failure" : error.replaceAll("[\\p{Cntrl}]", " ").strip();
		return value.substring(0, Math.min(value.length(), 500));
	}

	public record OutboxMessage(
			UUID id, String topic, String routingKey, String type,
			int version, String payload, int attemptCount
	) {
	}
}
