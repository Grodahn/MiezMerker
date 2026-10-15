-- PostgreSQL nextval is non-transactional: rolled-back/deleted allocations are
-- deliberately never reused. Exhaustion at INT32_MAX fails rather than cycling.
CREATE SEQUENCE node_bowl_number_seq AS INTEGER START WITH 1 MINVALUE 1 MAXVALUE 2147483647 NO CYCLE;
ALTER TABLE nodes ADD COLUMN bowl_number INTEGER;
-- Correlated rank is deterministic on both PostgreSQL and H2, including ties.
UPDATE nodes SET bowl_number = (SELECT COUNT(*) FROM nodes n
    WHERE n.created_at < nodes.created_at OR (n.created_at = nodes.created_at AND n.node_id <= nodes.node_id));
-- Consume all backfilled values, even for an empty database (zero calls).
SELECT nextval('node_bowl_number_seq') FROM nodes ORDER BY bowl_number;
ALTER TABLE nodes ALTER COLUMN bowl_number SET DEFAULT nextval('node_bowl_number_seq');
ALTER TABLE nodes ALTER COLUMN bowl_number SET NOT NULL;
ALTER TABLE nodes ADD CONSTRAINT uq_nodes_bowl_number UNIQUE (bowl_number);
ALTER TABLE nodes ADD CONSTRAINT ck_nodes_bowl_number_positive CHECK (bowl_number > 0);
