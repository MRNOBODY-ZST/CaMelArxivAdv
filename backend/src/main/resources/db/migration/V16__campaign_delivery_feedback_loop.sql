ALTER TABLE campaigns
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN mailbox_account_id UUID REFERENCES mailbox_accounts(id) ON DELETE SET NULL,
    ADD COLUMN review_preflight_digest BYTEA,
    ADD COLUMN review_preflight_at TIMESTAMPTZ,
    ADD COLUMN status_changed_at TIMESTAMPTZ,
    ADD COLUMN status_changed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_campaign_lock_version CHECK (lock_version >= 0),
    ADD CONSTRAINT ck_campaign_review_preflight_digest CHECK (
        review_preflight_digest IS NULL OR octet_length(review_preflight_digest) = 32
    );

CREATE INDEX ix_campaigns_mailbox_status
    ON campaigns (mailbox_account_id, status, id)
    WHERE mailbox_account_id IS NOT NULL;

ALTER TABLE campaign_recipients
    DROP CONSTRAINT ck_campaign_recipient_status,
    ADD COLUMN delivery_lease_hash BYTEA,
    ADD COLUMN delivery_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rfc_message_id VARCHAR(255),
    ADD COLUMN replied_at TIMESTAMPTZ,
    ADD COLUMN outcome_unknown_at TIMESTAMPTZ,
    ADD COLUMN outcome_unknown_reason VARCHAR(80),
    ADD CONSTRAINT ck_campaign_recipient_delivery_status CHECK (status IN (
        'QUEUED', 'CONNECTING', 'SMTP_ACCEPTED', 'TEMPORARY_FAILURE',
        'PERMANENT_FAILURE', 'BOUNCED', 'SUPPRESSED', 'UNSUBSCRIBED',
        'CANCELED', 'OUTCOME_UNKNOWN'
    )),
    ADD CONSTRAINT ck_campaign_recipient_delivery_lease CHECK (
        (delivery_lease_hash IS NULL AND delivery_lease_expires_at IS NULL)
        OR (octet_length(delivery_lease_hash) = 32 AND delivery_lease_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_campaign_recipient_attempt_count CHECK (attempt_count BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_campaign_recipient_rfc_message_id CHECK (
        rfc_message_id IS NULL OR rfc_message_id ~ '^<[^<>[:space:]]+>$'
    ),
    ADD CONSTRAINT ck_campaign_recipient_unknown_outcome CHECK (
        (outcome_unknown_at IS NULL AND outcome_unknown_reason IS NULL)
        OR (outcome_unknown_at IS NOT NULL AND outcome_unknown_reason IS NOT NULL)
    );

CREATE INDEX ix_campaign_recipients_delivery_due
    ON campaign_recipients (status, next_attempt_at, id)
    WHERE status IN ('QUEUED', 'TEMPORARY_FAILURE');
CREATE INDEX ix_campaign_recipients_rfc_message_id
    ON campaign_recipients (rfc_message_id)
    WHERE rfc_message_id IS NOT NULL;

ALTER TABLE delivery_attempts
    DROP CONSTRAINT ck_delivery_attempt_status,
    ADD COLUMN transport_stage VARCHAR(20),
    ADD COLUMN smtp_enhanced_status_code VARCHAR(16),
    ADD COLUMN rfc_message_id VARCHAR(255),
    ADD COLUMN outcome_unknown_reason VARCHAR(80),
    ADD CONSTRAINT ck_delivery_attempt_status CHECK (status IN (
        'CONNECTING', 'SMTP_ACCEPTED', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'CANCELED', 'OUTCOME_UNKNOWN'
    )),
    ADD CONSTRAINT ck_delivery_attempt_stage CHECK (
        transport_stage IS NULL OR transport_stage IN (
            'CONNECT', 'EHLO', 'STARTTLS', 'AUTH', 'MAIL_FROM', 'RCPT_TO', 'DATA', 'POST_DATA'
        )
    ),
    ADD CONSTRAINT ck_delivery_attempt_rfc_message_id CHECK (
        rfc_message_id IS NULL OR rfc_message_id ~ '^<[^<>[:space:]]+>$'
    );

ALTER TABLE tracking_tokens
    DROP CONSTRAINT ck_tracking_token_type,
    DROP CONSTRAINT ck_tracking_token_link,
    ADD CONSTRAINT ck_tracking_token_type CHECK (token_type IN ('OPEN', 'CLICK', 'UNSUBSCRIBE')),
    ADD CONSTRAINT ck_tracking_token_link CHECK (
        (token_type = 'OPEN' AND campaign_link_id IS NULL)
        OR (token_type = 'CLICK' AND campaign_link_id IS NOT NULL)
        OR (token_type = 'UNSUBSCRIBE' AND campaign_link_id IS NULL)
    );

CREATE TABLE recipient_delivery_cooldowns (
    email_hmac BYTEA PRIMARY KEY,
    last_smtp_accepted_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_recipient_delivery_cooldown_hmac CHECK (octet_length(email_hmac) = 32)
);

CREATE INDEX ix_recipient_delivery_cooldowns_accepted
    ON recipient_delivery_cooldowns (last_smtp_accepted_at DESC);

CREATE TABLE campaign_safety_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    smtp_account_id UUID NOT NULL REFERENCES smtp_accounts(id),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    recipient_limit INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_campaign_safety_run_limit CHECK (recipient_limit BETWEEN 1 AND 20),
    CONSTRAINT ck_campaign_safety_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIALLY_FAILED', 'FAILED', 'CANCELED')
    ),
    CONSTRAINT ck_campaign_safety_run_lock_version CHECK (lock_version >= 0)
);

CREATE INDEX ix_campaign_safety_runs_campaign_created
    ON campaign_safety_runs (campaign_id, created_at DESC, id);
CREATE INDEX ix_campaign_safety_runs_status
    ON campaign_safety_runs (status, created_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE campaign_safety_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES campaign_safety_runs(id) ON DELETE CASCADE,
    campaign_recipient_id UUID NOT NULL REFERENCES campaign_recipients(id),
    smtp_account_id UUID NOT NULL REFERENCES smtp_accounts(id),
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    delivery_lease_hash BYTEA,
    delivery_lease_expires_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    rfc_message_id VARCHAR(255),
    rendered_subject VARCHAR(998),
    rendered_html TEXT,
    rendered_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    smtp_accepted_at TIMESTAMPTZ,
    outcome_unknown_at TIMESTAMPTZ,
    outcome_unknown_reason VARCHAR(80),
    CONSTRAINT uk_campaign_safety_message_recipient UNIQUE (run_id, campaign_recipient_id),
    CONSTRAINT ck_campaign_safety_message_status CHECK (status IN (
        'QUEUED', 'CONNECTING', 'SMTP_ACCEPTED', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'CANCELED', 'OUTCOME_UNKNOWN'
    )),
    CONSTRAINT ck_campaign_safety_message_lease CHECK (
        (delivery_lease_hash IS NULL AND delivery_lease_expires_at IS NULL)
        OR (octet_length(delivery_lease_hash) = 32 AND delivery_lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_campaign_safety_message_attempt_count CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT ck_campaign_safety_message_rfc_message_id CHECK (
        rfc_message_id IS NULL OR rfc_message_id ~ '^<[^<>[:space:]]+>$'
    )
);

CREATE INDEX ix_campaign_safety_messages_due
    ON campaign_safety_messages (status, next_attempt_at, id)
    WHERE status IN ('QUEUED', 'TEMPORARY_FAILURE');
CREATE INDEX ix_campaign_safety_messages_run_status
    ON campaign_safety_messages (run_id, status, id);
CREATE INDEX ix_campaign_safety_messages_rfc_message_id
    ON campaign_safety_messages (rfc_message_id)
    WHERE rfc_message_id IS NOT NULL;

CREATE TABLE campaign_safety_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    safety_message_id UUID NOT NULL REFERENCES campaign_safety_messages(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    transport_stage VARCHAR(20),
    smtp_response_code INTEGER,
    smtp_enhanced_status_code VARCHAR(16),
    smtp_response_summary VARCHAR(500),
    failure_category VARCHAR(80),
    outcome_unknown_reason VARCHAR(80),
    retryable BOOLEAN NOT NULL DEFAULT false,
    rfc_message_id VARCHAR(255),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_campaign_safety_attempt_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_campaign_safety_attempt_number UNIQUE (safety_message_id, attempt_number),
    CONSTRAINT ck_campaign_safety_attempt_number CHECK (attempt_number BETWEEN 1 AND 3),
    CONSTRAINT ck_campaign_safety_attempt_status CHECK (status IN (
        'CONNECTING', 'SMTP_ACCEPTED', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'CANCELED', 'OUTCOME_UNKNOWN'
    )),
    CONSTRAINT ck_campaign_safety_attempt_stage CHECK (
        transport_stage IS NULL OR transport_stage IN (
            'CONNECT', 'EHLO', 'STARTTLS', 'AUTH', 'MAIL_FROM', 'RCPT_TO', 'DATA', 'POST_DATA'
        )
    )
);

CREATE INDEX ix_campaign_safety_attempts_message_time
    ON campaign_safety_attempts (safety_message_id, started_at DESC);

CREATE TABLE campaign_safety_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    safety_message_id UUID NOT NULL REFERENCES campaign_safety_messages(id) ON DELETE CASCADE,
    target_url VARCHAR(2048) NOT NULL,
    target_url_hash BYTEA NOT NULL,
    token_type VARCHAR(20) NOT NULL,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_campaign_safety_link_target UNIQUE (safety_message_id, target_url_hash),
    CONSTRAINT uk_campaign_safety_link_token UNIQUE (token_hash),
    CONSTRAINT ck_campaign_safety_link_target_hash CHECK (octet_length(target_url_hash) = 32),
    CONSTRAINT ck_campaign_safety_link_token_hash CHECK (octet_length(token_hash) = 32),
    CONSTRAINT ck_campaign_safety_link_token_type CHECK (token_type IN ('OPEN', 'CLICK', 'UNSUBSCRIBE')),
    CONSTRAINT ck_campaign_safety_link_target_scheme CHECK (target_url ~* '^https?://')
);

CREATE INDEX ix_campaign_safety_links_message
    ON campaign_safety_links (safety_message_id, id);

CREATE TABLE campaign_safety_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES campaign_safety_runs(id) ON DELETE CASCADE,
    safety_message_id UUID NOT NULL REFERENCES campaign_safety_messages(id) ON DELETE CASCADE,
    safety_link_id UUID REFERENCES campaign_safety_links(id) ON DELETE SET NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    classification VARCHAR(30),
    classification_reason VARCHAR(255),
    diagnostic_code VARCHAR(80),
    CONSTRAINT ck_campaign_safety_event_type CHECK (
        event_type IN ('OPEN', 'CLICK', 'UNSUBSCRIBE', 'REPLY', 'AUTO_REPLY', 'BOUNCE')
    )
);

CREATE INDEX ix_campaign_safety_events_message_time
    ON campaign_safety_events (safety_message_id, occurred_at DESC, id);

CREATE TABLE mailbox_sync_cursors (
    mailbox_account_id UUID NOT NULL REFERENCES mailbox_accounts(id) ON DELETE CASCADE,
    folder_name VARCHAR(255) NOT NULL,
    uid_validity BIGINT NOT NULL,
    last_remote_uid BIGINT NOT NULL DEFAULT 0,
    lease_hash BYTEA,
    lease_expires_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,
    last_error_category VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (mailbox_account_id, folder_name),
    CONSTRAINT ck_mailbox_sync_cursor_uid_validity CHECK (uid_validity >= 0),
    CONSTRAINT ck_mailbox_sync_cursor_uid CHECK (last_remote_uid >= 0),
    CONSTRAINT ck_mailbox_sync_cursor_lease CHECK (
        (lease_hash IS NULL AND lease_expires_at IS NULL)
        OR (octet_length(lease_hash) = 32 AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_mailbox_sync_cursors_due
    ON mailbox_sync_cursors (lease_expires_at, mailbox_account_id, folder_name);

CREATE TABLE mailbox_inbound_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mailbox_account_id UUID NOT NULL REFERENCES mailbox_accounts(id) ON DELETE CASCADE,
    folder_name VARCHAR(255) NOT NULL,
    uid_validity BIGINT NOT NULL,
    remote_uid BIGINT NOT NULL,
    inbound_type VARCHAR(20) NOT NULL,
    referenced_message_id VARCHAR(255),
    campaign_recipient_id UUID REFERENCES campaign_recipients(id) ON DELETE SET NULL,
    safety_message_id UUID REFERENCES campaign_safety_messages(id) ON DELETE SET NULL,
    diagnostic_code VARCHAR(80),
    permanent BOOLEAN,
    received_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mailbox_inbound_event_uid UNIQUE (mailbox_account_id, folder_name, uid_validity, remote_uid),
    CONSTRAINT ck_mailbox_inbound_event_type CHECK (inbound_type IN ('REPLY', 'AUTO_REPLY', 'BOUNCE', 'UNMATCHED')),
    CONSTRAINT ck_mailbox_inbound_event_uid CHECK (uid_validity >= 0 AND remote_uid >= 0),
    CONSTRAINT ck_mailbox_inbound_event_match CHECK (
        NOT (campaign_recipient_id IS NOT NULL AND safety_message_id IS NOT NULL)
    )
);

CREATE INDEX ix_mailbox_inbound_events_message_id
    ON mailbox_inbound_events (referenced_message_id)
    WHERE referenced_message_id IS NOT NULL;
CREATE INDEX ix_mailbox_inbound_events_recipient
    ON mailbox_inbound_events (campaign_recipient_id, created_at DESC)
    WHERE campaign_recipient_id IS NOT NULL;
