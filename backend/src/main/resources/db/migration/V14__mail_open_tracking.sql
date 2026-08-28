CREATE TABLE mail_send_records (
    id UUID PRIMARY KEY,
    source VARCHAR(24) NOT NULL,
    recipient_masked VARCHAR(320) NOT NULL,
    subject VARCHAR(998) NOT NULL,
    smtp_account_id UUID REFERENCES smtp_accounts(id) ON DELETE SET NULL,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SENDING',
    failure_category VARCHAR(80),
    tracking_enabled BOOLEAN NOT NULL DEFAULT false,
    token_hash BYTEA,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    tracking_expires_at TIMESTAMPTZ,
    CONSTRAINT ck_mail_send_source CHECK (source IN ('SMTP_DIAGNOSTIC', 'TEMPLATE_TEST')),
    CONSTRAINT ck_mail_send_status CHECK (status IN ('SENDING', 'SMTP_ACCEPTED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_mail_send_tracking CHECK (
        (tracking_enabled AND token_hash IS NOT NULL AND octet_length(token_hash) = 32
            AND tracking_expires_at IS NOT NULL AND tracking_expires_at > created_at)
        OR (NOT tracking_enabled AND token_hash IS NULL AND tracking_expires_at IS NULL)
    ),
    CONSTRAINT ck_mail_send_completion CHECK (
        (status = 'SENDING' AND completed_at IS NULL AND failure_category IS NULL)
        OR (status = 'SMTP_ACCEPTED' AND completed_at IS NOT NULL AND failure_category IS NULL)
        OR (status IN ('FAILED', 'UNKNOWN') AND completed_at IS NOT NULL AND failure_category IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_mail_send_token_hash ON mail_send_records (token_hash) WHERE token_hash IS NOT NULL;
CREATE INDEX ix_mail_send_created ON mail_send_records (created_at DESC, id DESC);
CREATE INDEX ix_mail_send_smtp_account ON mail_send_records (smtp_account_id);
CREATE INDEX ix_mail_send_actor ON mail_send_records (actor_user_id);

CREATE TABLE mail_open_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    record_id UUID NOT NULL REFERENCES mail_send_records(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    classification VARCHAR(20) NOT NULL,
    reason VARCHAR(80) NOT NULL,
    fingerprint_hash BYTEA NOT NULL,
    minute_bucket BIGINT NOT NULL,
    CONSTRAINT ck_mail_open_classification CHECK (classification IN ('UNCLASSIFIED', 'PREFETCH', 'IMAGE_PROXY', 'BOT')),
    CONSTRAINT ck_mail_open_fingerprint CHECK (octet_length(fingerprint_hash) = 32),
    CONSTRAINT uk_mail_open_minute UNIQUE (record_id, fingerprint_hash, minute_bucket)
);

CREATE INDEX ix_mail_open_record_time ON mail_open_events (record_id, occurred_at DESC, id DESC);

COMMENT ON TABLE mail_send_records IS 'Diagnostic/template test sends only; never campaign delivery data.';
COMMENT ON TABLE mail_open_events IS 'Deduplicated image-load observations, not proof of human reading.';
