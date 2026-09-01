package com.camel_hub.advertisement.email.tracking;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.camel_hub.advertisement.email.tracking.MailTrackingModels.*;

public final class MailTrackingRepository {
	private final DatabaseClient database;

	public MailTrackingRepository(DatabaseClient database) {
		this.database = database;
	}

	public Mono<Void> insert(
			UUID id, UUID actorId, UUID accountId, Source source, String recipientMasked, String subject,
			Instant createdAt, Instant expiresAt, byte[] tokenHash
	) {
		var query = database.sql("""
				INSERT INTO mail_send_records (
				    id, actor_user_id, smtp_account_id, source, recipient_masked, subject,
				    tracking_enabled, created_at, tracking_expires_at, token_hash
				) VALUES (:id, :actor, :account, :source, :recipient, :subject, :tracked, :created, :expires, :hash)
				""").bind("id", id).bind("actor", actorId).bind("account", accountId)
				.bind("source", source.name()).bind("recipient", recipientMasked).bind("subject", subject)
				.bind("tracked", tokenHash != null).bind("created", createdAt);
		query = expiresAt == null ? query.bindNull("expires", Instant.class) : query.bind("expires", expiresAt);
		query = tokenHash == null ? query.bindNull("hash", byte[].class) : query.bind("hash", tokenHash);
		return query.fetch().rowsUpdated().then();
	}

	public Mono<Void> complete(UUID id, Status status, String failureCategory, Instant completedAt) {
		var query = database.sql("""
				UPDATE mail_send_records SET status = :status, failure_category = :failure, completed_at = :completed
				WHERE id = :id AND status = 'SENDING'
				""").bind("id", id).bind("status", status.name()).bind("completed", completedAt);
		query = failureCategory == null ? query.bindNull("failure", String.class) : query.bind("failure", failureCategory);
		return query.fetch().rowsUpdated().flatMap(rows -> rows == 1 ? Mono.empty()
				: Mono.error(new IllegalStateException("Mail send outcome could not be recorded")));
	}

	public Mono<Long> reconcileStale(Instant cutoff, Instant completedAt) {
		return database.sql("""
				UPDATE mail_send_records
				SET status = 'UNKNOWN', failure_category = 'SEND_OUTCOME_MISSING', completed_at = :completed
				WHERE status = 'SENDING' AND created_at < :cutoff
				""").bind("cutoff", cutoff).bind("completed", completedAt).fetch().rowsUpdated();
	}

	public Mono<Void> observe(
			MailTrackingSigner.VerifiedToken token, MailOpenClassifier.Observation observation, Instant occurredAt
	) {
		return database.sql("""
				INSERT INTO mail_open_events (record_id, occurred_at, classification, reason, fingerprint_hash, minute_bucket)
				SELECT id, :occurred, :classification, :reason, :fingerprint, :bucket
				FROM mail_send_records
				WHERE id = :id AND token_hash = :hash AND tracking_enabled = true
				  AND tracking_expires_at = :expires AND tracking_expires_at > :occurred AND status <> 'FAILED'
				ON CONFLICT (record_id, fingerprint_hash, minute_bucket) DO NOTHING
				""").bind("id", token.recordId()).bind("hash", token.digest()).bind("expires", token.expiresAt())
				.bind("occurred", occurredAt).bind("classification", observation.classification().name())
				.bind("reason", observation.reason()).bind("fingerprint", observation.fingerprintHash())
				.bind("bucket", Math.floorDiv(occurredAt.getEpochSecond(), 60)).fetch().rowsUpdated().then();
	}

	public Mono<Void> insertLinks(UUID recordId, List<PendingClickLink> links, Instant createdAt) {
		return Flux.fromIterable(links).concatMap(link -> {
			var query = database.sql("""
					INSERT INTO mail_click_links (
					    id, record_id, target_url, target_url_hash, label, position, token_hash, expires_at, created_at
					) VALUES (:id, :record, :target, :targetHash, :label, :position, :tokenHash, :expires, :created)
					""").bind("id", link.id()).bind("record", recordId).bind("target", link.targetUrl())
					.bind("targetHash", MailTrackingSigner.digest(link.targetUrl())).bind("position", link.position())
					.bind("tokenHash", link.tokenHash()).bind("expires", link.expiresAt()).bind("created", createdAt);
			query = link.label() == null ? query.bindNull("label", String.class) : query.bind("label", link.label());
			return query.fetch().rowsUpdated();
		}).then();
	}

	public Mono<Void> observeClick(UUID linkId, MailOpenClassifier.Observation observation, Instant occurredAt) {
		return database.sql("""
				INSERT INTO mail_click_events (link_id, occurred_at, classification, reason, fingerprint_hash, minute_bucket)
				SELECT link.id, :occurred, :classification, :reason, :fingerprint, :bucket
				FROM mail_click_links link
				JOIN mail_send_records record ON record.id = link.record_id
				WHERE link.id = :link AND link.expires_at > :occurred
				  AND record.tracking_enabled = true AND record.status <> 'FAILED'
				ON CONFLICT (link_id, fingerprint_hash, minute_bucket) DO NOTHING
				""").bind("link", linkId).bind("occurred", occurredAt)
				.bind("classification", observation.classification().name()).bind("reason", observation.reason())
				.bind("fingerprint", observation.fingerprintHash())
				.bind("bucket", Math.floorDiv(occurredAt.getEpochSecond(), 60)).fetch().rowsUpdated().then();
	}

	public Mono<ResolvedClick> resolveClick(MailTrackingSigner.VerifiedClickToken token, Instant occurredAt) {
		return database.sql("""
				SELECT record.id AS record_id, link.id AS link_id, link.target_url
				FROM mail_click_links link
				JOIN mail_send_records record ON record.id = link.record_id
				WHERE record.id = :record AND link.id = :link AND link.token_hash = :hash
				  AND link.expires_at = :expires AND link.expires_at > :occurred
				  AND record.tracking_enabled = true AND record.status <> 'FAILED'
				""").bind("record", token.recordId()).bind("link", token.linkId()).bind("hash", token.digest())
				.bind("expires", token.expiresAt()).bind("occurred", occurredAt)
				.map((row, metadata) -> new ResolvedClick(row.get("record_id", UUID.class),
						row.get("link_id", UUID.class), row.get("target_url", String.class))).one();
	}

	public Flux<MailSendRecord> list(int offset, int limit) {
		return database.sql(selectSql("""
				(SELECT * FROM mail_send_records ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit)
				""") + " ORDER BY r.created_at DESC, r.id DESC")
				.bind("offset", offset).bind("limit", limit).map(this::record).all();
	}

	public Mono<Long> count() {
		return database.sql("SELECT count(*) AS total FROM mail_send_records")
				.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	public Mono<MailSendRecord> find(UUID id) {
		return database.sql(selectSql("mail_send_records") + " WHERE r.id = :id")
				.bind("id", id).map(this::record).one();
	}

	public Flux<MailOpenEvent> latestEvents(UUID id) {
		return database.sql("""
				SELECT id, occurred_at, classification, reason FROM mail_open_events
				WHERE record_id = :id ORDER BY occurred_at DESC, id DESC LIMIT 50
				""").bind("id", id).map((row, metadata) -> new MailOpenEvent(
				row.get("id", Long.class), row.get("occurred_at", Instant.class),
				Classification.valueOf(row.get("classification", String.class)), row.get("reason", String.class))).all();
	}

	public Flux<MailClickLink> latestLinks(UUID id) {
		return database.sql("""
				SELECT link.id, link.target_url, link.label, link.position,
				       count(event.id) AS raw_click_count,
				       count(event.id) FILTER (WHERE event.classification <> 'UNCLASSIFIED') AS automated_click_count,
				       min(event.occurred_at) AS first_click_at, max(event.occurred_at) AS last_click_at
				FROM mail_click_links link
				LEFT JOIN mail_click_events event ON event.link_id = link.id
				WHERE link.record_id = :id
				GROUP BY link.id, link.target_url, link.label, link.position
				ORDER BY link.position, link.id LIMIT 100
				""").bind("id", id).map((row, metadata) -> new MailClickLink(
				row.get("id", UUID.class), row.get("target_url", String.class), row.get("label", String.class),
				row.get("position", Integer.class), row.get("raw_click_count", Long.class),
				row.get("automated_click_count", Long.class), row.get("first_click_at", Instant.class),
				row.get("last_click_at", Instant.class))).all();
	}

	public Flux<MailClickEvent> latestClickEvents(UUID id) {
		return database.sql("""
				SELECT event.id, event.link_id, event.occurred_at, event.classification, event.reason
				FROM mail_click_events event
				JOIN mail_click_links link ON link.id = event.link_id
				WHERE link.record_id = :id
				ORDER BY event.occurred_at DESC, event.id DESC LIMIT 50
				""").bind("id", id).map((row, metadata) -> new MailClickEvent(
				row.get("id", Long.class), row.get("link_id", UUID.class), row.get("occurred_at", Instant.class),
				Classification.valueOf(row.get("classification", String.class)), row.get("reason", String.class))).all();
	}

	private String selectSql(String records) {
		return """
				SELECT r.id, r.source, r.recipient_masked, r.subject, a.name AS smtp_account_name,
				       r.status, r.failure_category, r.tracking_enabled, r.created_at, r.completed_at, r.tracking_expires_at,
				       e.raw_open_count, e.automated_open_count, e.first_open_at, e.last_open_at,
				       c.raw_click_count, c.automated_click_count, c.first_click_at, c.last_click_at
				FROM %s r
				LEFT JOIN smtp_accounts a ON a.id = r.smtp_account_id
				LEFT JOIN LATERAL (
				    SELECT count(*) AS raw_open_count,
				           count(*) FILTER (WHERE classification <> 'UNCLASSIFIED') AS automated_open_count,
				           min(occurred_at) AS first_open_at, max(occurred_at) AS last_open_at
				    FROM mail_open_events WHERE record_id = r.id
				) e ON true
				LEFT JOIN LATERAL (
				    SELECT count(event.id) AS raw_click_count,
				           count(event.id) FILTER (WHERE event.classification <> 'UNCLASSIFIED') AS automated_click_count,
				           min(event.occurred_at) AS first_click_at, max(event.occurred_at) AS last_click_at
				    FROM mail_click_links link
				    LEFT JOIN mail_click_events event ON event.link_id = link.id
				    WHERE link.record_id = r.id
				) c ON true
				""".formatted(records);
	}

	private MailSendRecord record(Row row, RowMetadata metadata) {
		return new MailSendRecord(row.get("id", UUID.class), Source.valueOf(row.get("source", String.class)),
				row.get("recipient_masked", String.class), row.get("subject", String.class), row.get("smtp_account_name", String.class),
				Status.valueOf(row.get("status", String.class)), row.get("failure_category", String.class),
				Boolean.TRUE.equals(row.get("tracking_enabled", Boolean.class)), row.get("created_at", Instant.class),
				row.get("completed_at", Instant.class), row.get("tracking_expires_at", Instant.class),
				row.get("raw_open_count", Long.class), row.get("automated_open_count", Long.class),
				row.get("first_open_at", Instant.class), row.get("last_open_at", Instant.class),
				row.get("raw_click_count", Long.class), row.get("automated_click_count", Long.class),
				row.get("first_click_at", Instant.class), row.get("last_click_at", Instant.class));
	}
}
