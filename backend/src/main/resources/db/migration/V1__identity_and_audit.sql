CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    system_role BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_roles_code UNIQUE (code)
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(80) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    force_password_change BOOLEAN NOT NULL DEFAULT true,
    token_version INTEGER NOT NULL DEFAULT 0,
    last_login_at TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED'))
);

CREATE UNIQUE INDEX uk_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));
CREATE INDEX ix_users_status_created_at ON users (status, created_at DESC);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX ix_user_roles_role_id ON user_roles (role_id);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX ix_role_permissions_permission_id ON role_permissions (permission_id);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    created_ip_hash BYTEA,
    user_agent_summary VARCHAR(255),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX ix_refresh_tokens_user_family ON refresh_tokens (user_id, family_id);
CREATE INDEX ix_refresh_tokens_active_expiry ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE login_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    principal_hash BYTEA NOT NULL,
    ip_hash BYTEA,
    succeeded BOOLEAN NOT NULL,
    failure_reason VARCHAR(80),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_login_attempts_principal_time ON login_attempts (principal_hash, attempted_at DESC);
CREATE INDEX ix_login_attempts_ip_time ON login_attempts (ip_hash, attempted_at DESC);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_hash BYTEA,
    user_agent_summary VARCHAR(255),
    trace_id VARCHAR(64) NOT NULL,
    before_summary JSONB,
    after_summary JSONB,
    result VARCHAR(20) NOT NULL,
    error_type VARCHAR(80),
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

CREATE INDEX ix_audit_logs_time ON audit_logs (occurred_at DESC);
CREATE INDEX ix_audit_logs_actor_time ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_resource ON audit_logs (resource_type, resource_id, occurred_at DESC);

