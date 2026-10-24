CREATE TABLE partner_invites (
    email_hmac BYTEA PRIMARY KEY CHECK (octet_length(email_hmac) = 32),
    binding_hash VARCHAR(128) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('REQUESTING', 'READY', 'HELD', 'CLAIMED')),
    encrypted_claim_url BYTEA,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT partner_invites_ready_payload CHECK (
        (state = 'READY' AND encrypted_claim_url IS NOT NULL AND expires_at IS NOT NULL)
        OR (state <> 'READY' AND encrypted_claim_url IS NULL AND expires_at IS NULL)
    )
);
