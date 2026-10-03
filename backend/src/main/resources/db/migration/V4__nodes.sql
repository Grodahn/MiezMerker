CREATE TABLE nodes (
    node_id UUID PRIMARY KEY,
    public_key_x VARCHAR(64) NOT NULL,
    public_key_y VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    organization_id UUID REFERENCES organizations (id),
    state VARCHAR(16) NOT NULL,
    firmware_version VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_nodes_org ON nodes (organization_id);
