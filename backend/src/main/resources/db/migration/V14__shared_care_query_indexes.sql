-- #97: CARE-filtered batches need chip-first lookup across explicitly granted owners.
-- Existing cats UNIQUE(organization_id, chip_id) cannot serve the chip-first batch.
CREATE INDEX idx_cats_shared_chip_org ON cats (chip_id, organization_id);
-- Existing (organization_id, chip_id) lacks the stable paginated visit ordering.
CREATE INDEX idx_visits_shared_page ON derived_visits (organization_id, chip_id, start_at, id);
