-- Issue #9: organization-scoped feeding sites, cats, historical node deployments
-- and immutable idempotent raw observations.
--
-- Tenant model: every business row carries organization_id (or, for deployments
-- and observations, is additionally reachable through an organization-owned
-- parent). Server code always authorizes via the ACTIVE membership and verifies
-- that referenced parents belong to the same organization; a client-supplied
-- organization id is never authority on its own.
--
-- Deployment history: valid_from is inclusive, valid_until is exclusive
-- (NULL = currently open). Overlap freedom per node is enforced by serializing
-- deployment writes on the node row in application code; the CHECK below keeps
-- every single interval well-formed at the database level.
--
-- Raw observations are immutable primary data. observed_at_ms is the raw RTC
-- value from the node (NULL exactly when clock_status = UNKNOWN). No
-- normalized/corrected time column exists here; derivation belongs to #10 and
-- must use separate derived information. feeding_site_id/deployment_id are a
-- frozen copy of the deployment valid at ingest time (NULL when the clock is
-- UNKNOWN or no deployment covers the timestamp); they are never rewritten
-- when the node later moves.

CREATE TABLE feeding_sites (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    location_lat DOUBLE PRECISION,
    location_lng DOUBLE PRECISION,
    location_label VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_feeding_site_name CHECK (char_length(name) >= 1),
    CONSTRAINT chk_feeding_site_location CHECK (
        (location_lat IS NULL AND location_lng IS NULL)
        OR (location_lat IS NOT NULL AND location_lng IS NOT NULL)
    ),
    CONSTRAINT chk_feeding_site_lat CHECK (location_lat IS NULL OR (location_lat >= -90 AND location_lat <= 90)),
    CONSTRAINT chk_feeding_site_lng CHECK (location_lng IS NULL OR (location_lng >= -180 AND location_lng <= 180))
);
CREATE INDEX idx_feeding_sites_org ON feeding_sites (organization_id);

CREATE TABLE cats (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    chip_id VARCHAR(64) NOT NULL,
    name VARCHAR(255),
    status VARCHAR(64),
    notes VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_cat_chip CHECK (char_length(chip_id) >= 1),
    CONSTRAINT uq_cat_org_chip UNIQUE (organization_id, chip_id)
);
CREATE INDEX idx_cats_org ON cats (organization_id);

CREATE TABLE node_deployments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    node_id UUID NOT NULL REFERENCES nodes (node_id),
    feeding_site_id UUID NOT NULL REFERENCES feeding_sites (id),
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_deployment_range CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX idx_deployments_org ON node_deployments (organization_id);
CREATE INDEX idx_deployments_node ON node_deployments (node_id);
CREATE INDEX idx_deployments_site ON node_deployments (feeding_site_id);

CREATE TABLE raw_observations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    node_id UUID NOT NULL REFERENCES nodes (node_id),
    sequence BIGINT NOT NULL,
    chip_id VARCHAR(64) NOT NULL,
    observed_at_ms BIGINT,
    clock_status VARCHAR(16) NOT NULL,
    incarnation VARCHAR(36),
    monotonic_ms BIGINT,
    boot_counter INTEGER,
    feeding_site_id UUID REFERENCES feeding_sites (id),
    deployment_id UUID REFERENCES node_deployments (id),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_raw_obs_seq CHECK (sequence >= 1),
    CONSTRAINT chk_raw_obs_chip CHECK (char_length(chip_id) >= 1),
    CONSTRAINT chk_raw_obs_clock CHECK (clock_status IN ('SYNCED', 'RTC_ONLY', 'UNKNOWN', 'KNOWN')),
    CONSTRAINT chk_raw_obs_time CHECK (
        (clock_status = 'UNKNOWN' AND observed_at_ms IS NULL)
        OR (clock_status <> 'UNKNOWN' AND observed_at_ms IS NOT NULL)
    ),
    CONSTRAINT chk_raw_obs_observed_nonneg CHECK (observed_at_ms IS NULL OR observed_at_ms >= 0),
    CONSTRAINT chk_raw_obs_monotonic_nonneg CHECK (monotonic_ms IS NULL OR monotonic_ms >= 0),
    CONSTRAINT chk_raw_obs_boot_nonneg CHECK (boot_counter IS NULL OR boot_counter >= 0),
    CONSTRAINT uq_raw_obs_node_seq UNIQUE (node_id, sequence)
);
CREATE INDEX idx_raw_obs_org ON raw_observations (organization_id);
CREATE INDEX idx_raw_obs_node ON raw_observations (node_id);
CREATE INDEX idx_raw_obs_chip ON raw_observations (organization_id, chip_id);
CREATE INDEX idx_raw_obs_site ON raw_observations (feeding_site_id);
CREATE INDEX idx_raw_obs_observed ON raw_observations (observed_at_ms);
CREATE INDEX idx_raw_obs_received ON raw_observations (received_at);

ALTER TABLE nodes ADD COLUMN protocol_version VARCHAR(32);
ALTER TABLE nodes ADD COLUMN status_note VARCHAR(500);
ALTER TABLE nodes ADD COLUMN last_contact_at TIMESTAMP WITH TIME ZONE;
