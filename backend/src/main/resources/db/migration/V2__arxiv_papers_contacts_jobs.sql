CREATE TABLE arxiv_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id VARCHAR(40) NOT NULL,
    group_name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    source_updated_at TIMESTAMPTZ,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_arxiv_groups_group_id UNIQUE (group_id)
);

CREATE TABLE arxiv_archives (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_ref_id UUID NOT NULL REFERENCES arxiv_groups(id),
    archive_id VARCHAR(40) NOT NULL,
    archive_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    source_updated_at TIMESTAMPTZ,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_arxiv_archives_archive_id UNIQUE (archive_id)
);

CREATE INDEX ix_arxiv_archives_group ON arxiv_archives (group_ref_id, active);

CREATE TABLE arxiv_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_ref_id UUID NOT NULL REFERENCES arxiv_groups(id),
    archive_ref_id UUID REFERENCES arxiv_archives(id),
    group_id VARCHAR(40) NOT NULL,
    group_name VARCHAR(120) NOT NULL,
    archive_id VARCHAR(40),
    archive_name VARCHAR(160),
    category_id VARCHAR(80) NOT NULL,
    category_name VARCHAR(200) NOT NULL,
    description TEXT,
    is_alias BOOLEAN NOT NULL DEFAULT false,
    alias_target VARCHAR(80),
    active BOOLEAN NOT NULL DEFAULT true,
    source_updated_at TIMESTAMPTZ,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_arxiv_categories_category_id UNIQUE (category_id),
    CONSTRAINT ck_arxiv_category_alias CHECK (
        (is_alias = false AND alias_target IS NULL)
        OR (is_alias = true AND alias_target IS NOT NULL)
    )
);

CREATE INDEX ix_arxiv_categories_tree ON arxiv_categories (group_ref_id, archive_ref_id, active);
CREATE INDEX ix_arxiv_categories_alias_target ON arxiv_categories (alias_target) WHERE is_alias = true;

CREATE TABLE papers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    arxiv_id VARCHAR(40) NOT NULL,
    title TEXT NOT NULL,
    abstract_text TEXT NOT NULL,
    primary_category_id UUID REFERENCES arxiv_categories(id) ON DELETE SET NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    doi VARCHAR(255),
    journal_reference TEXT,
    comment_text TEXT,
    license_url TEXT,
    pdf_url TEXT NOT NULL,
    source_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    source_format VARCHAR(50),
    version_count INTEGER NOT NULL DEFAULT 1,
    metadata_raw JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata_source_updated_at TIMESTAMPTZ,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_extracted_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_papers_arxiv_id UNIQUE (arxiv_id),
    CONSTRAINT ck_papers_source_status CHECK (source_status IN (
        'UNKNOWN', 'AVAILABLE', 'UNAVAILABLE', 'DOWNLOADED', 'SECURITY_REJECTED',
        'PARSED', 'PARTIALLY_PARSED', 'PARSE_FAILED'
    )),
    CONSTRAINT ck_papers_version_count CHECK (version_count >= 1),
    CONSTRAINT ck_papers_dates CHECK (updated_at >= submitted_at)
);

CREATE INDEX ix_papers_submitted_at ON papers (submitted_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_papers_updated_at ON papers (updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_papers_primary_category ON papers (primary_category_id, submitted_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_papers_source_status ON papers (source_status, last_extracted_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_papers_doi_present ON papers (submitted_at DESC) WHERE doi IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE paper_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    size_bytes BIGINT,
    source_format VARCHAR(50),
    CONSTRAINT uk_paper_versions_number UNIQUE (paper_id, version_number),
    CONSTRAINT ck_paper_versions_number CHECK (version_number >= 1),
    CONSTRAINT ck_paper_versions_size CHECK (size_bytes IS NULL OR size_bytes >= 0)
);

CREATE TABLE paper_categories (
    paper_id UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES arxiv_categories(id),
    relation_type VARCHAR(20) NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (paper_id, category_id, relation_type),
    CONSTRAINT ck_paper_category_type CHECK (relation_type IN ('PRIMARY', 'CROSS_LIST', 'ALIAS'))
);

CREATE INDEX ix_paper_categories_category_type ON paper_categories (category_id, relation_type, paper_id);

CREATE TABLE authors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    normalized_name VARCHAR(300) NOT NULL,
    display_name VARCHAR(300) NOT NULL,
    orcid VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_authors_normalized_name ON authors (normalized_name);
CREATE UNIQUE INDEX uk_authors_orcid ON authors (orcid) WHERE orcid IS NOT NULL;

CREATE TABLE paper_authors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES authors(id),
    author_order INTEGER NOT NULL,
    corresponding_author BOOLEAN NOT NULL DEFAULT false,
    raw_name VARCHAR(300) NOT NULL,
    affiliation_text TEXT,
    affiliation_data JSONB NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT uk_paper_authors_order UNIQUE (paper_id, author_order),
    CONSTRAINT uk_paper_authors_author UNIQUE (paper_id, author_id),
    CONSTRAINT ck_paper_authors_order CHECK (author_order >= 1)
);

CREATE INDEX ix_paper_authors_author ON paper_authors (author_id, paper_id);

CREATE TABLE contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_ciphertext BYTEA NOT NULL,
    email_nonce BYTEA NOT NULL,
    email_hmac BYTEA NOT NULL,
    email_domain VARCHAR(255) NOT NULL,
    display_ciphertext BYTEA NOT NULL,
    syntax_valid BOOLEAN NOT NULL,
    example_address BOOLEAN NOT NULL DEFAULT false,
    suppression_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    first_extracted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_extracted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_contacts_email_hmac UNIQUE (email_hmac),
    CONSTRAINT ck_contacts_suppression_status CHECK (suppression_status IN ('ACTIVE', 'SUPPRESSED', 'UNSUBSCRIBED', 'DELETED'))
);

CREATE INDEX ix_contacts_domain ON contacts (email_domain, suppression_status);
CREATE INDEX ix_contacts_last_extracted ON contacts (last_extracted_at DESC);

CREATE TABLE extraction_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    job_id UUID,
    parser_version VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    document_class VARCHAR(100),
    source_format VARCHAR(50),
    files_inspected INTEGER NOT NULL DEFAULT 0,
    contacts_found INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_summary VARCHAR(500),
    CONSTRAINT ck_extraction_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'SECURITY_REJECTED', 'SOURCE_UNAVAILABLE')),
    CONSTRAINT ck_extraction_counts CHECK (files_inspected >= 0 AND contacts_found >= 0),
    CONSTRAINT ck_extraction_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX ix_extraction_runs_paper_time ON extraction_runs (paper_id, started_at DESC);
CREATE INDEX ix_extraction_runs_status_time ON extraction_runs (status, started_at DESC);

CREATE TABLE paper_author_contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_author_id UUID REFERENCES paper_authors(id) ON DELETE CASCADE,
    paper_id UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES contacts(id),
    extraction_run_id UUID NOT NULL REFERENCES extraction_runs(id) ON DELETE CASCADE,
    confidence VARCHAR(20) NOT NULL,
    corresponding_author BOOLEAN NOT NULL DEFAULT false,
    human_verified BOOLEAN NOT NULL DEFAULT false,
    verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_paper_contact_run UNIQUE (paper_id, contact_id, extraction_run_id),
    CONSTRAINT ck_contact_confidence CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNMAPPED')),
    CONSTRAINT ck_contact_verification CHECK (verification_status IN ('UNVERIFIED', 'CONFIRMED', 'REJECTED'))
);

CREATE INDEX ix_paper_author_contacts_paper ON paper_author_contacts (paper_id, confidence);
CREATE INDEX ix_paper_author_contacts_contact ON paper_author_contacts (contact_id, paper_id);

CREATE TABLE extraction_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_author_contact_id UUID NOT NULL REFERENCES paper_author_contacts(id) ON DELETE CASCADE,
    source_relative_path VARCHAR(500) NOT NULL,
    rule_name VARCHAR(120) NOT NULL,
    line_number INTEGER,
    logical_location VARCHAR(120),
    masked_context VARCHAR(600) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_extraction_evidence_line CHECK (line_number IS NULL OR line_number >= 1)
);

CREATE INDEX ix_extraction_evidence_mapping ON extraction_evidence (paper_author_contact_id);

CREATE TABLE saved_searches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    criteria JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_saved_search_owner_name UNIQUE (owner_user_id, name)
);

CREATE TABLE jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(60) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key VARCHAR(160) NOT NULL,
    total_count BIGINT NOT NULL DEFAULT 0,
    processed_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    skipped_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    current_stage VARCHAR(80),
    progress_percent NUMERIC(5,2) NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_summary VARCHAR(1000),
    pause_requested BOOLEAN NOT NULL DEFAULT false,
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_jobs_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_jobs_status CHECK (status IN ('PENDING', 'QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_jobs_counts CHECK (
        total_count >= 0 AND processed_count >= 0 AND success_count >= 0
        AND skipped_count >= 0 AND failed_count >= 0 AND retry_count >= 0
    ),
    CONSTRAINT ck_jobs_progress CHECK (progress_percent >= 0 AND progress_percent <= 100)
);

ALTER TABLE extraction_runs
    ADD CONSTRAINT fk_extraction_runs_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE SET NULL;

CREATE INDEX ix_jobs_status_created_at ON jobs (status, created_at DESC);
CREATE INDEX ix_jobs_active_heartbeat ON jobs (heartbeat_at)
    WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED');

CREATE TABLE job_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    external_key VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    result_summary JSONB,
    error_code VARCHAR(80),
    error_summary VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_job_items_external_key UNIQUE (job_id, external_key),
    CONSTRAINT ck_job_items_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_job_items_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX ix_job_items_status ON job_items (job_id, status, id);

CREATE TABLE job_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    stage VARCHAR(80),
    message VARCHAR(1000),
    details JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_job_events_job_time ON job_events (job_id, occurred_at, id);

CREATE TABLE job_errors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    job_item_id UUID REFERENCES job_items(id) ON DELETE CASCADE,
    category VARCHAR(80) NOT NULL,
    code VARCHAR(80) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    retryable BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_job_errors_job_category ON job_errors (job_id, category, occurred_at DESC);

CREATE TABLE processed_messages (
    message_id UUID PRIMARY KEY,
    consumer_name VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    result VARCHAR(20) NOT NULL,
    CONSTRAINT uk_processed_messages_consumer_key UNIQUE (consumer_name, idempotency_key),
    CONSTRAINT ck_processed_messages_result CHECK (result IN ('SUCCEEDED', 'REJECTED', 'FAILED'))
);

CREATE TABLE worker_heartbeats (
    worker_id VARCHAR(120) PRIMARY KEY,
    worker_type VARCHAR(40) NOT NULL,
    version VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_worker_status CHECK (status IN ('IDLE', 'BUSY', 'DRAINING', 'UNHEALTHY'))
);

CREATE INDEX ix_worker_heartbeats_seen ON worker_heartbeats (worker_type, last_seen_at DESC);

