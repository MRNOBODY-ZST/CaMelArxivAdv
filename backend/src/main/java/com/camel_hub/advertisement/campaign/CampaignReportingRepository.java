package com.camel_hub.advertisement.campaign;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public final class CampaignReportingRepository {

	private final DatabaseClient databaseClient;

	public CampaignReportingRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<DeliveryView> deliveries(int offset, int limit) {
		return deliveries(null, offset, limit);
	}

	public Flux<DeliveryView> deliveries(UUID campaignId, int offset, int limit) {
		String filter = campaignId == null ? "" : "WHERE campaign.id = :campaignId\n";
		DatabaseClient.GenericExecuteSpec query = databaseClient.sql("""
				SELECT attempt.id, campaign.id AS campaign_id, campaign.name AS campaign_name,
				       recipient.id AS recipient_id, recipient.author_name_snapshot,
				       recipient.paper_title_snapshot, attempt.attempt_number, attempt.status,
				       attempt.smtp_response_code, attempt.smtp_response_summary,
				       attempt.failure_category, attempt.retryable, attempt.started_at, attempt.completed_at
				FROM delivery_attempts attempt
				JOIN campaign_recipients recipient ON recipient.id = attempt.campaign_recipient_id
				JOIN campaigns campaign ON campaign.id = recipient.campaign_id
				""" + filter + """
				ORDER BY attempt.started_at DESC, attempt.id
				OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit);
		if (campaignId != null) query = query.bind("campaignId", campaignId);
		return query.map((row, metadata) -> new DeliveryView(
				row.get("id", UUID.class), row.get("campaign_id", UUID.class),
				row.get("campaign_name", String.class), row.get("recipient_id", UUID.class),
				row.get("author_name_snapshot", String.class), row.get("paper_title_snapshot", String.class),
				number(row, "attempt_number"), row.get("status", String.class),
				row.get("smtp_response_code", Integer.class), row.get("smtp_response_summary", String.class),
				row.get("failure_category", String.class),
				Boolean.TRUE.equals(row.get("retryable", Boolean.class)),
				row.get("started_at", Instant.class), row.get("completed_at", Instant.class))).all();
	}

	public Mono<Long> deliveryCount(UUID campaignId) {
		return count("delivery_attempts", campaignId);
	}

	public Flux<CampaignAnalyticsView> campaigns(int offset, int limit) {
		return campaigns(null, offset, limit);
	}

	public Flux<CampaignAnalyticsView> campaigns(UUID campaignId, int offset, int limit) {
		String filter = campaignId == null ? "" : "WHERE campaign.id = :campaignId\n";
		DatabaseClient.GenericExecuteSpec query = databaseClient.sql("""
				SELECT campaign.id, campaign.name, campaign.status, campaign.generation_status,
				       campaign.created_at,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id) AS recipients,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id AND r.smtp_accepted_at IS NOT NULL) AS smtp_accepted,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id
				          AND r.status = 'PERMANENT_FAILURE') AS permanent_failures,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id AND r.status = 'OUTCOME_UNKNOWN') AS outcome_unknown,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id AND r.status = 'BOUNCED') AS bounced,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id AND r.status = 'UNSUBSCRIBED') AS unsubscribed,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id AND r.replied_at IS NOT NULL) AS replied,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'OPEN') AS raw_opens,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'OPEN' AND e.classification = 'LIKELY_HUMAN') AS human_opens,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'CLICK' AND e.classification = 'LIKELY_HUMAN') AS human_clicks,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'OPEN'
				          AND e.classification IN ('BOT','PREFETCH','SECURITY_SCANNER')) AS automated_opens,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'CLICK') AS raw_clicks,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'CLICK'
				          AND e.classification IN ('BOT','PREFETCH','SECURITY_SCANNER')) AS automated_clicks,
				       (SELECT count(*) FROM campaign_safety_runs sr
				        WHERE sr.campaign_id = campaign.id) AS safety_runs,
				       (SELECT count(*) FROM campaign_safety_messages sm
				        JOIN campaign_safety_runs sr ON sr.id = sm.run_id
				        WHERE sr.campaign_id = campaign.id) AS safety_messages,
				       (SELECT count(*) FROM campaign_safety_messages sm
				        JOIN campaign_safety_runs sr ON sr.id = sm.run_id
				        WHERE sr.campaign_id = campaign.id AND sm.smtp_accepted_at IS NOT NULL) AS safety_smtp_accepted,
				       (SELECT count(*) FROM campaign_safety_messages sm
				        JOIN campaign_safety_runs sr ON sr.id = sm.run_id
				        WHERE sr.campaign_id = campaign.id AND sm.status = 'OUTCOME_UNKNOWN') AS safety_outcome_unknown,
				       (SELECT count(*) FROM campaign_safety_events se
				        JOIN campaign_safety_runs sr ON sr.id = se.run_id
				        WHERE sr.campaign_id = campaign.id AND se.event_type = 'REPLY') AS safety_replies,
				       (SELECT count(*) FROM campaign_safety_events se
				        JOIN campaign_safety_runs sr ON sr.id = se.run_id
				        WHERE sr.campaign_id = campaign.id AND se.event_type = 'BOUNCE') AS safety_bounces
				FROM campaigns campaign
				""" + filter + """
				ORDER BY campaign.created_at DESC, campaign.id
				OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit);
		if (campaignId != null) query = query.bind("campaignId", campaignId);
		return query.map((row, metadata) -> {
			int recipients = number(row, "recipients");
			int accepted = number(row, "smtp_accepted");
			return new CampaignAnalyticsView(
				row.get("id", UUID.class), row.get("name", String.class), row.get("status", String.class),
				row.get("generation_status", String.class), recipients, accepted,
				number(row, "permanent_failures"), number(row, "outcome_unknown"), number(row, "bounced"),
				number(row, "unsubscribed"), number(row, "replied"), number(row, "raw_opens"),
				number(row, "human_opens"), number(row, "automated_opens"), number(row, "raw_clicks"),
				number(row, "human_clicks"), number(row, "automated_clicks"),
				new CampaignRates(rate(accepted, recipients), rate(number(row, "bounced"), accepted),
						rate(number(row, "unsubscribed"), accepted), rate(number(row, "replied"), accepted)),
				new SafetySummary(number(row, "safety_runs"), number(row, "safety_messages"),
						number(row, "safety_smtp_accepted"), number(row, "safety_outcome_unknown"),
						number(row, "safety_replies"), number(row, "safety_bounces")),
				row.get("created_at", Instant.class));
		}).all();
	}

	public Mono<Long> campaignCount(UUID campaignId) {
		return count("campaigns", campaignId);
	}

	public Flux<LinkAnalyticsView> links(int offset, int limit) {
		return links(null, offset, limit);
	}

	public Flux<LinkAnalyticsView> links(UUID campaignId, int offset, int limit) {
		String filter = campaignId == null ? "" : "WHERE campaign.id = :campaignId\n";
		DatabaseClient.GenericExecuteSpec query = databaseClient.sql("""
				SELECT link.id, campaign.id AS campaign_id, campaign.name AS campaign_name,
				       link.target_url, link.label,
				       (SELECT count(*) FROM tracking_events event WHERE event.campaign_link_id = link.id
				          AND event.event_type = 'CLICK') AS raw_clicks,
				       (SELECT count(*) FROM tracking_events event WHERE event.campaign_link_id = link.id
				          AND event.event_type = 'CLICK'
				          AND event.classification = 'LIKELY_HUMAN') AS human_clicks,
				       (SELECT count(*) FROM tracking_events event WHERE event.campaign_link_id = link.id
				          AND event.event_type = 'CLICK'
				          AND event.classification IN ('BOT','PREFETCH','SECURITY_SCANNER')) AS automated_clicks,
				       link.created_at
				FROM campaign_links link JOIN campaigns campaign ON campaign.id = link.campaign_id
				""" + filter + """
				ORDER BY link.created_at DESC, link.id OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit);
		if (campaignId != null) query = query.bind("campaignId", campaignId);
		return query.map((row, metadata) -> new LinkAnalyticsView(
				row.get("id", UUID.class), row.get("campaign_id", UUID.class),
				row.get("campaign_name", String.class), row.get("target_url", String.class),
				row.get("label", String.class), number(row, "raw_clicks"), number(row, "human_clicks"),
				number(row, "automated_clicks"), row.get("created_at", Instant.class))).all();
	}

	public Mono<Long> linkCount(UUID campaignId) {
		return count("campaign_links", campaignId);
	}

	private Mono<Long> count(String table, UUID campaignId) {
		String base = switch (table) {
			case "delivery_attempts" -> "SELECT count(*) AS total FROM delivery_attempts attempt "
					+ "JOIN campaign_recipients recipient ON recipient.id = attempt.campaign_recipient_id";
			case "campaigns" -> "SELECT count(*) AS total FROM campaigns campaign";
			case "campaign_links" -> "SELECT count(*) AS total FROM campaign_links link";
			default -> throw new IllegalArgumentException("Unsupported reporting table");
		};
		String qualifier = switch (table) {
			case "delivery_attempts" -> "recipient.campaign_id";
			case "campaigns" -> "campaign.id";
			case "campaign_links" -> "link.campaign_id";
			default -> throw new IllegalArgumentException("Unsupported reporting table");
		};
		DatabaseClient.GenericExecuteSpec query = databaseClient.sql(
				campaignId == null ? base : base + " WHERE " + qualifier + " = :campaignId");
		if (campaignId != null) query = query.bind("campaignId", campaignId);
		return query.map((row, metadata) -> row.get("total", Long.class)).one();
	}

	private double rate(int numerator, int denominator) {
		return denominator <= 0 ? 0.0 : (double) numerator / denominator;
	}

	private int number(io.r2dbc.spi.Row row, String field) {
		Number value = row.get(field, Number.class);
		return value == null ? 0 : value.intValue();
	}

	public record DeliveryView(
			UUID id, UUID campaignId, String campaignName, UUID recipientId, String authorName,
			String paperTitle, int attemptNumber, String status, Integer smtpResponseCode,
			String smtpResponseSummary, String failureCategory, boolean retryable,
			Instant startedAt, Instant completedAt
	) { }

	public record CampaignAnalyticsView(
			UUID id, String name, String status, String generationStatus, int recipients,
			int smtpAccepted, int permanentFailures, int outcomeUnknown, int bounced, int unsubscribed,
			int replied, int rawOpens, int humanOpens, int automatedOpens, int rawClicks, int humanClicks,
			int automatedClicks, CampaignRates rates, SafetySummary safety, Instant createdAt
	) { }

	public record CampaignRates(double smtpAcceptance, double bounce, double unsubscribe, double reply) { }

	public record SafetySummary(
			int runs, int messages, int smtpAccepted, int outcomeUnknown, int replies, int bounces
	) { }

	public record LinkAnalyticsView(
			UUID id, UUID campaignId, String campaignName, String targetUrl, String label,
			int rawClicks, int humanClicks, int automatedClicks, Instant createdAt
	) { }
}
