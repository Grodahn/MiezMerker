# 0012 — Firmware-core raw observations: sequence-first persistence

Status: Accepted (issue #5 implementation contract for the capture pipeline).

## Context

Issue #5 needs a hardware-independent core that turns RFID reads into durable,
immutable raw observations on an ESP32-C3 node. The critical hazards are
duplicate `(node_id, sequence)` pairs after crash/reboot, rewritten timestamps
after RTC correction, silent data loss when storage fills, and business logic
(visits) leaking into the node. ADRs 0001 (portable core), 0002 (immutable raw
log) and 0009 (node lifecycle) set the direction; this ADR fixes the
mechanism.

## Decision

- **Reserve-then-append with reboot reconciliation.** The identity store
  persists `next_sequence + 1` before the observation is appended; boot
  reconciles the counter against `max(persisted sequence) + 1`. Gaps after a
  crash are accepted; reuse is excluded. Uniqueness outranks gaplessness.
- **Identity pair.** `node_id` (UUIDv4 app identity, never MAC/DB id) plus an
  `incarnation` value; both are regenerated together on factory reset, never
  on reboot. Event identity is `(node_id, incarnation, sequence)`.
- **Clock states `SYNCED` / `RTC_ONLY` / `UNKNOWN`.** The RTC value at read
  time is stored with its then-current trust; `UNKNOWN` stores no epoch value;
  corrections affect future reads only. Maps to protocol v1 (`SYNCED` and
  `RTC_ONLY` → `known`, `UNKNOWN` → `unknown`/null); a future protocol version
  may expose the distinction on the wire (#6).
- **Technical debounce only.** Fixed-window per-chip sampling on the monotonic
  clock (default 2000 ms, configurable, RAM-only). No visits, no sessions, no
  deletions.
- **Full store rejects.** Pre-check before sequence reservation (no gaps on
  the common path); never overwrite pending or acknowledged data to continue
  recording. Compaction of synced records is #6 work; the ack watermark is
  persisted now so #6 can build on it.
- **Crypto/claim deferred.** A persisted `UNCLAIMED`/`CLAIMED` placeholder
  keeps the #18 shape; key generation and claiming stay out of #5.

## Consequences

- An ESP32-C3 adapter must implement atomic identity/observation writes
  (commit markers, torn-tail discard) to inherit the proven guarantees; the
  core cannot compensate for a non-atomic store.
- Sync (#6) must treat sequence gaps as normal and must never assume
  contiguity; backend ingestion (#7) deduplicates by full event identity.
- Protocol v1 stays unchanged in #5; the lossy `SYNCED`/`RTC_ONLY` → `known`
  mapping is documented in `protocol_view.hpp` and tested.
