-- Administrative ownership only: original delivery states and evidence remain untouched.
ALTER TABLE campaign_safety_attempts ADD COLUMN smtp_started_at TIMESTAMPTZ;

ALTER TABLE campaign_recipients ADD CONSTRAINT uk_campaign_recipient_campaign_id UNIQUE(campaign_id,id);
ALTER TABLE email_template_versions ADD CONSTRAINT uk_template_version_template_id UNIQUE(template_id,id);

CREATE TABLE campaign_template_migrations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL CHECK(idempotency_key ~ '^[A-Za-z0-9_-]{1,128}$'),
    request_digest BYTEA NOT NULL CHECK(octet_length(request_digest)=32),
    membership_digest BYTEA NOT NULL CHECK(octet_length(membership_digest)=32),
    source_campaign_id UUID NOT NULL REFERENCES campaigns(id),
    target_campaign_id UUID REFERENCES campaigns(id),
    target_template_id UUID NOT NULL REFERENCES email_templates(id),
    target_template_version_id UUID NOT NULL,
    source_lock_version BIGINT NOT NULL CHECK(source_lock_version>=0),
    target_template_lock_version BIGINT NOT NULL CHECK(target_template_lock_version>=0),
    selected_count INTEGER NOT NULL CHECK(selected_count BETWEEN 0 AND 120),
    migrated_count INTEGER NOT NULL CHECK(migrated_count BETWEEN 0 AND selected_count),
    status VARCHAR(20) NOT NULL CHECK(status IN ('MIGRATED','CONFIG_UPDATED','BLOCKED','NO_MAIL')),
    reasons JSONB NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(reasons)='array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(actor_id,idempotency_key),
    UNIQUE(id,source_campaign_id,target_campaign_id),
    FOREIGN KEY(target_template_id,target_template_version_id) REFERENCES email_template_versions(template_id,id),
    CHECK ((status='MIGRATED' AND target_campaign_id IS NOT NULL AND migrated_count>0
            AND migrated_count=selected_count AND target_campaign_id<>source_campaign_id)
        OR (status<>'MIGRATED' AND target_campaign_id IS NULL AND migrated_count=0)),
    CHECK(status<>'CONFIG_UPDATED' OR selected_count=0)
);
CREATE INDEX ix_template_migration_source ON campaign_template_migrations(source_campaign_id,created_at,id);
CREATE INDEX ix_template_migration_target ON campaign_template_migrations(target_campaign_id);
CREATE INDEX ix_template_migration_template_version ON campaign_template_migrations(target_template_id,target_template_version_id);

CREATE TABLE campaign_recipient_transfers (
    migration_id UUID NOT NULL,
    source_campaign_id UUID NOT NULL,
    source_recipient_id UUID NOT NULL UNIQUE,
    target_campaign_id UUID NOT NULL,
    target_recipient_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(migration_id,source_recipient_id),
    FOREIGN KEY(migration_id,source_campaign_id,target_campaign_id)
        REFERENCES campaign_template_migrations(id,source_campaign_id,target_campaign_id),
    FOREIGN KEY(source_campaign_id,source_recipient_id) REFERENCES campaign_recipients(campaign_id,id),
    FOREIGN KEY(target_campaign_id,target_recipient_id) REFERENCES campaign_recipients(campaign_id,id),
    CHECK(source_campaign_id<>target_campaign_id AND source_recipient_id<>target_recipient_id)
);
CREATE INDEX ix_recipient_transfer_source_campaign ON campaign_recipient_transfers(source_campaign_id,source_recipient_id);
CREATE INDEX ix_recipient_transfer_target_campaign ON campaign_recipient_transfers(target_campaign_id,target_recipient_id);
COMMENT ON TABLE campaign_recipient_transfers IS 'TRANSFERRED is administrative ownership, never a rewrite of historical delivery status. No cascading history deletion.';
