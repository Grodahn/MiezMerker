-- #92 follow-up: lookup aid for SYSADMIN audit rows by affected account.
CREATE INDEX IF NOT EXISTS idx_sysadmin_org_audit_target
    ON sysadmin_organization_audit (target_user_id, created_at);
