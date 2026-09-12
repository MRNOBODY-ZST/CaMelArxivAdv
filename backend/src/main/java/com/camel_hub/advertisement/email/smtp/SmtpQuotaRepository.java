package com.camel_hub.advertisement.email.smtp;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** All callers must hold the SMTP account row lock until their reservation is committed. */
public final class SmtpQuotaRepository {
	private final DatabaseClient database;

	public SmtpQuotaRepository(DatabaseClient database) {
		this.database = database;
	}

	public Flux<SmtpQuotaPolicy.Reservation> reservations(SmtpRepository.SmtpAccountRecord account, Instant now) {
		return database.sql("""
				SELECT a.started_at, r.email_domain
				FROM delivery_attempts a
				JOIN campaign_recipients r ON r.id = a.campaign_recipient_id
				WHERE a.smtp_account_id = :smtp
				  AND (a.status IN ('CONNECTING', 'SMTP_ACCEPTED') OR (:capped AND a.status = 'OUTCOME_UNKNOWN'))
				  AND a.started_at >= :cutoff
				UNION ALL
				SELECT a.started_at, nullif(lower(split_part(sr.destination_masked, '@', 2)), '') AS email_domain
				FROM campaign_safety_attempts a
				JOIN campaign_safety_messages m ON m.id = a.safety_message_id
				JOIN campaign_safety_runs sr ON sr.id = m.run_id
				WHERE m.smtp_account_id = :smtp
				  AND (a.status IN ('CONNECTING', 'SMTP_ACCEPTED') OR (:capped AND a.status = 'OUTCOME_UNKNOWN'))
				  AND a.started_at >= :cutoff
				UNION ALL
				SELECT created_at AS started_at, nullif(lower(split_part(recipient_masked, '@', 2)), '') AS email_domain
				FROM mail_send_records
				WHERE smtp_account_id = :smtp AND :capped
				  AND status IN ('SENDING', 'SMTP_ACCEPTED', 'UNKNOWN') AND created_at >= :cutoff
				""").bind("smtp", account.id()).bind("capped", account.perMonthLimit() != null)
				.bind("cutoff", SmtpQuotaPolicy.historyCutoff(account, now))
				.map((row, metadata) -> new SmtpQuotaPolicy.Reservation(
						row.get("started_at", Instant.class), row.get("email_domain", String.class))).all();
	}

	/** Diagnostic and template sends reserve their mail_send_records row inside the same transaction. */
	public Mono<Void> checkDiagnosticCapacity(UUID accountId, String recipientMasked, Instant now) {
		return new SmtpRepository(database).findForUpdate(accountId)
				.switchIfEmpty(Mono.error(new SmtpNotFoundException()))
				.flatMap(account -> {
					if (account.perMonthLimit() == null) return Mono.empty();
					String domain = recipientMasked.substring(recipientMasked.lastIndexOf('@') + 1);
					return reservations(account, now).collectList().flatMap(history -> {
						Instant release = SmtpQuotaPolicy.capacityRelease(account, domain, history, now);
						return release == null ? Mono.empty() : Mono.error(new SmtpConflictException(
								"SMTP account quota reached; available again at " + release));
					});
				});
	}
}
