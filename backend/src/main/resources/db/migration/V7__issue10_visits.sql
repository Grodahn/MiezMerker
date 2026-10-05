-- Issue #10: deterministic backend-side visit aggregation (visit-gap-v1).
--
-- DerivedVisits are secondary rebuildable data computed from immutable
-- RawObservations. Raw observations are never modified, deleted or rewritten
-- when visits are built; recompute deletes only derived_visits rows of one
-- organization and algorithm version inside a single transaction.
--
-- Grouping uses the frozen feeding_site_id stored on each raw observation at
-- ingest time (historical NodeDeployment attribution from #9). The node's
-- current site is never consulted, so moving a node later cannot reinterpret
-- old observations or visits. Observations without frozen site attribution
-- (UNKNOWN clock rows or timestamps outside any deployment interval) carry
-- NULL feeding_site_id and are excluded from time-based aggregation.
--
-- Clock policy (visit-gap-v1): only rows with clock_status in
-- (SYNCED, RTC_ONLY, KNOWN), a non-null observed_at_ms inside the finite
-- PostgreSQL range, and a non-null feeding_site_id participate. UNKNOWN,
-- implausible or unattributed rows are excluded and stay visible via the raw
-- observation API; the recompute response reports excluded counts.
--
-- Boundary: consecutive observations belong to the same visit while
-- gap <= threshold (inclusive). Default threshold is 60 seconds, configurable
-- via miezmerker.visits.default-gap-seconds and per recompute request.
-- Ordering inside a group is (observed_at_ms, node_id, sequence, id) so equal
-- timestamps and ingest order never affect the result.
--
-- cat_id is a best-effort snapshot at recompute time with NO foreign-key
-- constraint on purpose: visits must never block cat management. The current
-- cat can always be resolved live via (organization_id, chip_id).
-- first/last observation references likewise carry no FK constraint; they are
-- deterministic provenance UUIDs into the immutable raw log.

CREATE TABLE derived_visits (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    feeding_site_id UUID NOT NULL REFERENCES feeding_sites (id),
    chip_id VARCHAR(64) NOT NULL,
    cat_id UUID,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observation_count INTEGER NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    gap_seconds INTEGER NOT NULL,
    first_observation_id UUID NOT NULL,
    last_observation_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_visit_count CHECK (observation_count >= 1),
    CONSTRAINT chk_visit_range CHECK (end_at >= start_at),
    CONSTRAINT chk_visit_gap CHECK (gap_seconds >= 1),
    CONSTRAINT chk_visit_chip CHECK (char_length(chip_id) >= 1),
    CONSTRAINT uq_visit_org_algo_site_chip_start UNIQUE (organization_id,
        algorithm_version, feeding_site_id, chip_id, start_at)
);
CREATE INDEX idx_visits_org ON derived_visits (organization_id);
CREATE INDEX idx_visits_org_chip ON derived_visits (organization_id, chip_id);
CREATE INDEX idx_visits_site ON derived_visits (feeding_site_id);
CREATE INDEX idx_visits_start ON derived_visits (start_at);
CREATE INDEX idx_visits_algo ON derived_visits (organization_id, algorithm_version);
