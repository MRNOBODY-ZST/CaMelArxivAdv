CREATE TABLE ingestion_daily_stats (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stat_date DATE NOT NULL,
    category_id UUID REFERENCES arxiv_categories(id) ON DELETE SET NULL,
    query_matched BIGINT NOT NULL DEFAULT 0,
    papers_imported BIGINT NOT NULL DEFAULT 0,
    source_available BIGINT NOT NULL DEFAULT 0,
    source_download_succeeded BIGINT NOT NULL DEFAULT 0,
    source_download_failed BIGINT NOT NULL DEFAULT 0,
    security_rejected BIGINT NOT NULL DEFAULT 0,
    archives_unpacked BIGINT NOT NULL DEFAULT 0,
    tex_discovered BIGINT NOT NULL DEFAULT 0,
    parse_succeeded BIGINT NOT NULL DEFAULT 0,
    parse_partial BIGINT NOT NULL DEFAULT 0,
    parse_failed BIGINT NOT NULL DEFAULT 0,
    papers_with_email BIGINT NOT NULL DEFAULT 0,
    papers_without_email BIGINT NOT NULL DEFAULT 0,
    duration_sum_ms BIGINT NOT NULL DEFAULT 0,
    duration_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ingestion_daily_stats_dimensions UNIQUE NULLS NOT DISTINCT (stat_date, category_id),
    CONSTRAINT ck_ingestion_stats_nonnegative CHECK (
        query_matched >= 0 AND papers_imported >= 0 AND source_available >= 0
        AND source_download_succeeded >= 0 AND source_download_failed >= 0
        AND security_rejected >= 0 AND archives_unpacked >= 0 AND tex_discovered >= 0
        AND parse_succeeded >= 0 AND parse_partial >= 0 AND parse_failed >= 0
        AND papers_with_email >= 0 AND papers_without_email >= 0
        AND duration_sum_ms >= 0 AND duration_count >= 0
    )
);

CREATE TABLE extraction_duration_samples (
    extraction_run_id UUID PRIMARY KEY REFERENCES extraction_runs(id) ON DELETE CASCADE,
    stat_date DATE NOT NULL,
    category_id UUID REFERENCES arxiv_categories(id) ON DELETE SET NULL,
    document_class VARCHAR(100),
    duration_ms BIGINT NOT NULL,
    CONSTRAINT ck_extraction_duration_sample CHECK (duration_ms >= 0)
);

CREATE INDEX ix_extraction_duration_distribution ON extraction_duration_samples (stat_date, category_id, duration_ms);

CREATE TABLE contact_daily_stats (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stat_date DATE NOT NULL,
    category_id UUID REFERENCES arxiv_categories(id) ON DELETE SET NULL,
    email_domain VARCHAR(255) NOT NULL DEFAULT '',
    confidence VARCHAR(20) NOT NULL DEFAULT 'ALL',
    unique_authors BIGINT NOT NULL DEFAULT 0,
    unique_contacts BIGINT NOT NULL DEFAULT 0,
    corresponding_contacts BIGINT NOT NULL DEFAULT 0,
    human_confirmed BIGINT NOT NULL DEFAULT 0,
    rule_hits JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_contact_daily_stats_dimensions UNIQUE NULLS NOT DISTINCT (stat_date, category_id, email_domain, confidence),
    CONSTRAINT ck_contact_stat_confidence CHECK (confidence IN ('ALL', 'HIGH', 'MEDIUM', 'LOW', 'UNMAPPED')),
    CONSTRAINT ck_contact_stats_nonnegative CHECK (
        unique_authors >= 0 AND unique_contacts >= 0
        AND corresponding_contacts >= 0 AND human_confirmed >= 0
    )
);

CREATE TABLE campaign_hourly_stats (
    hour_start TIMESTAMPTZ NOT NULL,
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    smtp_account_id UUID REFERENCES smtp_accounts(id) ON DELETE SET NULL,
    email_domain VARCHAR(255) NOT NULL DEFAULT '',
    category_id VARCHAR(80) NOT NULL DEFAULT '',
    recipients BIGINT NOT NULL DEFAULT 0,
    smtp_accepted BIGINT NOT NULL DEFAULT 0,
    temporary_failures BIGINT NOT NULL DEFAULT 0,
    permanent_failures BIGINT NOT NULL DEFAULT 0,
    unsubscribes BIGINT NOT NULL DEFAULT 0,
    raw_opens BIGINT NOT NULL DEFAULT 0,
    unique_raw_opens BIGINT NOT NULL DEFAULT 0,
    likely_human_opens BIGINT NOT NULL DEFAULT 0,
    unique_likely_human_opens BIGINT NOT NULL DEFAULT 0,
    total_clicks BIGINT NOT NULL DEFAULT 0,
    unique_clicks BIGINT NOT NULL DEFAULT 0,
    scanner_events BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (hour_start, campaign_id, email_domain, category_id),
    CONSTRAINT ck_campaign_stats_nonnegative CHECK (
        recipients >= 0 AND smtp_accepted >= 0 AND temporary_failures >= 0
        AND permanent_failures >= 0 AND unsubscribes >= 0 AND raw_opens >= 0
        AND unique_raw_opens >= 0 AND likely_human_opens >= 0
        AND unique_likely_human_opens >= 0 AND total_clicks >= 0
        AND unique_clicks >= 0 AND scanner_events >= 0
    )
);

CREATE INDEX ix_campaign_hourly_stats_campaign_time ON campaign_hourly_stats (campaign_id, hour_start DESC);

CREATE TABLE link_daily_stats (
    stat_date DATE NOT NULL,
    campaign_link_id UUID NOT NULL REFERENCES campaign_links(id) ON DELETE CASCADE,
    total_clicks BIGINT NOT NULL DEFAULT 0,
    unique_clicks BIGINT NOT NULL DEFAULT 0,
    likely_human_clicks BIGINT NOT NULL DEFAULT 0,
    scanner_clicks BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (stat_date, campaign_link_id),
    CONSTRAINT ck_link_stats_nonnegative CHECK (
        total_clicks >= 0 AND unique_clicks >= 0
        AND likely_human_clicks >= 0 AND scanner_clicks >= 0
    )
);

CREATE TABLE analytics_refresh_log (
    aggregate_name VARCHAR(120) PRIMARY KEY,
    data_through TIMESTAMPTZ NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(20) NOT NULL,
    error_summary VARCHAR(500),
    CONSTRAINT ck_analytics_refresh_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
);

CREATE TABLE data_retention_policies (
    data_type VARCHAR(80) PRIMARY KEY,
    retention_days INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_retention_days CHECK (retention_days BETWEEN 1 AND 3650)
);

INSERT INTO data_retention_policies (data_type, retention_days) VALUES
    ('TRACKING_EVENT', 180),
    ('EXTRACTION_EVIDENCE', 365),
    ('LOGIN_ATTEMPT', 90),
    ('JOB_EVENT', 180),
    ('AUDIT_LOG', 730);

CREATE MATERIALIZED VIEW mv_campaign_summary AS
SELECT
    c.id AS campaign_id,
    c.status,
    count(cr.id) AS recipient_count,
    count(cr.id) FILTER (WHERE cr.status = 'SMTP_ACCEPTED') AS smtp_accepted_count,
    count(cr.id) FILTER (WHERE cr.status = 'TEMPORARY_FAILURE') AS temporary_failure_count,
    count(cr.id) FILTER (WHERE cr.status IN ('PERMANENT_FAILURE', 'BOUNCED')) AS permanent_failure_count,
    count(DISTINCT te.campaign_recipient_id) FILTER (
        WHERE te.event_type = 'OPEN' AND te.classification = 'LIKELY_HUMAN'
    ) AS unique_likely_human_open_count,
    count(DISTINCT te.campaign_recipient_id) FILTER (
        WHERE te.event_type = 'CLICK' AND te.classification = 'LIKELY_HUMAN'
    ) AS unique_likely_human_click_count,
    max(te.occurred_at) AS last_tracking_event_at
FROM campaigns c
LEFT JOIN campaign_recipients cr ON cr.campaign_id = c.id
LEFT JOIN tracking_events te ON te.campaign_recipient_id = cr.id
GROUP BY c.id, c.status
WITH NO DATA;

CREATE UNIQUE INDEX uk_mv_campaign_summary_campaign ON mv_campaign_summary (campaign_id);
