-- Only the successful new-command transaction inserts identities. No historical backfill.
CREATE TABLE source_command_authorities (
    job_id uuid PRIMARY KEY REFERENCES jobs(id),
    message_id uuid NOT NULL,
    command_type varchar(50) NOT NULL CHECK (command_type IN ('ARXIV_FETCH_AND_PARSE_SOURCE','ARXIV_REEXTRACT_CONTACTS')),
    key_digest char(64) NOT NULL CHECK (key_digest ~ '^[0-9a-f]{64}$'),
    payload_fingerprint char(64) NOT NULL CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    target_count integer NOT NULL CHECK (target_count BETWEEN 1 AND 100),
    sequence bigint NOT NULL DEFAULT 0 CHECK (sequence BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE source_command_issues (
    job_id uuid NOT NULL REFERENCES source_command_authorities(job_id),
    sequence bigint NOT NULL CHECK (sequence BETWEEN 1 AND 9007199254740991),
    nonce uuid NOT NULL,
    owner_id uuid NOT NULL,
    operation varchar(7) NOT NULL CHECK (operation IN ('BIND','RESERVE')),
    tuple_digest char(64) NOT NULL CHECK (tuple_digest ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz NOT NULL,
    expected_generation bigint NOT NULL CHECK (expected_generation BETWEEN 0 AND 9007199254740991),
    expected_digest char(64) CHECK (expected_digest ~ '^[0-9a-f]{64}$'),
    predecessor_sequence bigint NOT NULL CHECK (predecessor_sequence = sequence - 1),
    issued_generation bigint NOT NULL CHECK (issued_generation BETWEEN 0 AND 9007199254740991),
    issued_digest char(64) NOT NULL CHECK (issued_digest ~ '^[0-9a-f]{64}$'),
    target_index integer,
    attempt_number integer,
    token uuid,
    issued_deadline timestamptz,
    consumption_status varchar(9),
    consumed_generation bigint,
    consumed_digest char(64),
    prefix_success integer NOT NULL CHECK (prefix_success >= 0),
    prefix_skipped integer NOT NULL CHECK (prefix_skipped >= 0),
    prefix_failed integer NOT NULL CHECK (prefix_failed >= 0),
    PRIMARY KEY (job_id, sequence),
    UNIQUE (job_id, nonce),
    CHECK ((operation='BIND' AND consumption_status IS NULL AND consumed_generation IS NULL AND consumed_digest IS NULL)
        OR (operation='RESERVE' AND consumption_status IS NOT NULL AND consumption_status='BURNED' AND consumed_generation IS NULL AND consumed_digest IS NULL)
        OR (operation='RESERVE' AND consumption_status IS NOT NULL AND consumption_status='CONFIRMED' AND consumed_generation=issued_generation+1
            AND consumed_generation<=9007199254740991 AND consumed_digest ~ '^[0-9a-f]{64}$'
            AND consumed_generation IS NOT NULL AND consumed_digest IS NOT NULL)),
    CHECK ((operation='BIND' AND sequence=1 AND expected_generation=0 AND issued_generation=0
            AND expected_digest IS NULL AND target_index IS NULL AND attempt_number IS NULL AND token IS NULL AND issued_deadline IS NULL)
        OR (operation='RESERVE' AND expected_digest IS NOT NULL AND target_index IS NOT NULL AND target_index BETWEEN 0 AND 99
            AND attempt_number IS NOT NULL AND attempt_number BETWEEN 1 AND 6 AND token IS NOT NULL AND issued_deadline IS NOT NULL
            AND issued_generation=expected_generation+1 AND prefix_success+prefix_skipped+prefix_failed=target_index))
);
