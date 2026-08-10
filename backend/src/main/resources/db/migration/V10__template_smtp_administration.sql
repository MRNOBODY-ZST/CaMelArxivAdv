ALTER TABLE email_templates
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uk_email_templates_active_name
    ON email_templates (lower(name))
    WHERE deleted_at IS NULL;

ALTER TABLE smtp_accounts
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE template_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES email_templates(id) ON DELETE CASCADE,
    object_key VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 BYTEA NOT NULL,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_template_assets_object_key UNIQUE (object_key),
    CONSTRAINT ck_template_asset_size CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    CONSTRAINT ck_template_asset_type CHECK (content_type IN ('image/png', 'image/jpeg', 'image/gif', 'image/webp'))
);

CREATE INDEX ix_template_assets_template_created
    ON template_assets (template_id, created_at DESC, id);
