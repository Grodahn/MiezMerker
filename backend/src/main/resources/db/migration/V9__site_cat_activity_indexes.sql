-- Bound both aggregates by organization + frozen site before grouping by chip.
CREATE INDEX idx_raw_obs_org_site_chip ON raw_observations
    (organization_id, feeding_site_id, chip_id, received_at);
CREATE INDEX idx_visits_org_site_algo_chip ON derived_visits
    (organization_id, feeding_site_id, algorithm_version, chip_id, end_at);
