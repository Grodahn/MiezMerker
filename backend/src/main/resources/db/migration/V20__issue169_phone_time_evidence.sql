-- Convenience observation evidence only. Signed anchor constraints stay intact.
CREATE TABLE phone_time_anchors (
 anchor_id UUID PRIMARY KEY,
 organization_id UUID NOT NULL REFERENCES organizations(id),
 node_id UUID NOT NULL REFERENCES nodes(node_id),
 incarnation UUID NOT NULL,
 boot_counter BIGINT NOT NULL CHECK (boot_counter BETWEEN 1 AND 4294967295),
 uptime_ms NUMERIC(20,0) NOT NULL CHECK (uptime_ms BETWEEN 0 AND 18446744073709551615),
 utc_ms BIGINT NOT NULL CHECK (utc_ms > 0),
 transport_duration_ms BIGINT NOT NULL CHECK (transport_duration_ms >= 0),
 source VARCHAR(32) NOT NULL CHECK (source = 'PHONE_ESTIMATE'),
 format_version INTEGER NOT NULL CHECK (format_version = 1)
);
CREATE INDEX idx_phone_time_owner_boot ON phone_time_anchors(organization_id, node_id, incarnation, boot_counter, uptime_ms);
