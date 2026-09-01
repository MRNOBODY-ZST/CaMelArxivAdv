CREATE TABLE mail_click_links (
    id UUID PRIMARY KEY,
    record_id UUID NOT NULL REFERENCES mail_send_records(id) ON DELETE CASCADE,
    target_url VARCHAR(2048) NOT NULL,
    target_url_hash BYTEA NOT NULL,
    label VARCHAR(255),
    position INTEGER NOT NULL,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_mail_click_target UNIQUE (record_id, target_url_hash),
    CONSTRAINT uk_mail_click_token UNIQUE (token_hash),
    CONSTRAINT ck_mail_click_target_hash CHECK (octet_length(target_url_hash) = 32),
    CONSTRAINT ck_mail_click_token_hash CHECK (octet_length(token_hash) = 32),
    CONSTRAINT ck_mail_click_position CHECK (position >= 1),
    CONSTRAINT ck_mail_click_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_mail_click_target_scheme CHECK (target_url ~ '^https?://')
);

CREATE INDEX ix_mail_click_record_position ON mail_click_links (record_id, position, id);

CREATE TABLE mail_click_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_id UUID NOT NULL REFERENCES mail_click_links(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    classification VARCHAR(20) NOT NULL,
    reason VARCHAR(80) NOT NULL,
    fingerprint_hash BYTEA NOT NULL,
    minute_bucket BIGINT NOT NULL,
    CONSTRAINT uk_mail_click_minute UNIQUE (link_id, fingerprint_hash, minute_bucket),
    CONSTRAINT ck_mail_click_classification CHECK (classification IN ('UNCLASSIFIED', 'PREFETCH', 'IMAGE_PROXY', 'BOT')),
    CONSTRAINT ck_mail_click_fingerprint CHECK (octet_length(fingerprint_hash) = 32)
);

CREATE INDEX ix_mail_click_event_link_time ON mail_click_events (link_id, occurred_at DESC, id DESC);

COMMENT ON TABLE mail_click_links IS 'Validated HTTP(S) targets for diagnostic and template test sends only.';
COMMENT ON TABLE mail_click_events IS 'Deduplicated link redirect observations, not proof of a human click.';
