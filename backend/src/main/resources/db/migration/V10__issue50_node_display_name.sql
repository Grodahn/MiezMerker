-- Issue #50: human-readable bowl label on the existing Node entity.
--
-- Node = physical bowl + electronics; node_id stays the stable technical
-- identity (BLE protocol, claiming, crypto identity, RawObservation keys,
-- deployment references). display_name is optional product metadata only:
-- nullable so pre-#50 rows survive unchanged, no unique constraint (two
-- nodes in one organization may share e.g. 'Silberner Napf'), never derived
-- from UUID prefixes, MACs, FeedingSite names or firmware versions, and never
-- part of BLE/crypto identity. Historical deployments, raw observations and
-- derived visits are untouched.
--
-- Max 100 characters after trimming: comfortably fits labels such as
-- 'Silberner Napf am Unterstand' while staying short enough for bowl labels.
-- Unicode allowed; blank-only input is normalized to NULL in application
-- code, not by a DB default.
--
-- Compatible with PostgreSQL and the H2 test setup (MODE=PostgreSQL).

ALTER TABLE nodes ADD COLUMN display_name VARCHAR(100);
