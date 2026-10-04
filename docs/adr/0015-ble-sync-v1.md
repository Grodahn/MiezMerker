# 0015 — BLE sync protocol v1 between NapfNode (ESP32-C3) and PWA collector

Status: Accepted (implements #6).

## Context

#6 requires a robust, versioned BLE/GATT sync between NapfNode and the PWA
collector: authenticated/offline-authorized sessions, transfer of pending
`RawObservation`s, durable client persistence before ACK, contiguous
high-watermark ACK, retry after disconnects, idempotent retransmission, RTC
correction for future reads only, public owner metadata for foreign orgs, and
no protected leakage before authorization.

Security semantics are fixed by #17 (ADR-0012: JWS/ES256 offline credentials,
per-AppDevice key binding, challenge/response proof-of-possession) and #18
(ADR-0013: random UUIDv4 `node_id` + P-256 device key, claim lifecycle, public
owner hint). #6 must reuse them unchanged. Target hardware is **ESP32-C3**
(NimBLE, MTU negotiation, no OS pairing requirement).

## Decision

**Versioned binary GATT protocol v1 with three separated layers:**

1. **Domain state machine** (`firmware-core/ble_sync`, PWA `sync-engine`):
   per-connection session `PUBLIC -> CHALLENGE -> AUTHORIZED -> SYNCING`,
   foreign orgs stay on the public subset, protected ops require
   `can_sync(trusted_utc)`. App persists durably before ACK and ACKs only the
   contiguous prefix. Node frees data only after a valid ACK via explicit
   compaction. RTC correction applies to future reads only.
2. **Transport-independent codec** (`firmware-core/ble_codec`, PWA
   `ble-codec`, shared golden fixtures in `protocol/fixtures/ble-sync-v1.json`):
   `[ver u8][opcode u8][len u16 LE][payload]`, all integers little-endian,
   strings as `[len u16 LE][UTF-8]`. No BLE/NimBLE/Web-Bluetooth types leak
   into this layer. Unknown versions fail closed with `UNKNOWN_VERSION`.
3. **GATT transport** (`firmware-esp32/components/ble-sync`, PWA
   `SyncChannel` over Web Bluetooth): fixed 128-bit service + 9
   characteristics (see `protocol/ble/messages.md`), NimBLE long-write /
   notify fragmentation, advertisement with technical discovery only.

Wire details, UUIDs, advertisement bytes, error codes, ACK math and the full
transcript live in `protocol/ble/`; this ADR fixes the layering and the
non-goals.

- Reuse: AppDevice credentials, backend-signed offline JWT (`iss=miezmerker`,
  `kind=offline`, `ver=1`, `org/sub/dev/dpk/dpf/role/scope/iat/exp/jti`),
  32-byte fresh challenge per connection (single-use, every attempt consumes
  the nonce), node P-256 session proof
  (`MM-NODE-SESSION-v1\n{node_id}\n{base64url_nonce}`), organization binding,
  claim state / owner metadata, ES256/raw `r||s` formats and the deterministic
  vectors in `protocol/fixtures/offline-credential-v1.json`. No second
  credential format.
- Advertisement: flags + service UUID only. No chip IDs, no pending counts, no
  org data, no full `node_id`. Owner metadata is delivered after GATT
  connection on the public `Owner` characteristic, never broadcast.
- Pending counts, chip IDs and observations are protected: `Status`, `Batch`,
  `Ack`, `Time`, `Compact` require a valid, unexpired, org-matching credential
  plus proof-of-possession of the bound AppDevice key, checked against a
  trusted clock (never a PWA-supplied clock). Foreign orgs receive only the
  public owner hint.
- Time correction is a protected `TimeCorrect` op carrying UTC epoch ms. The
  node disciplines its RTC; already persisted `RawObservation`s are immutable
  and never rewritten. `UNKNOWN` clocks store no epoch.
- ACK is a contiguous high-watermark (`highest_contiguous_persisted_sequence`).
  The PWA computes it over its durable store; the node enforces monotonicity
  (`new >= current`) and `<= last durable sequence`, persists the cursor
  atomically and compacts only the acked prefix on explicit `Compact`.
  Retransmission preserves raw bytes and event identity
  `(node_id, incarnation, sequence)`.

## Alternatives considered

- CBOR/COSE for observations: rejected for v1. JWS/ES256 was already chosen
  for credentials (ADR-0012) because ESP32-C3/mbed-TLS parses flat
  `header.payload.signature` without a CBOR decoder; observations use an even
  simpler fixed-field binary for the same reason. COSE stays a future size
  optimization.
- JSON over GATT: rejected. UTF-8 decimal strings are kept only in the
  `protocol_view` reference rendering and the backend fixtures; the GATT wire
  uses little-endian binary to fit MTU 23..517 and to avoid float/precision
  hazards in JavaScript (u64 carried as decimal strings in JSON, as u64 LE on
  the wire, decoded to `BigInt` in TS).
- Shared organization secret / OS pairing as auth: rejected by #17. v1 uses no
  shared secret and requires no pairing; pairing may be enabled by the OS but
  confers no authorization.

## Consequences

- ESP32-C3 implements the GATT mapping with NimBLE, a board CSPRNG for
  `begin()` challenges, a trusted RTC/clock source for `can_sync`, and
  flash/NVS adapters honoring the atomicity contracts from ADR-0012
  (firmware-core observations). Host tests compile the same domain + codec
  against fakes; board wiring remains a thin documented shim (#4).
- PWA implements the mirror codec + durable Dexie stores + contiguous
  watermark; Web Bluetooth stays behind `SyncChannel` so management pages
  never depend on BLE. Backend is unchanged by #6 (owner hint already public).
- Versioning: `ver` byte negotiates (`min(client, server)`); v1 server
  answers any `ver != 1` with `UNKNOWN_VERSION` and no protected data.
  Capability bits advertise `BATCH/ACK/TIME/NODE_PROOF/COMPACT`; additive
  opcodes bump minor, incompatible framing bumps major.
- Robustness matrix from #6 (abort before/mid/after-persist, lost ACK,
  duplicate batch, node/app restart, many pending, unknown version, invalid
  record, same-org/foreign/expired/forged/no-key/replay/fake-node) is covered
  by `firmware-core` unit tests, the simulator sync scenarios and PWA vitest
  suites, all without hardware.
