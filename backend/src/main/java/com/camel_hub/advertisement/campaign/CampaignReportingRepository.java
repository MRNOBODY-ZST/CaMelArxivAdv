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
		return databaseClient.sql("""
				SELECT attempt.id, campaign.id AS campaign_id, campaign.name AS campaign_name,
				       recipient.id AS recipient_id, recipient.author_name_snapshot,
				       recipient.paper_title_snapshot, attempt.attempt_number, attempt.status,
				       attempt.smtp_response_code, attempt.smtp_response_summary,
				       attempt.failure_category, attempt.retryable, attempt.started_at, attempt.completed_at
				FROM delivery_attempts attempt
				JOIN campaign_recipients recipient ON recipient.id = attempt.campaign_recipient_id
				JOIN campaigns campaign ON campaign.id = recipient.campaign_id
				ORDER BY attempt.started_at DESC, attempt.id
				OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit).map((row, metadata) -> new DeliveryView(
				row.get("id", UUID.class), row.get("campaign_id", UUID.class),
				row.get("campaign_name", String.class), row.get("recipient_id", UUID.class),
				row.get("author_name_snapshot", String.class), row.get("paper_title_snapshot", String.class),
				number(row, "attempt_number"), row.get("status", String.class),
				row.get("smtp_response_code", Integer.class), row.get("smtp_response_summary", String.class),
				row.get("failure_category", String.class),
				Boolean.TRUE.equals(row.get("retryable", Boolean.class)),
				row.get("started_at", Instant.class), row.get("completed_at", Instant.class))).all();
	}

	public Mono<Long> deliveryCount() {
		return count("delivery_attempts");
	}

	public Flux<CampaignAnalyticsView> campaigns(int offset, int limit) {
		return databaseClient.sql("""
				SELECT campaign.id, campaign.name, campaign.status, campaign.generation_status,
				       campaign.created_at,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id) AS recipients,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id AND r.smtp_accepted_at IS NOT NULL) AS smtp_accepted,
				       (SELECT count(*) FROM campaign_recipients r
				        WHERE r.campaign_id = campaign.id
				          AND r.status IN ('PERMANENT_FAILURE', 'BOUNCED')) AS permanent_failures,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'OPEN' AND e.classification = 'LIKELY_HUMAN') AS human_opens,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'CLICK' AND e.classification = 'LIKELY_HUMAN') AS human_clicks,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'OPEN'
				          AND e.classification IN ('BOT','PREFETCH','SECURITY_SCANNER')) AS automated_opens,
				       (SELECT count(*) FROM tracking_events e WHERE e.campaign_id = campaign.id
				          AND e.event_type = 'CLICK'
				          AND e.classification IN ('BOT','PREFETCH','SECURITY_SCANNER')) AS automated_clicks
				FROM campaigns campaign ORDER BY campaign.created_at DESC, campaign.id
				OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit).map((row, metadata) -> new CampaignAnalyticsView(
				row.get("id", UUID.class), row.get("name", String.class), row.get("status", String.class),
				row.get("generation_status", String.class), number(row, "recipients"),
				number(row, "smtp_accepted"), number(row, "permanent_failures"),
				number(row, "human_opens"), number(row, "human_clicks"), number(row, "automated_opens"),
				number(row, "automated_clicks"), row.get("created_at", Instant.class))).all();
	}

	public Mono<Long> campaignCount() {
		return count("campaigns");
	}

	public Flux<LinkAnalyticsView> links(int offset, int limit) {
		return databaseClient.sql("""
				SELECT link.id, campaign.id AS campaign_id, campaign.name AS campaign_name,
				       link.target_url, link.label,
				       (SELECT count(*) FROM tracking_events event WHERE event.campaign_link_id = link.id
				          AND event.event_type = 'CLICK'
				          AND event.classification = 'LIKELY_HUMAN') AS human_clicks,
				       (SELECT count(*) FROM tracking_events event WHERE event.campaign_link_id = link.id
				          AND event.event_type = 'CLICK'
				          AND event.classification IN ('BOT','PREFETCH','SECURITY_SCANNER')) AS automated_clicks,
				       link.created_at
				FROM campaign_links link JOIN campaigns campaign ON campaign.id = link.campaign_id
				ORDER BY link.created_at DESC, link.id OFFSET :offset LIMIT :limit
				""").bind("offset", offset).bind("limit", limit).map((row, metadata) -> new LinkAnalyticsView(
				row.get("id", UUID.class), row.get("campaign_id", UUID.class),
				row.get("campaign_name", String.class), row.get("target_url", String.class),
				row.get("label", String.class), number(row, "human_clicks"),
				number(row, "automated_clicks"), row.get("created_at", Instant.class))).all();
	}

	public Mono<Long> linkCount() {
		return count("campaign_links");
	}

	private Mono<Long> count(String table) {
		String sql = switch (table) {
			case "delivery_attempts" -> "SELECT count(*) AS total FROM delivery_attempts";
			case "campaigns" -> "SELECT count(*) AS total FROM campaigns";
			case "campaign_links" -> "SELECT count(*) AS total FROM campaign_links";
			default -> throw new IllegalArgumentException("Unsupported reporting table");
		};
		return databaseClient.sql(sql).map((row, metadata) -> row.get("total", Long.class)).one();
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
			int smtpAccepted, int permanentFailures, int humanOpens, int humanClicks,
			int automatedOpens, int automatedClicks, Instant createdAt
	) { }

	public record LinkAnalyticsView(
			UUID id, UUID campaignId, String campaignName, String targetUrl, String label,
			int humanClicks, int automatedClicks, Instant createdAt
	) { }
}
