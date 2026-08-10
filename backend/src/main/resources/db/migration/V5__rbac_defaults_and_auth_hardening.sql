INSERT INTO permissions (code, description) VALUES
    ('user:read', 'Read users'),
    ('user:create', 'Create users'),
    ('user:update', 'Update users and role assignments'),
    ('user:disable', 'Disable or enable users'),
    ('role:read', 'Read roles and permissions'),
    ('role:manage', 'Create and update roles and permission grants'),
    ('paper:read', 'Read papers and metadata'),
    ('paper:import', 'Import papers from arXiv'),
    ('paper:delete', 'Delete paper data'),
    ('contact:read_masked', 'Read masked contact email addresses'),
    ('contact:read_full', 'Read complete contact email addresses'),
    ('contact:verify', 'Verify or correct contact evidence'),
    ('contact:export', 'Export permitted contact data'),
    ('job:manage', 'Pause, resume, cancel, or retry jobs'),
    ('template:read', 'Read email templates'),
    ('template:manage', 'Create and update email templates'),
    ('smtp:read', 'Read SMTP account metadata'),
    ('smtp:manage', 'Create, update, test, or disable SMTP accounts'),
    ('campaign:read', 'Read campaigns and delivery statistics'),
    ('campaign:create', 'Create and submit campaigns'),
    ('campaign:approve', 'Approve or reject campaigns'),
    ('campaign:send', 'Schedule or start approved campaigns'),
    ('campaign:pause', 'Pause, resume, or cancel campaigns'),
    ('analytics:read', 'Read analytics dashboards'),
    ('audit:read', 'Read audit logs'),
    ('system:manage', 'Manage system-wide settings')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (code, name, description, system_role) VALUES
    ('SUPER_ADMIN', 'Super Administrator', 'All permissions and system-level configuration', true),
    ('ADMIN', 'Administrator', 'User, data, SMTP, campaign approval, and operational administration', true),
    ('CAMPAIGN_MANAGER', 'Campaign Manager', 'Templates, recipient segments, campaigns, and sending statistics', true),
    ('DATA_ANALYST', 'Data Analyst', 'arXiv discovery, imports, masked contacts, exports, and analytics', true),
    ('VIEWER', 'Viewer', 'Read-only dashboards, papers, and campaign statistics', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = ANY (ARRAY[
    'user:read', 'user:create', 'user:update', 'user:disable', 'role:read',
    'paper:read', 'paper:import', 'paper:delete',
    'contact:read_masked', 'contact:read_full', 'contact:verify', 'contact:export',
    'job:manage', 'template:read', 'template:manage', 'smtp:read', 'smtp:manage',
    'campaign:read', 'campaign:create', 'campaign:approve', 'campaign:send', 'campaign:pause',
    'analytics:read'
])
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = ANY (ARRAY[
    'paper:read', 'contact:read_masked', 'template:read', 'template:manage',
    'campaign:read', 'campaign:create', 'campaign:send', 'campaign:pause', 'analytics:read'
])
WHERE r.code = 'CAMPAIGN_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = ANY (ARRAY[
    'paper:read', 'paper:import', 'contact:read_masked', 'contact:export',
    'job:manage', 'analytics:read'
])
WHERE r.code = 'DATA_ANALYST'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = ANY (ARRAY[
    'paper:read', 'campaign:read', 'analytics:read'
])
WHERE r.code = 'VIEWER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
