-- Audit only: firmware-reported uncertainty never replaces backend policy.
ALTER TABLE time_anchors ADD COLUMN node_metadata VARCHAR(1024);
