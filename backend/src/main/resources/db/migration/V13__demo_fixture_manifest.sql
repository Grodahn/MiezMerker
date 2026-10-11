-- Ownership ledger only. Normal startup never creates demo domain records.
-- The lock row serializes explicit setup invocations, including the first run.
CREATE TABLE demo_fixture_records (
    fixture_version VARCHAR(64) NOT NULL,
    record_key VARCHAR(64) NOT NULL,
    record_id UUID,
    PRIMARY KEY (fixture_version, record_key),
    CHECK ((record_key = 'lock' AND record_id IS NULL) OR
           (record_key <> 'lock' AND record_id IS NOT NULL))
);
INSERT INTO demo_fixture_records(fixture_version, record_key)
VALUES ('DEMO-ONLY-issue111-v1', 'lock');
