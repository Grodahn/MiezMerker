-- #96 / ADR 0016: default-deny cross-organization sharing foundation.
-- Every existing organization stays hidden; new organizations default to hidden.
-- Discoverability never authorizes data reads; it only enables directory listing.
ALTER TABLE organizations ADD COLUMN discoverable BOOLEAN NOT NULL DEFAULT FALSE;

-- One outgoing policy per (owner, scope). Audience and revision are owner-controlled.
CREATE TABLE organization_share_policies (
    id UUID PRIMARY KEY,
    owner_org_id UUID NOT NULL REFERENCES organizations (id),
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('CARE', 'VISITS', 'SITE_LABEL', 'PHOTO')),
    audience VARCHAR(32) NOT NULL CHECK (audience IN ('PRIVATE', 'ALL_DISCOVERABLE', 'ALLOWLIST')),
    revision INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_share_policy_owner_scope UNIQUE (owner_org_id, scope)
);
-- No separate (owner_org_id, scope) index: the UNIQUE constraint already
-- backs owner-side lookups.

-- Explicit ALLOWLIST recipients of one policy. Duplicate grants are impossible;
-- owner/recipient distinctness is enforced by the service layer.
CREATE TABLE organization_share_recipients (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL REFERENCES organization_share_policies (id) ON DELETE CASCADE,
    recipient_org_id UUID NOT NULL REFERENCES organizations (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_share_recipient_policy_org UNIQUE (policy_id, recipient_org_id)
);
-- No separate (policy_id) index: the UNIQUE constraint backs policy-side
-- lookups; the recipient-side index below serves allowlist invalidation.
CREATE INDEX idx_share_recipients_recipient ON organization_share_recipients (recipient_org_id);

-- Append-only audit ledger for grants, revocations and visibility-driven invalidation.
CREATE TABLE organization_share_audit (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES app_users (id),
    owner_org_id UUID NOT NULL REFERENCES organizations (id),
    recipient_org_id UUID REFERENCES organizations (id),
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('CARE', 'VISITS', 'SITE_LABEL', 'PHOTO')),
    action VARCHAR(16) NOT NULL CHECK (action IN ('GRANT', 'REVOKE', 'INVALIDATE')),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_share_audit_owner ON organization_share_audit (owner_org_id, created_at);
CREATE INDEX idx_share_audit_recipient ON organization_share_audit (recipient_org_id, created_at);
