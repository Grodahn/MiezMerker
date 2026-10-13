-- Additive evidence only: no UPDATE of raw observations or visit-gap-v1.
CREATE TABLE boot_epochs (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL REFERENCES organizations(id),
 node_id UUID NOT NULL REFERENCES nodes(node_id),
 incarnation UUID NOT NULL,
 boot_counter BIGINT NOT NULL CHECK (boot_counter BETWEEN 1 AND 4294967295),
 reset_reason BIGINT NOT NULL CHECK (reset_reason BETWEEN 0 AND 4294967295),
 started_uptime_ms NUMERIC(20,0) NOT NULL CHECK (started_uptime_ms BETWEEN 0 AND 18446744073709551615),
 first_possible_sequence NUMERIC(20,0) NOT NULL CHECK (first_possible_sequence BETWEEN 1 AND 18446744073709551615),
 format_version INTEGER NOT NULL CHECK (format_version = 1),
 provenance VARCHAR(64) NOT NULL,
 UNIQUE(node_id, incarnation, boot_counter),
 UNIQUE(id, organization_id, node_id, incarnation, boot_counter)
);
CREATE INDEX idx_boot_epochs_owner_order ON boot_epochs(organization_id, node_id, incarnation, boot_counter);
CREATE TABLE time_anchors (
 id UUID PRIMARY KEY,
 anchor_id UUID NOT NULL,
 organization_id UUID NOT NULL REFERENCES organizations(id),
 node_id UUID NOT NULL REFERENCES nodes(node_id),
 incarnation UUID NOT NULL,
 boot_counter BIGINT NOT NULL,
 boot_epoch_id UUID NOT NULL,
 uptime_ms NUMERIC(20,0) NOT NULL CHECK (uptime_ms BETWEEN 0 AND 18446744073709551615),
 challenge_uptime_ms NUMERIC(20,0) NOT NULL CHECK (challenge_uptime_ms BETWEEN 0 AND uptime_ms),
 utc_ms BIGINT NOT NULL CHECK (utc_ms > 0),
 proof_id UUID NOT NULL UNIQUE,
 proof_digest VARCHAR(43) NOT NULL UNIQUE,
 nonce_digest VARCHAR(43) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 proof VARCHAR(2048) NOT NULL,
 challenge_bytes VARCHAR(512) NOT NULL,
 acceptance_bytes VARCHAR(512) NOT NULL,
 trust_status VARCHAR(32) NOT NULL CHECK (trust_status = 'DEVICE_ACCEPTANCE_VERIFIED'),
 source VARCHAR(32) NOT NULL CHECK (source = 'BACKEND_SIGNED'),
 uncertainty_minus_ms BIGINT,
 uncertainty_plus_ms BIGINT,
 server_error_ms BIGINT CHECK (server_error_ms IS NULL OR server_error_ms > 0),
 sampling_ms BIGINT CHECK (sampling_ms IS NULL OR sampling_ms > 0),
 drift_ppm BIGINT,
 policy_provenance VARCHAR(500),
 format_version INTEGER NOT NULL CHECK (format_version = 1),
 FOREIGN KEY(boot_epoch_id, organization_id, node_id, incarnation, boot_counter)
   REFERENCES boot_epochs(id, organization_id, node_id, incarnation, boot_counter),
 UNIQUE(node_id, incarnation, boot_counter, nonce_digest),
 UNIQUE(organization_id, node_id, incarnation, boot_counter, anchor_id),
 CHECK (uncertainty_minus_ms IS NULL OR uncertainty_minus_ms > 0),
 CHECK (uncertainty_plus_ms IS NULL OR uncertainty_plus_ms > 0),
 CHECK (drift_ppm IS NULL OR drift_ppm BETWEEN 1 AND 999999),
 CHECK ((uncertainty_minus_ms IS NULL AND uncertainty_plus_ms IS NULL AND drift_ppm IS NULL AND policy_provenance IS NULL AND server_error_ms IS NULL AND sampling_ms IS NULL)
   OR (uncertainty_minus_ms IS NOT NULL AND uncertainty_plus_ms IS NOT NULL AND drift_ppm IS NOT NULL AND policy_provenance IS NOT NULL AND server_error_ms IS NOT NULL AND sampling_ms IS NOT NULL))
);
CREATE INDEX idx_time_anchors_owner_boot ON time_anchors(organization_id, node_id, incarnation, boot_counter, uptime_ms);
CREATE INDEX idx_raw_obs_temporal_page ON raw_observations(organization_id, node_id, sequence);
CREATE INDEX idx_raw_obs_boot_sequence ON raw_observations(organization_id, node_id, incarnation, boot_counter, sequence);

