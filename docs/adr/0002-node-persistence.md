# 0002 — Immutable versioned raw log on the Node
Status: Accepted direction; exact flash layout deferred to persistence work.

Store raw observations in an append-only, versioned record log with integrity
checking and crash-safe commit markers. Keep resumable acknowledgement/cursor
metadata separate from raw records. Event identity includes an incarnation so a
factory reset cannot reuse old Node/sequence identities. No visits on the Node.
Byte layout, capacity/overflow, wear-leveling and recovery golden fixtures must be
specified under protocol before the durable storage adapter is implemented.
