CREATE TABLE bulk_outreach_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash BYTEA NOT NULL CHECK(octet_length(payload_hash)=32),
    command JSONB NOT NULL, configuration JSONB NOT NULL, request_context JSONB NOT NULL,
    state JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SENDING','BLOCKED','FAILED','CANCELED','COMPLETED')),
    stage VARCHAR(30) NOT NULL DEFAULT 'IMPORT',
    message VARCHAR(500) NOT NULL DEFAULT 'Bulk parent created',
    lock_version BIGINT NOT NULL DEFAULT 0 CHECK(lock_version>=0),
    next_step_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(actor_id,idempotency_key)
);
CREATE INDEX ix_bulk_outreach_ready ON bulk_outreach_runs(next_step_at,created_at,id) WHERE status IN ('ACTIVE','SENDING');

CREATE TABLE bulk_outreach_jobs (
    run_id UUID NOT NULL REFERENCES bulk_outreach_runs(id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES jobs(id),
    kind VARCHAR(12) NOT NULL CHECK(kind IN ('IMPORT','EXTRACTION')),
    ordinal INTEGER NOT NULL, criteria JSONB, budget INTEGER NOT NULL CHECK(budget BETWEEN 1 AND 10000),
    PRIMARY KEY(run_id,kind,ordinal), UNIQUE(run_id,job_id)
);
CREATE TABLE bulk_outreach_papers (
    run_id UUID NOT NULL REFERENCES bulk_outreach_runs(id) ON DELETE CASCADE,
    paper_id UUID NOT NULL REFERENCES papers(id), arxiv_id VARCHAR(100) NOT NULL,
    outcome VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    extraction_job_id UUID REFERENCES jobs(id), extraction_run_id UUID REFERENCES extraction_runs(id),
    no_contact BOOLEAN NOT NULL DEFAULT false, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id,paper_id), UNIQUE(run_id,arxiv_id)
);
CREATE INDEX ix_bulk_paper_history ON bulk_outreach_papers(arxiv_id);
CREATE TABLE bulk_outreach_children (
    run_id UUID NOT NULL REFERENCES bulk_outreach_runs(id) ON DELETE CASCADE,
    campaign_id UUID PRIMARY KEY REFERENCES campaigns(id),
    stage VARCHAR(20) NOT NULL DEFAULT 'REVIEW', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    selected_count INTEGER NOT NULL CHECK(selected_count BETWEEN 1 AND 120),
    smtp_accepted INTEGER NOT NULL DEFAULT 0, bounced_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0, unknown_count INTEGER NOT NULL DEFAULT 0,
    message VARCHAR(500) NOT NULL DEFAULT 'Exact membership frozen',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_bulk_children_parent ON bulk_outreach_children(run_id,created_at,campaign_id);
-- Reservation is a durable ledger, including canceled, blocked and unknown selections.
-- The normal workflow remains the authority for all delivery attempts/nonreplay.
CREATE TABLE bulk_outreach_reservations (
    email_hmac BYTEA PRIMARY KEY CHECK(octet_length(email_hmac)=32),
    run_id UUID NOT NULL REFERENCES bulk_outreach_runs(id) ON DELETE CASCADE,
    campaign_id UUID NOT NULL REFERENCES bulk_outreach_children(campaign_id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL UNIQUE REFERENCES campaign_recipients(id),
    outcome VARCHAR(30) NOT NULL DEFAULT 'QUEUED', updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
