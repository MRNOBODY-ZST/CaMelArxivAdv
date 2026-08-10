CREATE TABLE arxiv_category_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_version VARCHAR(80) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_url VARCHAR(500) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    item_count INTEGER NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uk_arxiv_snapshot_version UNIQUE (snapshot_version),
    CONSTRAINT uk_arxiv_snapshot_hash UNIQUE (payload_sha256),
    CONSTRAINT ck_arxiv_snapshot_source CHECK (source_type IN ('OFFLINE_SNAPSHOT', 'OAI_LIST_SETS')),
    CONSTRAINT ck_arxiv_snapshot_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_arxiv_snapshot_item_count CHECK (item_count > 0),
    CONSTRAINT ck_arxiv_snapshot_applied CHECK (active = false OR applied_at IS NOT NULL)
);

CREATE UNIQUE INDEX uk_arxiv_snapshot_active
    ON arxiv_category_snapshots (active)
    WHERE active = true;

ALTER TABLE arxiv_categories
    ADD COLUMN snapshot_id UUID REFERENCES arxiv_category_snapshots(id) ON DELETE SET NULL;

CREATE INDEX ix_arxiv_categories_snapshot ON arxiv_categories (snapshot_id, active, category_id);

CREATE TABLE arxiv_sync_cursors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cursor_key VARCHAR(200) NOT NULL,
    sync_type VARCHAR(30) NOT NULL,
    set_spec VARCHAR(160),
    from_datestamp DATE,
    resumption_token TEXT,
    token_received_at TIMESTAMPTZ,
    last_response_date TIMESTAMPTZ,
    last_completed_datestamp DATE,
    last_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_arxiv_sync_cursor_key UNIQUE (cursor_key),
    CONSTRAINT ck_arxiv_sync_type CHECK (sync_type IN ('OAI_METADATA', 'OAI_LIST_SETS')),
    CONSTRAINT ck_arxiv_sync_version CHECK (version >= 0),
    CONSTRAINT ck_arxiv_sync_token_time CHECK (
        (resumption_token IS NULL AND token_received_at IS NULL)
        OR (resumption_token IS NOT NULL AND token_received_at IS NOT NULL)
    )
);

CREATE INDEX ix_arxiv_sync_cursors_active
    ON arxiv_sync_cursors (sync_type, updated_at DESC)
    WHERE resumption_token IS NOT NULL;

ALTER TABLE saved_searches
    ADD COLUMN criteria_hash CHAR(64);

UPDATE saved_searches
SET criteria_hash = md5(criteria::text) || md5(criteria::text || ':phase3')
WHERE criteria_hash IS NULL;

ALTER TABLE saved_searches
    ALTER COLUMN criteria_hash SET NOT NULL,
    ADD CONSTRAINT ck_saved_search_criteria_hash CHECK (criteria_hash ~ '^[0-9a-f]{64}$');

CREATE INDEX ix_saved_searches_owner_updated
    ON saved_searches (owner_user_id, updated_at DESC, id);

CREATE INDEX ix_saved_searches_owner_hash
    ON saved_searches (owner_user_id, criteria_hash);

ALTER TABLE jobs
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN parent_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL,
    ADD COLUMN root_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL,
    ADD COLUMN checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN control_requested_at TIMESTAMPTZ,
    ADD COLUMN last_message_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_jobs_version CHECK (version >= 0),
    ADD CONSTRAINT ck_jobs_lineage CHECK (parent_job_id IS NULL OR parent_job_id <> id),
    ADD CONSTRAINT ck_jobs_terminal_time CHECK (
        (status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELED') AND ended_at IS NOT NULL)
        OR status NOT IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELED')
    );

CREATE INDEX ix_jobs_creator_status_created
    ON jobs (created_by, status, created_at DESC, id);

CREATE INDEX ix_jobs_root_created
    ON jobs (root_job_id, created_at, id)
    WHERE root_job_id IS NOT NULL;

CREATE INDEX ix_job_events_replay ON job_events (job_id, id);

CREATE TABLE paper_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    metadata_source VARCHAR(30) NOT NULL,
    source_datestamp TIMESTAMPTZ,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_paper_import_job UNIQUE (paper_id, job_id),
    CONSTRAINT ck_paper_import_source CHECK (metadata_source IN ('LEGACY_API', 'OAI_PMH'))
);

CREATE INDEX ix_paper_imports_job ON paper_imports (job_id, imported_at, paper_id);
CREATE INDEX ix_paper_imports_paper ON paper_imports (paper_id, imported_at DESC);

ALTER TABLE papers
    ADD COLUMN search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A')
        || setweight(to_tsvector('simple', coalesce(abstract_text, '')), 'B')
    ) STORED;

CREATE INDEX ix_papers_search_vector ON papers USING GIN (search_vector);
CREATE INDEX ix_papers_primary_updated
    ON papers (primary_category_id, updated_at DESC, id)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_paper_authors_paper_order ON paper_authors (paper_id, author_order, author_id);
