-- Preserve every identity, role and status; existing rows start at revision zero.
ALTER TABLE organization_memberships ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
