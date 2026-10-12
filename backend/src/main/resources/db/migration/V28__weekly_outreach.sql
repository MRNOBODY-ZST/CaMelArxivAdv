CREATE TABLE outreach_schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES users(id),
    idempotency_key varchar(128) NOT NULL,
    payload_hash bytea NOT NULL,
    name varchar(200) NOT NULL,
    purpose varchar(2000) NOT NULL,
    max_papers integer NOT NULL CHECK(max_papers BETWEEN 1 AND 5000),
    smtp_account_id uuid NOT NULL REFERENCES smtp_accounts(id),
    template_id uuid NOT NULL REFERENCES email_templates(id),
    directions jsonb NOT NULL CHECK(jsonb_typeof(directions)='array' AND jsonb_array_length(directions) BETWEEN 1 AND 8),
    request_context jsonb NOT NULL,
    config_snapshot jsonb NOT NULL,
    accept_deliverability_risk boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    next_run_at timestamptz NOT NULL,
    last_occurrence_at timestamptz,
    last_run_id uuid,
    message varchar(500) NOT NULL DEFAULT 'Weekly incremental research scheduled; seed directions are not verified news',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(actor_id,idempotency_key)
);
CREATE INDEX outreach_schedules_due ON outreach_schedules(next_run_at,id) WHERE enabled;
CREATE TABLE outreach_schedule_occurrences (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id uuid NOT NULL REFERENCES outreach_schedules(id),
    scheduled_for timestamptz NOT NULL,
    occurrence_key varchar(128) NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'RESERVED' CHECK(status IN ('RESERVED','READY','LAUNCHED','BLOCKED')),
    window_from date NOT NULL,
    window_to date NOT NULL CHECK(window_to>=window_from),
    lease_token uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    discovery_attempts integer NOT NULL DEFAULT 0 CHECK(discovery_attempts BETWEEN 0 AND 2),
    discovery jsonb,
    bulk_command jsonb,
    parent_run_id uuid,
    message varchar(500) NOT NULL DEFAULT 'Evidence discovery reserved',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(schedule_id,scheduled_for),
    CHECK(status NOT IN ('READY','LAUNCHED') OR (discovery IS NOT NULL AND bulk_command IS NOT NULL))
);
CREATE UNIQUE INDEX outreach_schedule_one_outstanding ON outreach_schedule_occurrences(schedule_id) WHERE status IN ('RESERVED','READY');
COMMENT ON TABLE outreach_schedule_occurrences IS 'Durable occurrence key and bounded evidence/model usage; no provider secret is stored here. Parent commands are incremental and recoverable without repeating discovery.';
