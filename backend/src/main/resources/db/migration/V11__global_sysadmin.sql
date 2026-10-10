-- No migrated account receives a global role. Tenant roles remain unchanged.
CREATE TABLE app_user_system_roles (
    user_id UUID NOT NULL REFERENCES app_users(id),
    role VARCHAR(16) NOT NULL CHECK (role = 'SYSADMIN'),
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- Append-only operator ledger. Retain operation IDs even after revocation.
CREATE TABLE app_user_system_role_audit (
    operation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    role VARCHAR(16) NOT NULL CHECK (role = 'SYSADMIN'),
    action VARCHAR(16) NOT NULL CHECK (action IN ('GRANT', 'REVOKE')),
    operator VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_system_role_audit_user ON app_user_system_role_audit(user_id, created_at);
