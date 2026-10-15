-- PostgreSQL nextval is non-transactional: rolled-back/deleted allocations are
-- deliberately never reused. Exhaustion at INT32_MAX fails rather than cycling.
CREATE SEQUENCE node_bowl_number_seq AS INTEGER START WITH 1 MINVALUE 1 MAXVALUE 2147483647 NO CYCLE;
ALTER TABLE nodes ADD COLUMN bowl_number INTEGER;
-- Rank once, rather than rescanning all nodes for every row. PostgreSQL and H2
-- support the standard MERGE form; UUID breaks timestamp ties deterministically.
MERGE INTO nodes n
USING (SELECT node_id, CAST(ROW_NUMBER() OVER (ORDER BY created_at, node_id) AS INTEGER) AS bowl_number
       FROM nodes) numbered
ON n.node_id = numbered.node_id
WHEN MATCHED THEN UPDATE SET bowl_number = numbered.bowl_number;
-- Consume all backfilled values, even for an empty database (zero calls).
SELECT nextval('node_bowl_number_seq') FROM nodes ORDER BY bowl_number;
ALTER TABLE nodes ALTER COLUMN bowl_number SET DEFAULT nextval('node_bowl_number_seq');
ALTER TABLE nodes ALTER COLUMN bowl_number SET NOT NULL;
ALTER TABLE nodes ADD CONSTRAINT uq_nodes_bowl_number UNIQUE (bowl_number);
ALTER TABLE nodes ADD CONSTRAINT ck_nodes_bowl_number_positive CHECK (bowl_number > 0);
