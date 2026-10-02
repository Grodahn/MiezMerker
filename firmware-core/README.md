# firmware-core — raw observation capture (issue #5)

Hardware-independent C++20 core. No ESP32/ESP-IDF, OS or browser API.
Target board for the real firmware is **ESP32-C3**; its adapters live in
`firmware-esp32` (later ticket) and implement the ports defined here.

## RawObservation lifecycle

```text
RFID read → validate chip_id → debounce check → reserve sequence
  → sample RTC (timestamp + clock_status at read time)
  → durable append → RECORDED
```

An observation counts as captured **only after durable persistence succeeds**
(`ObservationStore::append` returns `OK`). `DEBOUNCED`, `REJECTED_FULL`,
`REJECTED_INVALID`, `NOT_READY` and `IO_ERROR` create nothing and delete
nothing. Persisted observations are immutable: no later operation rewrites
their timestamp, clock status or chip data.

Fields: `node_id`, `incarnation`, `sequence`, `chip_id`,
`observed_at_epoch_ms` (null for `UNKNOWN`), `clock_status`,
`monotonic_ms` (device clock at read), `boot_counter` (recording boot epoch).

## Sequence guarantees

- `sequence` starts at 1 per node identity; 0 is invalid and never used.
- Strictly monotonic per `(node_id, incarnation)` across reboots.
- Strategy: **reserve-then-append with reboot reconciliation**.
  The identity counter is persisted *before* the observation is appended, so a
  crash between the two leaves a gap, never a duplicate. Every boot
  reconciles `next_sequence` against `max(persisted sequence) + 1`.
- Tradeoff is deliberate: **uniqueness beats gaplessness**. Skipped sequences
  after a crash are expected and must not be "repaired" by reuse.
- `(node_id, sequence)` is therefore unique for every observation the node
  ever persists; `(node_id, incarnation, sequence)` is the full event identity
  for BLE sync (#6) and backend ingestion.

## Node identity lifecycle (per #18, crypto/claim deferred)

- `node_id` is a persistent random UUIDv4 (128-bit application identity),
  generated from the injected `RandomSource`. Never a BLE/ESP MAC, never a
  database ID.
- `incarnation` is a second random 128-bit value, regenerated together with
  `node_id` on every factory reset.
- Normal reboot, power loss and firmware update retain `node_id`,
  `incarnation`, the sequence counter, all persisted observations and the
  acknowledgement watermark. Only the volatile debounce cache and
  `boot_counter` (incremented, persisted) change.
- **Factory reset** (`Core::factory_reset`) is a distinct operation: it erases
  observations, ack state and debounce memory, then provisions a **new**
  `node_id` + `incarnation` starting a new sequence lifetime at 1. The same
  `node_id` with a reset counter is impossible by construction.
- `ClaimState` (`UNCLAIMED`/`CLAIMED`) is persisted as a minimal #18
  placeholder; cryptographic device keys and organization claiming are #17/#18
  work and intentionally absent here.

## Clock / timestamp semantics

- `timestamp` (`observed_at_epoch_ms`) is the RTC value **actually observed at
  read time**, stored together with the `clock_status` that applied then.
- `SYNCED`: RTC disciplined by a trusted source (e.g. App-Sync); value is
  trustworthy wall-clock time.
- `RTC_ONLY`: RTC running but not externally synced; the raw value is stored
  but must NOT be treated as accurate wall-clock time.
- `UNKNOWN`: RTC invalid/unset; no epoch value is stored (null) and no
  precision is pretended.
- Later RTC corrections apply to **future reads only**; persisted records are
  never rewritten or redated.
- `sequence` orders observations deterministically when the clock is bad, but
  implies no real elapsed time. `monotonic_ms` is technical metadata for
  debounce/ordering, never wall-clock time.

## Technical deduplication (and what it is not)

- Only suppression allowed: repeats of the **identical** `chip_id` within a
  configurable monotonic window (`CoreConfig::debounce_interval_ms`, default
  2000 ms, 0 disables) after a recorded observation are reported as
  `DEBOUNCED`.
- Fixed-window sampling per chip: suppressed reads consume no sequence, create
  no record and refresh no timer; different chip IDs are independent.
- The window lives in RAM and is lost on reboot (a read right after reboot
  records; suppressing it would risk silent loss).
- NOT allowed and NOT implemented: grouping reads into visits, inferring
  feeding sessions, deleting observations for business reasons.

## Storage-full behavior

- The store is append-only with fixed capacity; `append` returns `FULL`
  instead of overwriting. `NEARLY_FULL` (≥90% occupancy) is a warning; `FULL`
  rejects new reads with `REJECTED_FULL`.
- Rejection happens **before** sequence reservation, so a full store causes no
  sequence gaps. A residual race (`FULL` between check and append) yields a
  documented gap, never a reuse.
- Nothing is ever silently deleted to make room — not pending and not
  acknowledged data. Acknowledging (`set_ack_watermark`, monotonic, persisted
  across reboot) does not free space; compaction of synced records belongs to
  #6.
- Operators must sync (#6) and, once #6 provides compaction, reclaim space.
  Until then a full node keeps serving old data and refuses new reads loudly.

## Crash / power-loss guarantees

- Store implementations must be atomic: power loss during a write leaves
  either the previous state or the fully committed record, never a torn record
  and never corruption of earlier records.
- An observation is captured only after durable success; anything else is
  reported (`IO_ERROR`) and can be retried by presenting the tag again.
- Reboot preserves identity, sequence state, all persisted observations
  (including unacknowledged ones) and the ack watermark.

## Ports (for #6, #7, #18 and ESP32-C3 adapters)

`Clock` (monotonic + RTC reading), `RandomSource`, `DeviceIdentityStore`,
`ObservationStore` (+ ack watermark), `RfidReader`. `BleTransport` is the
untouched #6 boundary. `protocol_view.hpp` renders observations as
protocol-v1 JSON (`SYNCED`/`RTC_ONLY` → `known`, `UNKNOWN` → `unknown`/null)
as a bridge for #6; the GATT codec itself is #6 work.
