ALTER TABLE extraction_runs
    ADD COLUMN message_id UUID,
    ADD COLUMN idempotency_key VARCHAR(200),
    ADD COLUMN archive_size_bytes BIGINT,
    ADD COLUMN extracted_size_bytes BIGINT,
    ADD COLUMN cleanup_confirmed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN cleanup_confirmed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_extraction_run_sizes CHECK (
        (archive_size_bytes IS NULL OR archive_size_bytes >= 0)
        AND (extracted_size_bytes IS NULL OR extracted_size_bytes >= 0)
    ),
    ADD CONSTRAINT ck_extraction_run_cleanup CHECK (
        (cleanup_confirmed = false AND cleanup_confirmed_at IS NULL)
        OR (cleanup_confirmed = true AND cleanup_confirmed_at IS NOT NULL)
    );

ALTER TABLE contacts
    ADD COLUMN display_nonce BYTEA;

CREATE UNIQUE INDEX uk_extraction_runs_message
    ON extraction_runs (message_id)
    WHERE message_id IS NOT NULL;

CREATE UNIQUE INDEX uk_extraction_runs_job_paper
    ON extraction_runs (job_id, paper_id)
    WHERE job_id IS NOT NULL;

CREATE INDEX ix_extraction_runs_job_status
    ON extraction_runs (job_id, status, paper_id);

CREATE INDEX ix_paper_author_contacts_latest
    ON paper_author_contacts (paper_id, contact_id, created_at DESC);

CREATE INDEX ix_contacts_active_domain_time
    ON contacts (email_domain, last_extracted_at DESC, id)
    WHERE deleted_at IS NULL;

ALTER TABLE paper_author_contacts
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_paper_author_contacts_version CHECK (version >= 0);
