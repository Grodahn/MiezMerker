# BLE boundary (separate from HTTP OpenAPI)

This directory owns versioned BLE/GATT wire definitions, codecs and fixtures.
The logical observation exchange format is fixed by `raw-observation-v1.schema.json`
and its shared JSON fixture. No GATT UUID, frame layout, cryptographic algorithm
or storage byte format is finalized by #2. These must be agreed here before
firmware/PWA agents implement their adapters; the boundary requirements below are binding.

## Logical record contract

The schema is the BLE record model, separate from backend-owned HTTP DTOs. JSON
is the lossless reference/fixture representation, not a choice of BLE frame codec.
Node identity and incarnation are opaque, nonempty identifiers; incarnation changes
whenever persistent sequence identity would reset. `(node_id, incarnation, sequence)`
is event identity. Sequence and times use canonical unsigned decimal strings so
JavaScript cannot round 64-bit values. `monotonic_ms` is the Node clock at reading;
`observed_at_epoch_ms` is UTC Unix milliseconds, null when `clock_status` is unknown.
`known` means the Node has wall-clock context, not that the backend must trust its
accuracy. Chip identity is the unchanged canonical identifier emitted by the reader
adapter; reader-specific normalization must be specified before capture work.

Records contain no current FeedingSite, Organization access grant or DerivedVisit.
The backend determines tenant/deployment authority independently. GATT codecs must
preserve this model exactly and publish golden byte fixtures alongside it. The
committed fixture includes a sequence above JavaScript's safe integer range and
an unknown wall clock. Schema validation is included in the app test suite; future
core codecs can share the same fixtures without relying on frontend code.

- Raw data: stable Node identity, reset/sequence epoch, monotonic record sequence,
  chip identity, recorded device timestamp plus clock-quality metadata.
- Sync: explicit protocol version, resumable cursor/range and durable acknowledgement.
  A PWA may acknowledge only records durably committed locally. Backend upload is
  a separate action and never a prerequisite for Node sync.
- Retransmission must preserve raw bytes and event identity. Firmware never emits
  visits; PWA never aggregates visits.
- BLE authorization must work without network access using per-AppDevice identity
  and independently issued, bounded credentials. Backend session cookies are not
  Node credentials. No employee-wide organization secret.
- Ownership/claim/reset lifecycle must avoid reusing event identities after reset.
- Versioned golden byte fixtures will live here alongside the eventual codec, not
  in backend OpenAPI. No invented provisional wire format is shipped.

See ADRs 0002, 0003, 0008 and 0009 for deferred decisions and their owning tickets.
