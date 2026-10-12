CREATE TABLE personalization_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    version BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    provider TEXT NOT NULL CHECK (provider IN ('anthropic', 'openai')),
    model TEXT NOT NULL,
    api_origin TEXT NOT NULL,
    auth_scheme TEXT NOT NULL CHECK (auth_scheme IN ('bearer', 'x-api-key')),
    encrypted_key TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Generation IDs are not rows in jobs; snapshots live independently and are immutable.
CREATE TABLE personalization_job_snapshots (
    job_id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    api_origin TEXT NOT NULL,
    auth_scheme TEXT NOT NULL,
    encrypted_key TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
