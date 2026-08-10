ALTER TABLE campaigns
    ADD COLUMN generation_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN generation_provider VARCHAR(80),
    ADD COLUMN generation_model VARCHAR(120),
    ADD COLUMN generation_job_id UUID,
    ADD COLUMN generation_requested_at TIMESTAMPTZ,
    ADD COLUMN generation_completed_at TIMESTAMPTZ,
    ADD COLUMN generation_error_summary VARCHAR(1000),
    ADD CONSTRAINT ck_campaign_generation_status CHECK (
        generation_status IN (
            'NOT_REQUESTED', 'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIALLY_FAILED', 'FAILED'
        )
    ),
    ADD CONSTRAINT ck_campaign_generation_timestamps CHECK (
        generation_completed_at IS NULL OR generation_requested_at IS NOT NULL
    );

CREATE INDEX ix_campaigns_generation_status
    ON campaigns (generation_status, generation_requested_at, id)
    WHERE generation_status IN ('QUEUED', 'RUNNING');

ALTER TABLE campaign_recipients
    ADD COLUMN personalization_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN personalization_rationale VARCHAR(2000),
    ADD COLUMN personalization_error_code VARCHAR(80),
    ADD COLUMN personalization_error_message VARCHAR(500),
    ADD COLUMN personalization_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN personalized_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_campaign_recipient_personalization_status CHECK (
        personalization_status IN ('PENDING', 'QUEUED', 'RUNNING', 'GENERATED', 'FAILED')
    ),
    ADD CONSTRAINT ck_campaign_recipient_personalization_attempts CHECK (
        personalization_attempts >= 0
    ),
    ADD CONSTRAINT ck_campaign_recipient_personalized_at CHECK (
        personalization_status <> 'GENERATED' OR personalized_at IS NOT NULL
    );

CREATE INDEX ix_campaign_recipients_personalization_status
    ON campaign_recipients (campaign_id, personalization_status, id);

