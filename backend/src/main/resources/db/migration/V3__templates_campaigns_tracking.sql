CREATE TABLE smtp_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    tls_mode VARCHAR(20) NOT NULL,
    username VARCHAR(255),
    password_ciphertext BYTEA,
    password_nonce BYTEA,
    from_email VARCHAR(320) NOT NULL,
    default_from_name VARCHAR(160) NOT NULL,
    reply_to VARCHAR(320) NOT NULL,
    per_minute_limit INTEGER NOT NULL,
    per_hour_limit INTEGER NOT NULL,
    per_day_limit INTEGER NOT NULL,
    per_domain_hour_limit INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    last_tested_at TIMESTAMPTZ,
    last_test_status VARCHAR(20),
    last_test_error VARCHAR(500),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_smtp_accounts_name UNIQUE (name),
    CONSTRAINT ck_smtp_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_smtp_tls CHECK (tls_mode IN ('STARTTLS_REQUIRED', 'TLS_IMPLICIT', 'PLAIN_LOCAL_ONLY')),
    CONSTRAINT ck_smtp_limits CHECK (
        per_minute_limit > 0 AND per_hour_limit > 0 AND per_day_limit > 0 AND per_domain_hour_limit > 0
    ),
    CONSTRAINT ck_smtp_test_status CHECK (last_test_status IS NULL OR last_test_status IN ('SUCCEEDED', 'FAILED'))
);

CREATE TABLE email_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    current_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_email_templates_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_email_template_version CHECK (current_version >= 1)
);

CREATE INDEX ix_email_templates_status_updated ON email_templates (status, updated_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE email_template_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES email_templates(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    subject_template VARCHAR(998) NOT NULL,
    from_name_template VARCHAR(160) NOT NULL,
    reply_to VARCHAR(320) NOT NULL,
    html_content TEXT NOT NULL,
    text_content TEXT NOT NULL,
    content_size_bytes INTEGER NOT NULL,
    validation_result JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_email_template_version UNIQUE (template_id, version_number),
    CONSTRAINT ck_email_template_content_size CHECK (content_size_bytes >= 0)
);

CREATE TABLE segments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_segments_name UNIQUE (name)
);

CREATE TABLE segment_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    segment_id UUID NOT NULL REFERENCES segments(id) ON DELETE CASCADE,
    rule_order INTEGER NOT NULL,
    field_name VARCHAR(80) NOT NULL,
    operator VARCHAR(40) NOT NULL,
    value_data JSONB NOT NULL,
    CONSTRAINT uk_segment_rule_order UNIQUE (segment_id, rule_order),
    CONSTRAINT ck_segment_rule_order CHECK (rule_order >= 1)
);

CREATE TABLE campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    purpose TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    template_id UUID NOT NULL REFERENCES email_templates(id),
    template_version_id UUID NOT NULL REFERENCES email_template_versions(id),
    segment_id UUID REFERENCES segments(id) ON DELETE SET NULL,
    smtp_account_id UUID NOT NULL REFERENCES smtp_accounts(id),
    from_name VARCHAR(160) NOT NULL,
    from_email VARCHAR(320) NOT NULL,
    reply_to VARCHAR(320) NOT NULL,
    tracking_opens_enabled BOOLEAN NOT NULL DEFAULT false,
    tracking_clicks_enabled BOOLEAN NOT NULL DEFAULT false,
    unsubscribe_enabled BOOLEAN NOT NULL DEFAULT true,
    scheduled_at TIMESTAMPTZ,
    submitted_for_review_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    approved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    rejected_at TIMESTAMPTZ,
    rejected_by UUID REFERENCES users(id) ON DELETE SET NULL,
    rejection_reason VARCHAR(1000),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT', 'READY_FOR_REVIEW', 'APPROVED', 'REJECTED', 'SCHEDULED', 'RUNNING', 'PAUSED', 'COMPLETED', 'CANCELED')),
    CONSTRAINT ck_campaign_unsubscribe CHECK (unsubscribe_enabled = true),
    CONSTRAINT ck_campaign_schedule CHECK (status <> 'SCHEDULED' OR scheduled_at IS NOT NULL)
);

CREATE INDEX ix_campaigns_status_schedule ON campaigns (status, scheduled_at) WHERE status IN ('APPROVED', 'SCHEDULED', 'RUNNING', 'PAUSED');
CREATE INDEX ix_campaigns_created_at ON campaigns (created_at DESC);

CREATE TABLE campaign_recipients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    contact_id UUID REFERENCES contacts(id) ON DELETE SET NULL,
    paper_id UUID REFERENCES papers(id) ON DELETE SET NULL,
    author_id UUID REFERENCES authors(id) ON DELETE SET NULL,
    email_ciphertext BYTEA NOT NULL,
    email_nonce BYTEA NOT NULL,
    email_hmac BYTEA NOT NULL,
    email_domain VARCHAR(255) NOT NULL,
    author_name_snapshot VARCHAR(300),
    paper_title_snapshot TEXT,
    category_snapshot VARCHAR(80),
    organization_snapshot VARCHAR(500),
    confidence VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    exclusion_reason VARCHAR(80),
    rendered_subject VARCHAR(998),
    rendered_html TEXT,
    rendered_text TEXT,
    queued_at TIMESTAMPTZ,
    first_attempt_at TIMESTAMPTZ,
    smtp_accepted_at TIMESTAMPTZ,
    final_failure_at TIMESTAMPTZ,
    first_open_at TIMESTAMPTZ,
    first_click_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_campaign_recipient UNIQUE (campaign_id, email_hmac),
    CONSTRAINT ck_campaign_recipient_confidence CHECK (confidence IN ('HIGH', 'MEDIUM')),
    CONSTRAINT ck_campaign_recipient_status CHECK (status IN ('QUEUED', 'CONNECTING', 'SMTP_ACCEPTED', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'BOUNCED', 'SUPPRESSED', 'UNSUBSCRIBED', 'CANCELED'))
);

COMMENT ON COLUMN campaign_recipients.smtp_accepted_at IS
    'SMTP server accepted the message; this does not prove final delivery.';

CREATE INDEX ix_campaign_recipients_status ON campaign_recipients (campaign_id, status, id);
CREATE INDEX ix_campaign_recipients_domain_status ON campaign_recipients (email_domain, status, smtp_accepted_at);

CREATE TABLE campaign_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    template_version_id UUID NOT NULL REFERENCES email_template_versions(id),
    target_url TEXT NOT NULL,
    target_url_hash BYTEA NOT NULL,
    label VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_campaign_link_target UNIQUE (campaign_id, target_url_hash),
    CONSTRAINT ck_campaign_link_scheme CHECK (target_url ~ '^https?://')
);

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_recipient_id UUID NOT NULL REFERENCES campaign_recipients(id) ON DELETE CASCADE,
    smtp_account_id UUID NOT NULL REFERENCES smtp_accounts(id),
    attempt_number INTEGER NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    smtp_response_code INTEGER,
    smtp_response_summary VARCHAR(500),
    failure_category VARCHAR(80),
    retryable BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_delivery_attempt_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_delivery_attempt_number UNIQUE (campaign_recipient_id, attempt_number),
    CONSTRAINT ck_delivery_attempt_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_delivery_attempt_status CHECK (status IN ('CONNECTING', 'SMTP_ACCEPTED', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'CANCELED'))
);

CREATE INDEX ix_delivery_attempts_recipient_time ON delivery_attempts (campaign_recipient_id, started_at DESC);

CREATE TABLE tracking_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_recipient_id UUID NOT NULL REFERENCES campaign_recipients(id) ON DELETE CASCADE,
    campaign_link_id UUID REFERENCES campaign_links(id) ON DELETE CASCADE,
    token_type VARCHAR(10) NOT NULL,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_tracking_token UNIQUE (token_hash),
    CONSTRAINT ck_tracking_token_type CHECK (token_type IN ('OPEN', 'CLICK')),
    CONSTRAINT ck_tracking_token_link CHECK (
        (token_type = 'OPEN' AND campaign_link_id IS NULL)
        OR (token_type = 'CLICK' AND campaign_link_id IS NOT NULL)
    )
);

CREATE INDEX ix_tracking_tokens_recipient_type ON tracking_tokens (campaign_recipient_id, token_type);

CREATE TABLE tracking_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    campaign_recipient_id UUID NOT NULL REFERENCES campaign_recipients(id) ON DELETE CASCADE,
    campaign_link_id UUID REFERENCES campaign_links(id) ON DELETE SET NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_hash BYTEA,
    user_agent_summary VARCHAR(255),
    classification VARCHAR(30) NOT NULL DEFAULT 'UNCLASSIFIED',
    classification_reason VARCHAR(255),
    CONSTRAINT ck_tracking_event_type CHECK (event_type IN ('OPEN', 'CLICK')),
    CONSTRAINT ck_tracking_classification CHECK (classification IN ('UNCLASSIFIED', 'LIKELY_HUMAN', 'BOT', 'PREFETCH', 'SECURITY_SCANNER'))
);

CREATE INDEX ix_tracking_events_campaign_time ON tracking_events (campaign_id, occurred_at DESC);
CREATE INDEX ix_tracking_events_recipient_type ON tracking_events (campaign_recipient_id, event_type, occurred_at);
CREATE INDEX ix_tracking_events_link_time ON tracking_events (campaign_link_id, occurred_at) WHERE campaign_link_id IS NOT NULL;

CREATE TABLE suppression_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_hmac BYTEA NOT NULL,
    email_domain VARCHAR(255) NOT NULL,
    reason VARCHAR(80) NOT NULL,
    source VARCHAR(40) NOT NULL,
    notes VARCHAR(500),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    CONSTRAINT uk_suppression_email_hmac UNIQUE (email_hmac),
    CONSTRAINT ck_suppression_reason CHECK (reason IN ('UNSUBSCRIBED', 'PERMANENT_FAILURE', 'BOUNCED', 'MANUAL', 'COMPLAINT', 'PRIVACY_REQUEST'))
);

CREATE INDEX ix_suppression_domain ON suppression_entries (email_domain, created_at DESC);

CREATE TABLE unsubscribe_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_hmac BYTEA NOT NULL,
    campaign_id UUID REFERENCES campaigns(id) ON DELETE SET NULL,
    campaign_recipient_id UUID REFERENCES campaign_recipients(id) ON DELETE SET NULL,
    token_hash BYTEA NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_hash BYTEA,
    user_agent_summary VARCHAR(255),
    CONSTRAINT uk_unsubscribe_email_hmac UNIQUE (email_hmac),
    CONSTRAINT uk_unsubscribe_token_hash UNIQUE (token_hash)
);

CREATE TABLE campaign_exclusions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    email_hmac BYTEA NOT NULL,
    reason VARCHAR(80) NOT NULL,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_campaign_exclusion UNIQUE (campaign_id, email_hmac)
);

CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange_name VARCHAR(100) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    message_type VARCHAR(80) NOT NULL,
    message_version INTEGER NOT NULL,
    aggregate_id UUID,
    idempotency_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    CONSTRAINT uk_outbox_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_outbox_message_version CHECK (message_version >= 1),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_pending ON outbox_messages (available_at, id) WHERE published_at IS NULL;

