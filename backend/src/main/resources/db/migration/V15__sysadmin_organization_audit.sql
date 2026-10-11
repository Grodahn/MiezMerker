-- #92 / ADR 0016 decision 1: SYSADMIN organization management audit ledger.
-- Append-only: who changed which organization's metadata or ADMIN membership,
-- when, and the relevant before/after state. Rows are never updated or
-- deleted by application code. CREATE is recorded after the organization row
-- exists, so organization_id is always populated. No passwords, keys,
-- session tokens or animal data are stored here.
CREATE TABLE sysadmin_organization_audit (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES app_users (id),
    organization_id UUID NOT NULL REFERENCES organizations (id),
    target_user_id UUID REFERENCES app_users (id),
    action VARCHAR(32) NOT NULL CHECK (action IN ('CREATE', 'RENAME', 'DEACTIVATE',
        'REACTIVATE', 'DELETE_REQUESTED', 'ADMIN_GRANTED', 'ADMIN_REVOKED')),
    before_state VARCHAR(1000) NOT NULL DEFAULT '',
    after_state VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_sysadmin_org_audit_org ON sysadmin_organization_audit (organization_id, created_at);
CREATE INDEX idx_sysadmin_org_audit_actor ON sysadmin_organization_audit (actor_user_id, created_at);
