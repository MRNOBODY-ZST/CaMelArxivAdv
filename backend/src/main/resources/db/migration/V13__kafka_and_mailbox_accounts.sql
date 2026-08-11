ALTER TABLE outbox_messages
    RENAME COLUMN exchange_name TO topic_name;

UPDATE outbox_messages
SET topic_name = CASE topic_name
    WHEN 'arxiv.jobs' THEN 'camel.arxiv.jobs.v1'
    WHEN 'arxiv.results' THEN 'camel.arxiv.results.v1'
    WHEN 'mail.jobs' THEN 'camel.mail.personalization.jobs.v1'
    WHEN 'mail.results' THEN 'camel.mail.personalization.results.v1'
    ELSE topic_name
END;

CREATE INDEX ix_outbox_topic_pending
    ON outbox_messages (topic_name, available_at, id)
    WHERE published_at IS NULL;

CREATE TABLE mailbox_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    protocol VARCHAR(10) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    tls_mode VARCHAR(30) NOT NULL,
    username VARCHAR(255) NOT NULL,
    password_ciphertext BYTEA NOT NULL,
    password_nonce BYTEA NOT NULL,
    folder_name VARCHAR(255) NOT NULL DEFAULT 'INBOX',
    enabled BOOLEAN NOT NULL DEFAULT true,
    last_tested_at TIMESTAMPTZ,
    last_test_status VARCHAR(40),
    last_test_error VARCHAR(80),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mailbox_accounts_name UNIQUE (name),
    CONSTRAINT ck_mailbox_protocol CHECK (protocol IN ('IMAP', 'POP3')),
    CONSTRAINT ck_mailbox_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_mailbox_tls_mode CHECK (
        tls_mode IN ('STARTTLS_REQUIRED', 'TLS_IMPLICIT', 'PLAIN_LOCAL_ONLY')
    ),
    CONSTRAINT ck_mailbox_folder CHECK (
        (protocol = 'POP3' AND folder_name = 'INBOX')
        OR (protocol = 'IMAP' AND length(folder_name) BETWEEN 1 AND 255)
    ),
    CONSTRAINT ck_mailbox_lock_version CHECK (lock_version >= 0),
    CONSTRAINT ck_mailbox_secret CHECK (
        octet_length(password_ciphertext) >= 16 AND octet_length(password_nonce) = 12
    )
);

CREATE INDEX ix_mailbox_accounts_enabled
    ON mailbox_accounts (enabled, updated_at DESC, id);

INSERT INTO permissions (code, description) VALUES
    ('mailbox:read', 'Read inbound mailbox account metadata and message headers'),
    ('mailbox:manage', 'Create, update, test, or disable inbound mailbox accounts')
ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.code = 'SUPER_ADMIN'
  AND permission.code IN ('mailbox:read', 'mailbox:manage')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.code = 'OPERATIONS_ADMIN'
  AND permission.code IN ('mailbox:read', 'mailbox:manage')
ON CONFLICT (role_id, permission_id) DO NOTHING;
