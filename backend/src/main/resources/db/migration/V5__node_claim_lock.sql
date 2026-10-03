-- Serialize claim lookup + insert/update, including initially absent node rows.
CREATE TABLE node_claim_lock (id INTEGER PRIMARY KEY);
INSERT INTO node_claim_lock (id) VALUES (1);
