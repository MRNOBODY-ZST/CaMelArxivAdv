CREATE TABLE outreach_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash) = 32),
    name VARCHAR(200) NOT NULL,
    target_mode VARCHAR(10) NOT NULL CHECK (target_mode IN ('EMAILS', 'PAPERS')),
    target_count INTEGER NOT NULL,
    criteria JSONB NOT NULL,
    purpose TEXT NOT NULL,
    request_context JSONB NOT NULL,
    smtp_account_id UUID NOT NULL REFERENCES smtp_accounts(id),
    mailbox_account_id UUID NOT NULL REFERENCES mailbox_accounts(id),
    template_id UUID NOT NULL REFERENCES email_templates(id),
    template_version_id UUID NOT NULL REFERENCES email_template_versions(id),
    template_version INTEGER NOT NULL,
    smtp_lock_version BIGINT NOT NULL,
    mailbox_lock_version BIGINT NOT NULL,
    from_email VARCHAR(320) NOT NULL,
    from_name VARCHAR(160) NOT NULL,
    reply_to VARCHAR(320) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN
        ('ACTIVE', 'SENDING', 'BLOCKED', 'FAILED', 'CANCELED', 'COMPLETED')),
    stage VARCHAR(30) NOT NULL DEFAULT 'IMPORT' CHECK (stage IN
        ('IMPORT', 'WAIT_IMPORT', 'EXTRACT', 'WAIT_EXTRACTION', 'PREPARE', 'REVIEW', 'APPROVE', 'START', 'SENDING', 'DONE')),
    message VARCHAR(500) NOT NULL DEFAULT 'Run created',
    paper_ids UUID[] NOT NULL DEFAULT ARRAY[]::UUID[],
    paper_count INTEGER NOT NULL DEFAULT 0 CHECK (paper_count BETWEEN 0 AND 100),
    extracted_count INTEGER NOT NULL DEFAULT 0 CHECK (extracted_count BETWEEN 0 AND 100),
    eligible_count INTEGER NOT NULL DEFAULT 0 CHECK (eligible_count >= 0),
    selected_count INTEGER NOT NULL DEFAULT 0 CHECK (selected_count BETWEEN 0 AND 120),
    smtp_accepted INTEGER NOT NULL DEFAULT 0 CHECK (smtp_accepted >= 0),
    failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    unknown_count INTEGER NOT NULL DEFAULT 0 CHECK (unknown_count >= 0),
    campaign_id UUID UNIQUE REFERENCES campaigns(id),
    import_job_id UUID REFERENCES jobs(id),
    extraction_job_id UUID REFERENCES jobs(id),
    lock_version BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    next_step_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (actor_id, idempotency_key),
    CHECK ((target_mode = 'EMAILS' AND target_count BETWEEN 1 AND 120)
        OR (target_mode = 'PAPERS' AND target_count BETWEEN 1 AND 100)),
    CHECK (cardinality(paper_ids) = paper_count)
);

CREATE INDEX ix_outreach_runs_ready ON outreach_runs(next_step_at, created_at, id)
    WHERE status IN ('ACTIVE', 'SENDING');

CREATE TABLE outreach_content_reviews (
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL UNIQUE REFERENCES campaign_recipients(id) ON DELETE CASCADE,
    policy_version TEXT NOT NULL,
    source_hash BYTEA NOT NULL CHECK (octet_length(source_hash) = 32),
    content_hash BYTEA NOT NULL CHECK (octet_length(content_hash) = 32),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_outreach_content_reviews_campaign ON outreach_content_reviews(campaign_id);
